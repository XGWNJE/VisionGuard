# Assets 目录说明

此目录存放运行时依赖的二进制资源文件。

## yolov5n.onnx（必需）

- **用途**：目标检测模型（COCO 80类，本项目只使用 class=0 person）
- **输入形状**：[1, 3, 320, 320]，float32，CHW，RGB，归一化到 [0,1]
- **输出形状**：[1, 2100, 85]
- **文件大小**：约 3.8~4.2 MB

### 导出方式（在任意有 Python 的机器上执行）

```bash
pip install ultralytics onnxsim
python -c "from ultralytics import YOLO; YOLO('yolov5n.pt').export(format='onnx', imgsz=320, opset=12, simplify=True)"
```

导出完成后将 `yolov5n.onnx` 放入本目录，然后在 Visual Studio 中确认：
- Build Action: `Content`
- Copy to Output Directory: `Copy if newer`

### 验证模型

用 [Netron](https://netron.app) 打开 onnx 文件，确认：
- Input: `images` [1, 3, 320, 320] float32
- Output: `output0` [1, 2100, 85] float32
- Opset: 12
