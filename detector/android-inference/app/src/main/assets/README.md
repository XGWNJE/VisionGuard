# 测量自带模型（不入 Git）

测量用的模型**不进版本控制**（单个 640 模型就有几十 MiB，仓库没人愿意克隆）。本地保留即可，重新生成方式见下。

## 目录约定

- 测量资产放 **`app/src/debug/assets/`**，不是这里。`bench-models/` 与 `bench-frames/` 只服务调试构建的加速测量入口；正式包的模型一律从统一服务按需下载，放 `main` 会让每个正式包白白多背一百多 MiB。
- `bench-models/`：待测模型，**同名成对**才有意义——`<基名>.onnx` 走 ONNX Runtime 档，`<基名>.tflite` 走 LiteRT 档。缺哪个就只跑另一个，报告里会记「缺 .onnx / 缺 .tflite 产物」。
- `bench-frames/`：测量输入画面（JPEG/PNG），当前用 `person-zidane.jpg`（源图是 PNG，扩展名沿用别名，按内容解码）。没有画面时退回合成输入，报告里也会记一条。
- 本 README 留在 `main`：它说明的是约定，不是资产本身。

## 复现步骤

```powershell
# 1) 量化（校准集用真实照片，不要用界面截图——夜间截图会让分数被截断）
python scripts/quantize-onnx-models.py --model server/data/models/yolo26n_320.onnx `
    --mode static --exclude head --calib-dir .local/model-quant/calib-photos --calib-count 12 `
    --out-dir detector/android-inference/app/src/debug/assets/bench-models

# 2) 需要 LiteRT 档时再转 TFLite（Windows 上 ultralytics 不导出 LiteRT，走 onnx2tf）
python -m onnx2tf -i detector/android-inference/app/src/debug/assets/bench-models/yolo26n_320_int8.onnx `
    -o .local/model-quant/tflite/y26n320_int8 -osd -nuo

# 3) GPU 档是截断图（图内无 NMS），另有专用链路：
#    .local/model-quant/build-anchor-tflite.py；产物要按端侧约定的文件名投放，
#    正式链路请求的是 <键>_gpu.tflite，不是本地的 <键>_anchor_float32.tflite。

# 4) 测量画面
Copy-Item artifacts/e2e/person-fixtures/person-zidane.jpg `
    detector/android-inference/app/src/debug/assets/bench-frames/
```

## 已知约束

- LiteRT 只吃 `.tflite`；ONNX Runtime 只吃 `.onnx`。
- ONNX **外部数据格式**（`.onnx` + `.onnx.data`）要两个文件一起放，只放 `.onnx` 会加载失败。
- 完整量化（输入输出都是 int8/uint8）的 TFLite **暂时做不出来**：`onnx2tf` 的严格全整数量化在 YOLO 的解码段报 `Unsupported op for strict full integer quantization: ROUND`。现有 `.tflite` 都是 float32 边界、图内带 int8。
