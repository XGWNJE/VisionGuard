# WPF 四窗口人员检测 smoke 工具

这个项目验证四个真实可见浏览器窗口经 `WindowHandle` 捕获、独立推理与运行隔离的链路。输入目录必须恰好包含四张 `jpg/jpeg/png/bmp` 人员图片，四路都必须至少有一帧 `person` 命中，且实际处理速率不得低于 2.5 FPS。

从仓库根目录运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\test-wpf-person-detection.ps1
```

也可以指定自己的四张图片与模型：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\test-wpf-person-detection.ps1 `
  -FixtureDirectory .\path\to\four-person-images `
  -ModelPath .\artifacts\v0\yolo26n_320.onnx
```

默认报告写入 `artifacts/e2e/wpf-four-window-person-detection.json`，包含每路帧数、`personHitFrames`、最高置信度、实际后端与 FPS，并验证停止一路不影响其他路、单路重配隔离、CPU 并行拒绝和 DirectML 失败回退。阈值默认是 `0.25`，可通过 `-ConfidenceThreshold` 调整。
