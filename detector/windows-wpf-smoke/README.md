# WPF 人员检测 smoke 工具

`VisionGuard.WpfSmoke` 用**真实可见窗口**做 WPF 检测端的人员推理验证：它按窗口句柄逐路 `WindowHandle` 采集、跑真实推理，要求每路至少产生 30 帧并至少命中一帧 `person`，同时验证停止一路不影响其他路、单路重配隔离、窗口移动缩放、遮挡、最小化故障隔离与恢复、关闭隔离、子区域裁剪、CPU 多路允许运行、DirectML 初始化失败回退，以及运行期推理故障逐路隔离。性能不足的可见提示改由 `PerformanceWatchdog` 按实测帧率判断（容量基线已于 2026-09-20 移除），其判定口径由 `-Mode PerformanceWatchdog` 的纯计算契约覆盖。

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

`visionguard-e2e` 脚本中的 `Start-WpfFixtureWindows` 用 WinForms 创建可见图片窗口，`WpfPersonDetection` 模式调用本工具并在结束后释放窗口。它提供静态图片推理与隔离证据，不能满足项目规则要求的动态视频窗口 smoke，也不能替代真实 WPF 主程序验收。

运行入口及限制见[运维文档](../../docs/codex/60-operations.md)，验收证据见[验证报告](../../docs/codex/90-verification-report.md)。
