# WPF 人员检测 smoke 工具

`VisionGuard.WpfSmoke` 用**真实可见窗口**做 WPF 检测端的人员推理验证：它按窗口句柄逐路 `WindowHandle` 采集、跑真实推理，要求每路至少产生 30 帧并至少命中一帧 `person`，同时验证停止一路不影响其他路、单路重配隔离、窗口移动缩放、遮挡、最小化故障隔离与恢复、关闭隔离、子区域裁剪、CPU 多路允许运行且超容量可见提示、DirectML 初始化失败回退，以及运行期推理故障逐路隔离。

它通过 `ProjectReference` 引用 `detector/windows-wpf/VisionGuard.csproj`，因此**构建档位与检测端一致**（默认 `OrtProfile=modern`）。它不替代动态视频、完整报警链、持续运行、其他 GPU 或 UI 目检。

## 直接调用

```powershell
dotnet run --project detector\windows-wpf-smoke\VisionGuard.WpfSmoke.csproj -c Release -- `
  <window-handle-file> <model.onnx> <report.json> [confidence-threshold]
```

- `window-handle-file`：每行一个十进制窗口句柄，顺序即来源顺序；行数就是来源数量（工具只接受 3–4 个命令行参数，来源数量由句柄行数决定）。
- `model.onnx`：例如 `artifacts\v0\yolo26n_320.onnx`（modern 档用 YOLO26，legacy 档用 YOLOv5）。
- `report.json`：默认建议写到 `artifacts/e2e/wpf-window-person-detection.json`，字段含总判定 `passed`、各路 `frames`/`personHitFrames`/`maxPersonConfidence`/`ActiveBackend`/`ActualFps`、各项隔离断言与 `unexpectedRuntimeErrors`。
- `confidence-threshold`：可选，默认 `0.25`。

## 谁提供目标窗口

**当前仓库不含夹具窗口工具。** 目标窗口由外部提供，要求是可被 `PrintWindow` 稳定重绘的顶层窗口（历史实现是 net472 的 DPI-unaware WinForms 窗口逐个显示一张含人图片；该工具 `detector/windows-winforms-smoke/` 已随 WinForms 检测端退役一并删除，替换工具尚未落地）。

`visionguard-e2e` 的 `WpfPersonDetection` 模式负责创建这些窗口并调用本工具，参数与证据边界见该 Skill 与[运维文档](../../docs/codex/60-operations.md)。已知取舍：`PrintWindow` 对纯合成（WPF/Chromium）窗口可能抓到空白帧，所以夹具必须是自绘或 GDI 绘制的窗口。
