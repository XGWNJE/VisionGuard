"""
Quantize exported ONNX models to INT8 for the inference nodes.

静态量化（QDQ）会把卷积也转成整数，是移动端真正能提速的一档；动态量化只量化权重，
对以卷积为主的 YOLO 提速有限，这里保留它作为不需要校准集的退路。

Usage:
  # 静态量化（需要校准画面；默认用 .local 下的真机截图）
  python scripts/quantize-onnx-models.py --model server/data/models/yolo26m_640.onnx --mode static

  # 动态量化（无校准集）
  python scripts/quantize-onnx-models.py --model server/data/models/yolo26m_640.onnx --mode dynamic

  # 批量
  python scripts/quantize-onnx-models.py --all --mode static --only yolo26

  # 校验：比较 FP32 与 INT8 的输出差异与本机耗时
  python scripts/quantize-onnx-models.py --verify server/data/models/yolo26m_640.onnx

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


def input_name_of(model_path: Path) -> str:
    import onnxruntime as ort

    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    return session.get_inputs()[0].name


def quantize(model_path: Path, mode: str, out_dir: Path | None, calib_dir: Path, calib_count: int) -> Path:
    from onnxruntime.quantization import QuantFormat, QuantType, quantize_dynamic, quantize_static

    size = read_model_size(model_path)
    target_dir = out_dir if out_dir else model_path.parent
    target_dir.mkdir(parents=True, exist_ok=True)
    output = target_dir / f"{model_path.stem}_int8.onnx"

    started = time.time()
    if mode == "dynamic":
        quantize_dynamic(
            model_input=str(model_path),
            model_output=str(output),
            weight_type=QuantType.QInt8,
        )
    else:
        tensors = load_calibration(calib_dir, size, calib_count)
        reader = FrameCalibrationReader(tensors, input_name_of(model_path))
        quantize_static(
            model_input=str(model_path),
            model_output=str(output),
            calibration_data_reader=reader,
            quant_format=QuantFormat.QDQ,
            activation_type=QuantType.QUInt8,
            weight_type=QuantType.QInt8,
            per_channel=True,
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

    def run(path: Path):
        session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
        name = session.get_inputs()[0].name
        outputs = session.run(None, {name: data})
        # 预热一帧，再取稳态耗时。
        session.run(None, {name: data})
        started = time.perf_counter()
        for _ in range(frames):
            session.run(None, {name: data})
        elapsed = (time.perf_counter() - started) / frames * 1000.0
        return outputs[0].reshape(-1), elapsed

    fp32_out, fp32_ms = run(model_path)
    int8_out, int8_ms = run(int8_path)

    length = min(fp32_out.size, int8_out.size)
    a = fp32_out[:length].astype(np.float64)
    b = int8_out[:length].astype(np.float64)
    cosine = float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-9))
    max_diff = float(np.max(np.abs(a - b)))

    print(f"\n校验 {model_path.stem}")
    print(f"  输出元素 {fp32_out.size} vs {int8_out.size}")
    print(f"  余弦相似度 {cosine:.4f}（越接近 1 越好）")
    print(f"  最大绝对差 {max_diff:.4f}")
    print(f"  FP32 {fp32_ms:.1f} ms/帧，INT8 {int8_ms:.1f} ms/帧，提升 {fp32_ms / int8_ms:.2f}×")
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
    args = parser.parse_args()

    if args.verify:
        int8_path = args.verify.with_name(f"{args.verify.stem}_int8.onnx")
        if not int8_path.is_file():
            raise SystemExit(f"找不到量化模型：{int8_path}")
        verify(args.verify, int8_path, calib_dir=args.calib_dir)
        return 0

    if args.model:
        quantize(args.model, args.mode, args.out_dir, args.calib_dir, args.calib_count)
        return 0

    models = sorted(args.model_dir.glob("*.onnx"))
    if args.only:
        models = [m for m in models if args.only in m.name]
    models = [m for m in models if not m.stem.endswith("_int8")]
    if not models:
        raise SystemExit(f"没有可量化的模型：{args.model_dir}")
    for model in models:
        quantize(model, args.mode, args.out_dir, args.calib_dir, args.calib_count)
    return 0


if __name__ == "__main__":
    sys.exit(main())
