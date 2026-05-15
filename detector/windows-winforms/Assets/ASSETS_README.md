# Assets 目录说明

此目录存放运行时依赖的 YOLOv5 ONNX 模型文件。

## 模型列表

| 模型文件 | 输入 | 大小 | 速度 | 精度 |
|----------|------|------|------|------|
| yolov5nu_320.onnx | 320×320 | ~10 MB | 极快 | 低 |
| yolov5nu_640.onnx | 640×640 | ~10 MB | 快 | 中 |
| yolov5su_320.onnx | 320×320 | ~35 MB | 快 | 中 |
| yolov5su_640.onnx | 640×640 | ~35 MB | 中 | 较高 |
| yolov5mu_320.onnx | 320×320 | ~96 MB | 中 | 较高 |
| yolov5mu_640.onnx | 640×640 | ~96 MB | 较慢 | 高 |

> 输入形状：`[1, 3, H, W]` float32，CHW，RGB，归一化到 [0,1]。
> 输出形状：`[1, 84, N]` — 84 = 4 bbox + 80 COCO 类别。
> N 随输入尺寸变化：320→2100，640→8400。
> Opset 12（兼容 ONNX Runtime 1.1.0 / Windows 7）。

## 模型选择建议

- 实时监控、低配机器 → **yolov5nu_320** 或 **yolov5su_320**
- 均衡精度与速度 → **yolov5mu_320** 或 **yolov5mu_640**
- 高精度场景、小目标检测 → **yolov5mu_640**

## 重新导出

源模型位于 `D:\ObjectCode\YOLO\models\`，使用 ultralytics 导出：

```bash
pip install ultralytics
python -c "
from ultralytics import YOLO
# 示例：导出 yolov5nu 320
YOLO('yolov5nu.pt').export(format='onnx', imgsz=320, opset=16, simplify=True)
"
```

导出后复制 .onnx 到本 Assets 目录，程序启动时自动识别。

### Windows 7 兼容性

- ONNX Runtime 1.11.1（官方最后支持 Win7 的版本）
- ONNX opset 16（1.11.1 支持的最高版本）
- .NET Framework 4.7.2（Win7 支持的最高版本）
