# 测量自带模型（不入 Git）

这个目录里的模型**不进版本控制**（单个 640 模型就有几十 MiB，400 MiB 级的仓库没人愿意克隆）。本地保留即可，重新生成方式如下。

## 目录约定

- `bench-models/`：待测模型，**同名成对**才有意义——`<基名>.onnx` 走 ONNX Runtime 档，`<基名>.tflite` 走 LiteRT 档。缺哪个就只跑另一个，报告里会记「缺 .onnx / 缺 .tflite 产物」。
- `bench-frames/`：测量输入画面（JPEG/PNG），当前用 `person-zidane.jpg`。没有画面时退回固定合成输入，报告里也会记一条。

## 复现步骤

```powershell
# 1) 量化（校准集用真实照片，不要用界面截图——夜间截图会让分数被截断）
python scripts/quantize-onnx-models.py --model server/data/models/yolo26n_320.onnx `
    --mode static --exclude head --calib-dir .local/model-quant/calib-photos --calib-count 12 `
    --out-dir detector/android-inference/app/src/main/assets/bench-models

# 2) 需要 LiteRT 档时再转 TFLite（Windows 上 ultralytics 不导出 LiteRT，走 onnx2tf）
python -m onnx2tf -i detector/android-inference/app/src/main/assets/bench-models/yolo26n_320_int8.onnx `
    -o .local/model-quant/tflite/y26n320_int8 -osd -nuo

# 3) 测量画面
Copy-Item artifacts/e2e/person-fixtures/person-zidane.jpg `
    detector/android-inference/app/src/main/assets/bench-frames/
```

## 已知约束

- LiteRT 只吃 `.tflite`；ONNX Runtime 只吃 `.onnx`。
- ONNX **外部数据格式**（`.onnx` + `.onnx.data`）要两个文件一起放，只放 `.onnx` 会加载失败。
- 完整量化（输入输出都是 int8/uint8）的 TFLite **暂时做不出来**：`onnx2tf` 的严格全整数量化在 YOLO 的解码段报 `Unsupported op for strict full integer quantization: ROUND`。现有 `.tflite` 都是 float32 边界、图内带 int8。加速器通常要求完整量化模型，这一条是当前 GPU 路线的现实障碍。
