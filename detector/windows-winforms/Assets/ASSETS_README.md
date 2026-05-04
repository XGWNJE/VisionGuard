# Assets 目录说明

此目录存放运行时依赖的二进制资源文件。

## yolov8n_320.onnx（默认）

- **模型**：YOLOv8n ultralytics 版，COCO 80类
- **用途**：目标检测，轻量高速
- **输入形状**：`[1, 3, 320, 320]` float32，CHW，RGB，归一化到 [0,1]
- **输出形状**：`[1, 84, 2100]`（原始输出，未内置 NMS）
- **Opset**：16（兼容 ONNX Runtime 1.11.1 / Windows 7）
- **文件大小**：约 12.7 MB

## yolov8s_320.onnx（可选）

- **模型**：YOLOv8s ultralytics 版，COCO 80类
- **用途**：目标检测，精度更高
- **输入形状**：`[1, 3, 320, 320]` float32，CHW，RGB，归一化到 [0,1]
- **输出形状**：`[1, 84, 2100]`（原始输出，未内置 NMS）
- **Opset**：16
- **文件大小**：约 42.7 MB

> 输出格式：84 = 4 (bbox: cx,cy,w,h 中心格式) + 80 (COCO 类别 logits，需 sigmoid)。
> 坐标基于网格相对位置，需解码为像素坐标。NMS 由代码手动实现。

### 重新导出

```bash
pip install ultralytics onnxslim
python -c "
from ultralytics import YOLO
YOLO('yolov8n.pt').export(format='onnx', imgsz=320, opset=16, simplify=True)
YOLO('yolov8s.pt').export(format='onnx', imgsz=320, opset=16, simplify=True)
"
```

导出后用 [Netron](https://netron.app) 验证 Input/Output shape 正确。

### Windows 7 兼容性

- ONNX Runtime 1.11.1（官方最后支持 Win7 的版本）
- ONNX opset 16（1.11.1 支持的最高版本）
- .NET Framework 4.7.2（Win7 支持的最高版本）
