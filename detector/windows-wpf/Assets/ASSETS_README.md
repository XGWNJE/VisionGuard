# Assets 目录说明

此目录存放运行时依赖的二进制资源文件。

## YOLO26 ONNX 模型（6 档）

| 文件名 | 大小 | 输入 | 说明 |
|--------|------|------|------|
| `yolo26n_320.onnx` | 9.4MB | 320×320 | nano 轻量高速 |
| `yolo26n_640.onnx` | 9.5MB | 640×640 | nano 标准精度 |
| `yolo26s_320.onnx` | 36.4MB | 320×320 | small 均衡 |
| `yolo26s_640.onnx` | 36.5MB | 640×640 | small 标准精度 |
| `yolo26m_320.onnx` | 78.0MB | 320×320 | medium 高精度 |
| `yolo26m_640.onnx` | 78.2MB | 640×640 | medium 最高精度 |

- **模型**：YOLO26 ultralytics 官方权重
- **输出形状**：`[1, 300, 6]`（原生内置 NMS，6 = [cx, cy, w, h, confidence, class_id]）
- **Opset**：20
- **输入**：float32，CHW，RGB，归一化到 [0,1]

> YOLO26 与 YOLOv8 是不同的模型系列。YOLO26 原生输出 [1,300,6]，无需 `nms=True`。

### 重新导出

```bash
pip install ultralytics onnxslim
python -c "
from ultralytics import YOLO
for m in ['yolo26n','yolo26s','yolo26m']:
    for sz in [320, 640]:
        YOLO(f'{m}.pt').export(format='onnx', imgsz=sz, opset=20, simplify=True)
"
```

导出后用 [Netron](https://netron.app) 验证 Input/Output shape 正确。
