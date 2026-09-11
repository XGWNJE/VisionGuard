# Windows 检测端与驻留程序

本文只维护 Windows WinForms 检测端、Windows WPF 检测端和 Windows 驻留程序的当前实现事实。路线状态和验收结果见[项目概览](10-project-overview.md)、[产品路线图](15-product-roadmap.md)和[验证报告](90-verification-report.md)。

## 规范名称与职责

| 规范名称 | 路径 | 职责 |
|---|---|---|
| Windows WinForms 检测端（WinForms Visual Detector） | `detector/windows-winforms/` | .NET Framework 4.7.2 / WinForms；维护 Win7+ 兼容视觉检测 |
| Windows WPF 检测端（WPF Visual Detector） | `detector/windows-wpf/` | .NET 9 / WPF / MVVM；提供现代 Windows 视觉检测和最多四路窗口来源 |
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
- `MonitorService` 负责窗口/区域捕获、推理、告警和 UI 更新；多来源协调器支持最多四个独立配置、ONNX Session、定时器和冷却状态，不再提供产品图片直读来源。
- 主窗口移除左侧导航、页内重复品牌块、信号区说明栏和全局底部信息栏，中央固定四路 2×2 监控矩阵；右侧控制台统一承载“当前信号 / 运行环境 / 连接”。控制台移除重复页标题、操作说明和嵌套卡片，主要按钮、单行输入框、下拉框与多选入口统一为 40px 高，次要清除动作允许使用 28px 紧凑高度。当前来源名称与状态合并为单行，点击名称原位编辑，回车或失焦只自动保存名称。“当前信号”其余设置按“采集目标 → 识别设置 → 检测参数 → 区域排除 → 保存”这一实际作业顺序排列；采集目标的清除动作进入区块标题，两个选择动作并排。运行环境的后端与模型分别采用“短标签 + 控件”单行布局；连接页的状态、设备名称和版本操作也各占一个操作行。逐路阈值、类别、频率和冷却只在当前信号中配置，其中检测类别使用基于 COCO 80 类中英文映射的下拉多选菜单。每路画面内独立显示更新时间、推理耗时、最后报警与实际后端，共享页只管理推理后端与模型资源，避免重复职责。
- 运行环境中的模型选择显示下载状态并提供下载；模型缓存为 `%APPDATA%\VisionGuard\models\`，旧 `Assets/` 模型会迁移。
- WPF Release 输出统一为 `bin\x64\`；导航 PNG 以内嵌 Resource 提供，模型和 `Assets/` 不随发行包分发。
- WPF 采集目标使用统一物理像素下限：窗口、屏幕选区和窗口子区域的宽度与高度必须分别严格大于 100 像素；屏幕选区拖拽提示与提交校验共用同一套 DIP 到实际采集像素映射，高 DPI 下界面显示的尺寸就是最终校验尺寸。窗口枚举直接过滤任一边不达标的候选，确认、启动和实际截图层继续做防御式校验。遮罩编辑窗口最小为 520×360 逻辑像素，避免小来源导致工具栏按钮显示不完整。
- WPF 四窗口人员 smoke 已通过独立 `WindowHandle` 捕获、逐路 `person`、每路 3 FPS、停止/重配隔离、CPU 多路拒绝与 DirectML 回退断言；动态视频、其他 GPU、持续运行、报警链和 UI 视觉仍需单独验收。

## Windows 驻留程序当前实现

- 使用独立 `windows-resident` WS 身份与 Server 通信；只接受 `open-wpf`、`open-winforms`、`close-wpf`、`close-winforms` 四个固定生命周期命令。
- 与两个主程序通过当前用户会话事件完成启动握手和正常退出请求；远程打开只启动主程序，不自动开始监控。
- 共用当前用户会话命名互斥体，阻止手动启动和远程启动产生第二实例。
- 登录启动仅由 `--enable-startup <config>` / `--disable-startup` 显式切换；进程级握手可自动验证，登录重启、崩溃恢复、网络中断和完整远控 WSS 链路仍待补。

当前实现基于 .NET 9，仅覆盖受支持的现代 Windows；Windows 7 兼容尚未实现。本项目计划要求驻留程序在后续正式交付前兼容 Windows 7 SP1 x64，并单独验收安装/启动、WSS/TLS、进程握手、远程开关、休眠/网络恢复和退出清理。此要求现在只进入路线与验收门槛，不改变当前代码。

## 不应夸大的结论

- Release 编译通过不等于 Windows 功能完整交付。
- WPF 四个静态图片窗口推理通过不等于动态业务视频、报警截图、Server 中继或 UI 目检通过。
- DirectML 会话成功不等于所有节点都在 GPU 执行，也不等于其他 GPU/640 输入已验证。
- 驻留进程存在不等于远程控制全链路已验收；具体证据以验证报告为准。
