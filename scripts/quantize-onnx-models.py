"""
Quantize exported ONNX models to INT8 for the inference nodes.

静态量化（QDQ）把卷积也转成整数，是移动端真正能提速的一档；动态量化只量化权重，
对以卷积为主的 YOLO 提速有限，这里保留它作为不需要校准集的退路。

两条实测结论决定了默认参数：

1. 校准集必须是真实画面。只用夜间截图校准时，模型学到的分数上限是 0.44，所有高分
   目标被截断成同一个值（0.676）；换成真实照片后，检测框数量一致、IoU ≥ 0.90、
   中心偏移 ≤ 4 px。校准目录要覆盖白天 / 夜间 / 不同角度。
2. 必须排除"大小量程混用"的检测头（见 `--exclude head`）。默认 `none` 会把类别
   分数压成 0，检测输出全空。

要求 onnxruntime >= 1.17。YOLOv5u 导出为 opset 12，静态量化会自动先升到 opset 13 并
核对升级前后输出一致；不升级会得到 onnxruntime 拒绝加载的模型。

Usage:
  # 静态量化（需要校准画面）
  python scripts/quantize-onnx-models.py --model server/data/models/yolo26m_640.onnx --mode static

  # 指认真实画面校准目录
  python scripts/quantize-onnx-models.py --model server/data/models/yolo26m_640.onnx \
      --mode static --calib-dir .local/model-quant/calib-photos --calib-count 20

  # 动态量化（无校准集）
  python scripts/quantize-onnx-models.py --model server/data/models/yolo26m_640.onnx --mode dynamic

  # 批量
  python scripts/quantize-onnx-models.py --all --mode static --only yolo26

  # 校验：比较 FP32 与 INT8 的输出差异与本机耗时（INT8 与源模型放在同一目录）
  python scripts/quantize-onnx-models.py --verify server/data/models/yolo26m_640.onnx

排除范围用 `--exclude` 控制：`head`（默认）只把"大小量程混用"的检测头留在浮点，其余
算子照常量化——这是实测唯一能保住检测输出的做法；`postprocess` 只排除 NMS 段；`all`
排除全部节点（对照用）；`none` 不排除（已知会把类别分数压成 0）。`--exclude-from` 可按
节点名子串追加排除起点。

Output: <model>_int8.onnx next to the source model (override with --out-dir).
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import numpy as np

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MODEL_DIR = REPO_ROOT / "server" / "data" / "models"
DEFAULT_CALIB_DIR = REPO_ROOT / ".local" / "kpad-inference-ui"

# 与端侧预处理保持一致：letterbox 灰边与归一化方式。
PAD_VALUE = 114
MEAN = 0.0
SCALE = 1.0 / 255.0


def read_model_size(model_path: Path) -> int:
    import onnx

    model = onnx.load(str(model_path), load_external_data=False)
    for item in model.graph.input:
        dims = [d.dim_value for d in item.type.tensor_type.shape.dim]
        if len(dims) >= 4 and dims[2] > 0:
            return int(dims[2])
    return 320


def letterbox(image, size: int):
    """等比缩放到 size×size，空白用灰边填充。"""
    from PIL import Image

    width, height = image.size
    scale = min(size / width, size / height)
    new_size = (max(1, int(round(width * scale))), max(1, int(round(height * scale))))
    resized = image.resize(new_size, Image.BILINEAR)
    canvas = Image.new("RGB", (size, size), (PAD_VALUE, PAD_VALUE, PAD_VALUE))
    canvas.paste(resized, ((size - new_size[0]) // 2, (size - new_size[1]) // 2))
    return canvas


def to_tensor(image, size: int) -> np.ndarray:
    canvas = letterbox(image, size)
    array = np.asarray(canvas, dtype=np.float32)
    array = (array - MEAN * 255.0) * SCALE
    chw = np.transpose(array, (2, 0, 1))
    return np.ascontiguousarray(chw[np.newaxis, ...])


class FrameCalibrationReader:
    """校准数据：从本机截图里裁出画面区域，缩放到模型输入尺寸。"""

    def __init__(self, images: list[np.ndarray], input_name: str):
        self._images = images
        self._input_name = input_name
        self._index = 0

    def get_next(self):
        if self._index >= len(self._images):
            return None
        data = self._images[self._index]
        self._index += 1
        return {self._input_name: data}

    def rewind(self):
        self._index = 0


def load_calibration(calib_dir: Path, size: int, limit: int) -> list[np.ndarray]:
    from PIL import Image

    if not calib_dir.is_dir():
        raise SystemExit(f"校准目录不存在：{calib_dir}")

    files = sorted(calib_dir.glob("*.png")) + sorted(calib_dir.glob("*.jpg"))
    if not files:
        raise SystemExit(f"校准目录没有图片：{calib_dir}")

    # 均匀取样，避免只拿到同一类画面（空态、运行时、抽屉各占一部分）。
    step = max(1, len(files) // limit)
    picked = files[::step][:limit]

    tensors = []
    for path in picked:
        with Image.open(path) as image:
            image = image.convert("RGB")
            width, height = image.size
            # 截图里画面通常占中间偏左的大块，中心裁 70% 足以覆盖画面内容。
            box = (int(width * 0.15), int(height * 0.1), int(width * 0.85), int(height * 0.9))
            tensors.append(to_tensor(image.crop(box), size))
    print(f"校准样本 {len(tensors)} 张（来自 {calib_dir}）")
    return tensors


#: 后处理里对量化最敏感的算子：以它们为锚点，把整段后处理排除在量化范围外。
POSTPROCESS_ANCHORS = {"NonMaxSuppression"}


def _consumers(graph) -> dict[str, list[int]]:
    consumers: dict[str, list[int]] = {}
    for index, node in enumerate(graph.node):
        for name in node.input:
            consumers.setdefault(name, []).append(index)
    return consumers


def _downstream_closure(graph, start_indexes: set[int]) -> set[int]:
    consumers = _consumers(graph)
    excluded = set(start_indexes)
    stack = list(start_indexes)
    while stack:
        index = stack.pop()
        for output in graph.node[index].output:
            for next_index in consumers.get(output, []):
                if next_index not in excluded:
                    excluded.add(next_index)
                    stack.append(next_index)
    return excluded


def output_closure_names(model_path: Path) -> list[str]:
    """找出"为产出最终输出而必须保留浮点"的节点：图输出的全部前向依赖。"""
    import onnx

    graph = onnx.load(str(model_path), load_external_data=False).graph
    producers = {output: index for index, node in enumerate(graph.node) for output in node.output}

    needed: set[int] = set()
    stack = [output.name for output in graph.output]
    while stack:
        name = stack.pop()
        index = producers.get(name)
        if index is None or index in needed:
            continue
        needed.add(index)
        stack.extend(graph.node[index].input)
    return [graph.node[i].name for i in sorted(needed)]


#: 走链回溯时的最大跳数：只够跨过解码尾巴（Reshape/Transpose/Concat 数层）。
_WALK_LIMIT = 12


def head_cut_names(model_path: Path) -> list[str]:
    """只把"大小量程混用"的检测头留在浮点，其余照常量化。

    YOLO26 与 YOLOv5u 的图尾都是同一个形状：`Concat(Mul_2(门控框坐标 0–675),
    Sigmoid(类别分数 0–0.44))`。逐张量激活量化只能给一个 scale，这个 Concat 一旦量化，
    类别分数会被压成 0——实测静态量化后检测输出全空。

    做法：从图输出沿解码尾巴回溯到那个混用 Concat，再取它的前向依赖（两个分支各自
    的检测头卷积）作为留浮点的范围。训练分支（one2one 一类不参与前向的算子）仍然量化。
    """
    import onnx

    graph = onnx.load(str(model_path), load_external_data=False).graph
    producers = {output: index for index, node in enumerate(graph.node) for output in node.output}

    mixing: int | None = None
    frontier = [output.name for output in graph.output]
    for _ in range(_WALK_LIMIT):
        if not frontier or mixing is not None:
            break
        next_frontier: list[str] = []
        for name in frontier:
            index = producers.get(name)
            if index is None:
                continue
            node = graph.node[index]
            if node.op_type == "Concat" and any(
                producers.get(source) is not None
                and graph.node[producers[source]].op_type == "Sigmoid"
                for source in node.input
            ):
                mixing = index
                break
            next_frontier.extend(node.input)
        frontier = next_frontier

    if mixing is None:
        return output_closure_names(model_path)
    return [graph.node[i].name for i in sorted(_downstream_closure(graph, {mixing}))]


def postprocess_node_names(model_path: Path) -> list[str]:
    """找出后处理子图（NMS 及其全部下游）的节点名。

    这个模型把 NMS 包含在图内（输出 [1,300,6]），NonMaxSuppression 一类算子一旦做
    激活量化，比较与索引逻辑就会失效——实测静态量化后前 5 个框分数全为 0。
    """
    import onnx

    model = onnx.load(str(model_path), load_external_data=False)
    graph = model.graph
    anchors = [i for i, node in enumerate(graph.node) if node.op_type in POSTPROCESS_ANCHORS]
    if not anchors:
        return []
    return [graph.node[i].name for i in sorted(_downstream_closure(graph, set(anchors)))]


def exclude_node_names(model_path: Path, from_patterns: list[str]) -> list[str]:
    """按 `--exclude-from` 的节点名子串匹配，从匹配节点起排除整段下游子图。"""
    import onnx

    model = onnx.load(str(model_path), load_external_data=False)
    graph = model.graph
    starts = {
        index
        for index, node in enumerate(graph.node)
        if any(pattern in node.name for pattern in from_patterns)
    }
    if not starts:
        return []
    return [graph.node[i].name for i in sorted(_downstream_closure(graph, starts))]


def excluded_node_names(model_path: Path, mode: str, from_patterns: list[str]) -> list[str]:
    """按模式汇总要排除的节点，取并集。"""
    if mode == "all":
        import onnx

        graph = onnx.load(str(model_path), load_external_data=False).graph
        return [node.name for node in graph.node]

    names: set[str] = set()
    if from_patterns:
        names.update(exclude_node_names(model_path, from_patterns))
    if mode in ("head", "postprocess"):
        names.update(head_cut_names(model_path))
    if mode == "postprocess":
        names.update(postprocess_node_names(model_path))
    return sorted(names)


def input_name_of(model_path: Path) -> str:
    import onnxruntime as ort

    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    return session.get_inputs()[0].name


#: QDQ 静态量化会插入带 axis 属性的逐通道 DequantizeLinear，需要 opset >= 13。
_MIN_STATIC_OPSET = 13


def ensure_static_opset(model_path: Path, work_dir: Path, output_stem: str) -> Path:
    """opset < 13 时先升级到 13；否则原样返回。

    YOLOv5u 导出为 opset 12，直接静态量化会得到 onnxruntime 拒绝加载的模型
    （`Error Unrecognized attribute: axis for operator DequantizeLinear`），必须转版本。
    """
    import onnx
    from onnx import version_converter

    model = onnx.load(str(model_path))
    versions = [item.version for item in model.opset_import if item.domain in ("", "ai.onnx")]
    current = max(versions) if versions else 0
    if current >= _MIN_STATIC_OPSET:
        return model_path

    import onnxruntime as ort

    source = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    upgraded = version_converter.convert_version(model, _MIN_STATIC_OPSET)
    try:
        onnx.checker.check_model(upgraded)
    except Exception as error:  # noqa: BLE001 - 需要把失败原因原样报给调用者
        raise SystemExit(f"opset {current} -> {_MIN_STATIC_OPSET} 转换后模型校验失败：{error}") from error

    work_dir.mkdir(parents=True, exist_ok=True)
    target = work_dir / f"{output_stem}_opset{_MIN_STATIC_OPSET}.onnx"
    onnx.save(upgraded, str(target))

    # 转换器可能在数值上改变极小算子，用同一帧输入核对输出是否保持一致。
    probe = np.zeros((1, 3, read_model_size(model_path), read_model_size(model_path)), dtype=np.float32)
    before = np.asarray(
        source.run(None, {source.get_inputs()[0].name: probe})[0], dtype=np.float64
    ).reshape(-1)
    check = ort.InferenceSession(str(target), providers=["CPUExecutionProvider"])
    after = np.asarray(
        check.run(None, {check.get_inputs()[0].name: probe})[0], dtype=np.float64
    ).reshape(-1)
    if before.size != after.size or not np.array_equal(before, after):
        raise SystemExit(
            f"opset {current} -> {_MIN_STATIC_OPSET} 改变了输出（{before.size} vs {after.size} 元素），"
            "拒绝继续量化；请改用 `--mode dynamic` 或重新以 opset >= 13 导出。"
        )
    print(f"opset {current} -> {_MIN_STATIC_OPSET}：{model_path.name} -> {target.name}（输出一致）")
    return target


def quantize(
    model_path: Path,
    mode: str,
    out_dir: Path | None,
    calib_dir: Path,
    calib_count: int,
    exclude_mode: str = "head",
    exclude_from: list[str] | None = None,
    activation: str = "uint8",
    suffix: str = "_int8",
) -> Path:
    from onnxruntime.quantization import QuantFormat, QuantType, quantize_dynamic, quantize_static

    size = read_model_size(model_path)
    target_dir = out_dir if out_dir else model_path.parent
    target_dir.mkdir(parents=True, exist_ok=True)
    output = target_dir / f"{model_path.stem}{suffix}.onnx"

    started = time.time()
    if mode == "dynamic":
        quantize_dynamic(
            model_input=str(model_path),
            model_output=str(output),
            weight_type=QuantType.QInt8,
        )
    else:
        source = ensure_static_opset(model_path, target_dir / "_opset", model_path.stem)
        size = read_model_size(source)
        tensors = load_calibration(calib_dir, size, calib_count)
        reader = FrameCalibrationReader(tensors, input_name_of(source))
        excluded = excluded_node_names(source, exclude_mode, exclude_from or [])
        print(f"量化范围：{exclude_mode}，排除节点 {len(excluded)} 个")
        quantize_static(
            model_input=str(source),
            model_output=str(output),
            calibration_data_reader=reader,
            quant_format=QuantFormat.QDQ,
            activation_type=QuantType.QInt8 if activation == "int8" else QuantType.QUInt8,
            weight_type=QuantType.QInt8,
            per_channel=True,
            nodes_to_exclude=excluded,
        )

    source_mb = model_path.stat().st_size / 1024 / 1024
    target_mb = output.stat().st_size / 1024 / 1024
    print(
        f"{model_path.name} -> {output.name}：{source_mb:.1f} MiB -> {target_mb:.1f} MiB"
        f"（{mode}，用时 {time.time() - started:.1f}s）"
    )
    return output


def verify(model_path: Path, int8_path: Path, frames: int = 5, calib_dir: Path = DEFAULT_CALIB_DIR) -> None:
    import onnxruntime as ort

    size = read_model_size(model_path)
    # 必须用真实画面：随机噪声的激活分布与校准集差很远，量化后的输出会被截断，
    # 用它判断质量会得出"量化坏了"的错误结论。
    frames_data = load_calibration(calib_dir, size, limit=4)
    data = frames_data[0]

    def run(path: Path, threads: int):
        options = ort.SessionOptions()
        options.intra_op_num_threads = threads
        options.inter_op_num_threads = 1
        session = ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])
        name = session.get_inputs()[0].name
        outputs = session.run(None, {name: data})
        # 预热一帧，再取稳态耗时。
        session.run(None, {name: data})
        started = time.perf_counter()
        for _ in range(frames):
            session.run(None, {name: data})
        elapsed = (time.perf_counter() - started) / frames * 1000.0
        return outputs[0].reshape(-1), elapsed

    fp32_out, fp32_ms = run(model_path, 1)
    int8_out, int8_ms = run(int8_path, 1)
    _, fp32_ms4 = run(model_path, 4)
    _, int8_ms4 = run(int8_path, 4)

    length = min(fp32_out.size, int8_out.size)
    a = fp32_out[:length].astype(np.float64)
    b = int8_out[:length].astype(np.float64)
    cosine = float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-9))
    max_diff = float(np.max(np.abs(a - b)))

    print(f"\n校验 {model_path.stem}")
    print(f"  输出元素 {fp32_out.size} vs {int8_out.size}")
    print(f"  余弦相似度 {cosine:.4f}（越接近 1 越好）")
    print(f"  最大绝对差 {max_diff:.4f}")
    print(f"  单线程：FP32 {fp32_ms:.1f} ms/帧，INT8 {int8_ms:.1f} ms/帧，提升 {fp32_ms / int8_ms:.2f}×")
    print(f"  4 线程：FP32 {fp32_ms4:.1f} ms/帧，INT8 {int8_ms4:.1f} ms/帧，提升 {fp32_ms4 / int8_ms4:.2f}×")
    print("  注意：本机是 x86 桌面 CPU，绝对耗时不能代表 Android；只用于确认量化生效与相对提升。")


def main() -> int:
    parser = argparse.ArgumentParser(description="Quantize ONNX models for the inference nodes")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--model", type=Path, help="单个模型路径")
    source.add_argument("--all", action="store_true", help="量化模型目录下的全部模型")
    source.add_argument("--verify", type=Path, help="比较某模型的 FP32 与 INT8 输出")
    parser.add_argument("--mode", choices=["static", "dynamic"], default="static")
    parser.add_argument("--model-dir", type=Path, default=DEFAULT_MODEL_DIR)
    parser.add_argument("--out-dir", type=Path, default=None)
    parser.add_argument("--calib-dir", type=Path, default=DEFAULT_CALIB_DIR)
    parser.add_argument("--calib-count", type=int, default=20)
    parser.add_argument("--only", type=str, default=None, help="批量时按名称筛选，例如 yolo26")
    parser.add_argument(
        "--exclude",
        choices=["head", "postprocess", "all", "none"],
        default="head",
        help="量化范围：head=混用量程的检测头留浮点（默认），postprocess=仅排除 NMS，all/none 用于对照",
    )
    parser.add_argument("--exclude-from", type=str, default=None, help="按节点名子串追加排除起点，逗号分隔")
    parser.add_argument(
        "--activation",
        choices=["uint8", "int8"],
        default="uint8",
        help="静态量化的激活类型。默认 uint8；ORT 的 NNAPI 提供器拒收 uint8 激活，"
             "给硬件加速路径造产物时用 int8",
    )
    parser.add_argument("--suffix", type=str, default="_int8", help="输出文件名后缀（区分不同激活类型）")
    args = parser.parse_args()

    exclude_from = [p.strip() for p in (args.exclude_from or "").split(",") if p.strip()]

    if args.verify:
        int8_path = args.verify.with_name(f"{args.verify.stem}_int8.onnx")
        if not int8_path.is_file():
            raise SystemExit(f"找不到量化模型：{int8_path}")
        verify(args.verify, int8_path, calib_dir=args.calib_dir)
        return 0

    if args.model:
        quantize(args.model, args.mode, args.out_dir, args.calib_dir, args.calib_count, args.exclude, exclude_from,
                 args.activation, args.suffix)
        return 0

    models = sorted(args.model_dir.glob("*.onnx"))
    if args.only:
        models = [m for m in models if args.only in m.name]
    models = [m for m in models if not m.stem.endswith("_int8")]
    if not models:
        raise SystemExit(f"没有可量化的模型：{args.model_dir}")
    for model in models:
        quantize(model, args.mode, args.out_dir, args.calib_dir, args.calib_count, args.exclude, exclude_from,
                 args.activation, args.suffix)
    return 0


if __name__ == "__main__":
    sys.exit(main())
