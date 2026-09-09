# WPF 人员检测 smoke 工具

这个项目用于验证 WPF 多来源图片捕获与推理链，不代表真实窗口采集验收。它要求输入目录中恰好有三张 `jpg/jpeg/png/bmp` 图片，并且三路都必须至少有一帧 `person` 检测命中。

从仓库根目录运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\test-wpf-person-detection.ps1 -PrepareFixtures
```

`-PrepareFixtures` 会下载三张公开样例图到 `artifacts/e2e/person-fixtures-valid/`。也可以不加该参数，改用自己的三张图片：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\test-wpf-person-detection.ps1 `
  -FixtureDirectory .\path\to\three-person-images `
  -ModelPath .\artifacts\v0\yolo26n_320.onnx
```

报告写入 `artifacts/e2e/wpf-person-detection.json`，包含每路帧数、`personHitFrames`、最高置信度、实际后端和隔离测试结果。阈值默认是 `0.25`，可通过 `-ConfidenceThreshold` 调整。
