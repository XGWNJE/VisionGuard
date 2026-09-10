# Windows 检测端与驻留程序

本文只维护 Windows WinForms 检测端、Windows WPF 检测端和 Windows 驻留程序的当前实现事实。路线状态和验收结果见[项目概览](10-project-overview.md)、[产品路线图](15-product-roadmap.md)和[验证报告](90-verification-report.md)。

## 规范名称与职责

| 规范名称 | 路径 | 职责 |
|---|---|---|
| Windows WinForms 检测端（WinForms Visual Detector） | `detector/windows-winforms/` | .NET Framework 4.7.2 / WinForms；维护 Win7+ 兼容视觉检测 |
| Windows WPF 检测端（WPF Visual Detector） | `detector/windows-wpf/` | .NET 9 / WPF / MVVM；提供现代 Windows 视觉检测和最多三路图片来源原型 |
| Windows 驻留程序（Windows Resident） | `detector/windows-resident/` | .NET 9 当前用户后台进程；承接主程序生命周期远控，不承接推理；当前仅支持现代 Windows，Win7 兼容列为后续交付硬门槛 |

Windows 两个检测端使用 WS 角色 `windows`；驻留程序使用独立的 `windows-resident` 角色。驻留程序与检测端按同一 `deviceId` 聚合，但连接和状态不能混为一个组件。

## 共通检测链路

`Capture -> MaskApply -> Preprocess -> ONNX Inference -> Parse -> AlertDecision -> Push`

两种检测端均支持窗口/屏幕捕获、遮罩、预处理、ONNX 推理、告警冷却和 Server 推送；遮罩使用相对坐标 `[0,1]`，推理前涂黑并同时影响推理结果和告警截图。

## WinForms 当前实现

- .NET Framework 4.7.2、事件驱动 `Form1` partial class、YOLOv5 输出解析和 `websocket-sharp`。
- 兼容边界为 Win7 SP1 x64；入口强制显式 TLS 1.2。`LegacyTlsTunnelService` 只在用户显式启用时使用，stunnel 不随发行输出。
- 本地设置兼容 `settings.ini`；模型从 Server 按需下载到 `%APPDATA%\VisionGuard\models\`，启动时迁移旧 `Assets/` 模型。
- WinForms Release 输出统一为 `bin\Release\`；模型、stunnel 和未引用图片不进入发行包。

## WPF 当前实现

- .NET 9、WPF + MVVM、YOLO26 输出格式 `[1,300,6]`、DirectML 默认后端并支持显式 CPU 回退。
- `MonitorService` 负责捕获、推理、告警和 UI 更新；多来源协调器支持最多三个独立配置、ONNX Session、定时器和冷却状态。
- 设置页模型选择处显示下载状态并提供下载；模型缓存为 `%APPDATA%\VisionGuard\models\`，旧 `Assets/` 模型会迁移。
- WPF Release 输出统一为 `bin\x64\`；导航 PNG 以内嵌 Resource 提供，模型和 `Assets/` 不随发行包分发。
- WPF 人员图片 smoke 已通过每路 `person` 命中断言；真实窗口采集、其他 GPU、持续运行和 UI 视觉仍需单独验收。

## Windows 驻留程序当前实现

- 使用独立 `windows-resident` WS 身份与 Server 通信；只接受 `open-wpf`、`open-winforms`、`close-wpf`、`close-winforms` 四个固定生命周期命令。
- 与两个主程序通过当前用户会话事件完成启动握手和正常退出请求；远程打开只启动主程序，不自动开始监控。
- 共用当前用户会话命名互斥体，阻止手动启动和远程启动产生第二实例。
- 登录启动仅由 `--enable-startup <config>` / `--disable-startup` 显式切换；进程级握手可自动验证，登录重启、崩溃恢复、网络中断和完整远控 WSS 链路仍待补。

当前实现基于 .NET 9，仅覆盖受支持的现代 Windows；Windows 7 兼容尚未实现。本项目计划要求驻留程序在后续正式交付前兼容 Windows 7 SP1 x64，并单独验收安装/启动、WSS/TLS、进程握手、远程开关、休眠/网络恢复和退出清理。此要求现在只进入路线与验收门槛，不改变当前代码。

## 不应夸大的结论

- Release 编译通过不等于 Windows 功能完整交付。
- WPF ImageFile 三路推理通过不等于真实窗口采集、报警截图、Server 中继或 UI 目检通过。
- DirectML 会话成功不等于所有节点都在 GPU 执行，也不等于其他 GPU/640 输入已验证。
- 驻留进程存在不等于远程控制全链路已验收；具体证据以验证报告为准。
