# Model Assets

这个文件统一记录模型资源、类目映射和目标子集，替代各目录下重复维护的 `ASSETS_README.md` 与 `COCO_CLASSES.md`。

## 当前模型集合

### WinForms

- `yolov5nu_320.onnx`
- `yolov5nu_640.onnx`
- `yolov5su_320.onnx`
- `yolov5su_640.onnx`
- `yolov5mu_320.onnx`
- `yolov5mu_640.onnx`

### WPF

- `yolo26n_320.onnx`
- `yolo26n_640.onnx`
- `yolo26s_320.onnx`
- `yolo26s_640.onnx`
- `yolo26m_320.onnx`
- `yolo26m_640.onnx`

### Android Detector

- 当前设置仓库默认模型名是 `yolo26n`
- Android 端说明里若出现 `yolo26m`，必须先核对打包与复制逻辑再写

## 输出格式

- WinForms YOLOv5：当前说明和解析逻辑使用 `[1,84,N]`
- WPF YOLO26：当前说明和解析逻辑使用 `[1,300,6]`
- Android Detector：当前解析逻辑也按 YOLO26 类标签表工作

## COCO 映射真相源

- WinForms：`detector/windows-winforms/Data/CocoClassMap.cs`
- WPF：`detector/windows-wpf/Data/CocoClassMap.cs`
- Android Receiver：`receiver/android/.../CocoClassMap.kt`
- Android Detector 当前在 `YoloOutputParser.kt` 内维护标签数组

## 统一目标子集

当前多端对齐的 6 类监控目标是：

- `person`
- `bicycle`
- `car`
- `motorcycle`
- `bus`
- `truck`

## 当前默认值

- Windows 两条线旧数据兼容逻辑都以空集合回落到 `person`
- Android Detector `SettingsRepository` 默认 `targets` 为 `person`
- Android Receiver `SettingsRepository` 默认 `targets` 为 `person`

## 维护规则

- 资源说明不再分别放在 `Assets/` 目录维护
- 类目中英文映射不要再手工复制文档，直接引用源码静态表
- 导出脚本、模型文件名、输入尺寸只在源码或项目文件已存在时写入说明

