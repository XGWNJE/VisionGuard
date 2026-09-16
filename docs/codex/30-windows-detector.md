# Windows 检测端与驻留程序

本文只维护 Windows WinForms 检测端、Windows WPF 检测端和 Windows 驻留程序的当前实现事实。路线状态和验收结果见[项目概览](10-project-overview.md)、[产品路线图](15-product-roadmap.md)和[验证报告](90-verification-report.md)。

## 规范名称与职责

| 规范名称 | 路径 | 职责 |
|---|---|---|
| Windows WinForms 检测端（WinForms Visual Detector） | `detector/windows-winforms/` | .NET Framework 4.7.2 / WinForms；维护 Win7+ 兼容视觉检测，多来源与逐来源配置对齐检测端功能基线 |
| Windows WPF 检测端（WPF Visual Detector） | `detector/windows-wpf/` | .NET 9 / WPF / MVVM；提供现代 Windows 视觉检测，来源数量按 Server 协商上限生成 |
| Windows 驻留程序（Windows Resident） | `detector/windows-resident/` | .NET Framework 4.7.2 x64 当前用户后台进程；承接主程序生命周期远控，不承接推理；框架与 API 已对齐 Win7 SP1 x64 |

Windows 两个检测端使用 WS 角色 `windows`；驻留程序使用独立的 `windows-resident` 角色。驻留程序与检测端按同一 `deviceId` 聚合，但连接和状态不能混为一个组件。

## 共通检测链路

`Capture -> MaskApply -> Preprocess -> ONNX Inference -> Parse -> AlertDecision -> Push`

两种检测端均支持窗口/屏幕捕获、遮罩、预处理、ONNX 推理、告警冷却和 Server 推送；遮罩使用相对坐标 `[0,1]`，推理前涂黑并同时影响推理结果和告警截图。

控制命令方面，两种检测端语义一致：带 `targetSourceId` 的命令只作用于该来源；不带来源标识的设备级 `pause`/`resume` 作用于全部来源，等价于界面上的“启动已配置 / 全部停止”，不会静默只操作第一路。运行中的来源拒绝改配置，必须先停止该来源。

状态文案两端统一为**检测中**（逐来源与设备级一致）；没有任何完整配置的来源时显示**未就绪**，此时启动入口不可用。所有运行中参数（采集目标、阈值、类别、频率、冷却、模型）都只能冷改动：运行中这些控件不可用，切换模型也会被明确拒绝。

来源数量两端行为一致：默认 1 个来源，用户在 Server 下发上限内手动新增/删除（删除保底保留 1 个，运行中的来源必须先停止）；上限只是上界，不再等于槽位数量。两端都只在服务端**真的下发了** `maxSources` 时才收窄上限：服务端未声明上限时保持本地已放开的范围（最大 16 路），不静默压回默认 4 路。两端统一使用**来源**这一说法：界面文案与标签页为“当前来源”，设置键前缀为 `Source.{i}.`（早期 `Signal.*` 键做一次性迁移并保留作备份），类型名为 `SourceViewModel`。`sourceId` 取值保持不变（`default`、`signal-N`）——它是接收端历史与静音偏好的归属键，不随显示名改动。

## WinForms 当前实现

- .NET Framework 4.7.2、事件驱动 `Form1` partial class、YOLOv5 输出解析和 `websocket-sharp`。
- 多来源主体已对齐检测端功能基线：每个来源由独立的 `ISourceMonitorRuntime` 承载（独立 `AlertService`、`MonitorService`、FPS 统计、冷却与错误状态），槽位数量按 Server 在 `auth-result`/`heartbeat-ack` 下发的 `maxSources` 调整，来源身份与配置以 `WinForms.Signal.{i}.*` 持久化，改名不改身份。心跳携带逐来源 `sources` 数组并声明 `source-control`；逐来源启停与参数调整按 `targetSourceId` 路由，回执原样回写该字段，目标来源不存在时先回明确失败而非静默落到其他来源。
- 设备级命令（无 `targetSourceId`）作用于全部来源，等价于界面上的“启动已配置 / 全部停止”；不会静默只操作第一路。运行中的来源拒绝改配置，必须先停止该来源。
- 推理后端固定 CPU，不规划硬件加速；本机容量档位由 `WinForms.CpuCapacity` 配置，只用于性能提示，不阻止启动、不自动减路或降帧。
- WPF 界面由 .NET 9/WPF 的 PerMonitorV2 支持跟随当前显示器缩放；屏幕区域在选择时把 WPF DIP 坐标换算为物理像素。窗口捕获固定使用目标窗口自身的客户区重绘平面：先按 `GetClientRect` 建候选位图并铺哨兵色，`PrintWindow` 使用 `PW_CLIENTONLY | PW_RENDERFULLCONTENT`，随后按实际被目标窗口覆盖的范围规范化；窗口子区域持久化为规范化帧内像素。该几何不切换调用方或目标进程的 DPI 感知，避免把系统虚拟化后的候选尺寸与未拉伸的窗口自绘内容混用。
- 预览区为按来源数量自适应的网格，每格显示来源名、状态、实际 FPS、推理耗时、最后报警、实际后端以及错误/提示行，并在卡片标题栏提供“新增来源 / 删除当前来源”图标以及逐路启动、停止；WPF 不再重复展示顶部整机摘要或本机全局启停，服务器连接状态只在“连接”页表达。右侧标签页为“当前来源 / 运行环境 / 连接”，逐来源检测参数与监控目标在“当前来源”，容量与模型在“运行环境”。WinForms 保持原生控件和 Win7 兼容，但采用与 WPF 相同的左侧监控矩阵、右侧检查器信息架构；右侧使用分区标题和等宽双列操作，字段高 32px、普通按钮高 36px、主要启动按钮高 40px，避免各页控件规格漂移。
- 采集目标沿用窗口与屏幕区域；窗口、屏幕选区与窗口子区域的宽高必须分别严格大于 100 像素，窗口枚举阶段直接过滤不达标候选，确认、启动与实际截图层继续做防御式校验。窗口帧只包含客户区，不含标题栏和边框；旧版按外框帧保存的子区域需要重新选择，越界值明确失败。`PrintWindow` 返回全黑画面时仍抛出 `CaptureBlackFrameException`，作为对应来源的可见故障上报。
- 兼容边界为 Win7 SP1 x64；入口强制显式 TLS 1.2。`LegacyTlsTunnelService` 只在用户显式启用时使用，stunnel 不随发行输出。
- 本地设置兼容 `settings.ini`；模型从 Server 按需下载到 `%APPDATA%\VisionGuard\models\`，启动时迁移旧 `Assets/` 模型。
- WinForms Release 输出统一为 `bin\Release\`；模型、stunnel 和未引用图片不进入发行包。

## WPF 当前实现

- .NET 9、WPF + MVVM、YOLO26 输出格式 `[1,300,6]`、DirectML 默认后端并支持显式 CPU 回退。
- `MonitorService` 负责窗口/区域捕获、推理、告警和 UI 更新；多来源协调器按 Server 协商上限维护来源槽位，每个来源拥有独立配置、ONNX Session、定时器和冷却状态，不再提供产品图片直读来源。
- 主窗口移除左侧导航、页内重复品牌块、来源区说明栏和全局底部信息栏，中央两列、行数随来源数量增长的监控矩阵；右侧控制台统一承载“当前来源 / 运行环境 / 连接”。控制台移除重复页标题、操作说明和嵌套卡片，主要按钮、单行输入框、下拉框与多选入口统一为 40px 高，次要清除动作允许使用 28px 紧凑高度。当前来源名称与状态合并为单行，点击名称原位编辑，回车或失焦只自动保存名称。“当前来源”其余设置按“采集目标 → 识别设置 → 检测参数 → 区域排除 → 保存”这一实际作业顺序排列；采集目标的清除动作进入区块标题，两个选择动作并排。运行环境的后端与模型分别采用“短标签 + 控件”单行布局；连接页的状态、设备名称和版本操作也各占一个操作行。逐路阈值、类别、频率和冷却只在当前来源中配置，其中检测类别使用基于 COCO 80 类中英文映射的下拉多选菜单。每路画面内独立显示更新时间、推理耗时、最后报警与实际后端，共享页只管理推理后端与模型资源，避免重复职责。
- 运行环境中的模型选择显示下载状态并提供下载；模型缓存为 `%APPDATA%\VisionGuard\models\`，旧 `Assets/` 模型会迁移。
- WPF Release 输出统一为 `bin\x64\`；导航 PNG 以内嵌 Resource 提供，模型和 `Assets/` 不随发行包分发。
- WPF 采集目标使用统一像素下限：窗口客户区、屏幕选区和窗口子区域的宽度与高度必须分别严格大于 100 像素；屏幕选区拖拽提示与提交校验共用 DIP 到物理像素映射，窗口子区域则直接使用 `PrintWindow` 客户区帧内像素。窗口枚举直接过滤任一边不达标的候选，确认、启动和实际截图层继续做防御式校验。遮罩仍是帧内 `[0,1]` 相对坐标，不受客户区像素尺寸变化影响。
- WPF 不维护启动时 DPI 基线，也不会因缩放变化停止来源或要求重启；PerMonitorV2 负责窗口布局与输入坐标更新，采集帧则按每次捕获的实际重绘范围自洽。常见缩放比例共用同一路径，没有按 100%/150%/200% 分支。
- 新选择的窗口会持久化标题、窗口类名和进程名；恢复时优先精确匹配，标题变化后只有稳定身份唯一时才自动重绑，同名或同身份多候选会保持停止并要求重新选择。旧配置继续使用唯一标题匹配。
- `PrintWindow` 返回全黑画面时会产生“疑似黑屏”采集故障并显示在对应来源，不再只写调试日志。运行期故障（捕获、黑屏、推理、处理）全部保留在发生故障的来源并显示原因，当前不存在会触发全局停机的故障类型，也没有对应的策略位：故障种类 `MonitorFailureKind` 只用于逐来源上报（`MonitorFailurePolicy` 这个恒为 `false` 的占位策略已删除）。FPS 只统计成功帧。
- 多路 CPU 允许运行：容量基线可配置，超出基线时在对应来源显示“允许继续运行，请关注实际帧率”的提示，不拒绝启动、不自动减路或降帧，性能不足与故障分开表达。
- WPF 与 WinForms 报警先写入各自按 `channel` 分区的 `%LocalAppData%\VisionGuard\*alert-outbox-<channel>.json`，断网或进程重启后重发；只有 Server 返回持久化 `alert-ack` 才移除，随后异步发送截图。队列损坏时原文件会隔离为带时间戳的 `.corrupt-*` 并明确记录，而非静默清空。两端支持用 `VISIONGUARD_SERVER_URL` 与 `VISIONGUARD_CHANNEL` 接入独立测试实例，跨通道积压不会互相重放。
- Windows 人员 smoke 按当前配置的来源数量取证（`-SourceCount`，范围 2–16，四路为回归基线）：WPF 打开独立可见浏览器窗口逐个 `WindowHandle` 捕获，WinForms 打开独立 net472 窗口并复用生产协调器与 YOLOv5 CPU 管线。已通过逐路 `person`、停止/重配隔离、移动缩放、遮挡、最小化故障隔离及恢复、关闭隔离、CPU 超容量允许运行并提示、DirectML 初始化回退和运行期推理故障逐路隔离；动态视频、其他 GPU、显卡驱动故障、持续运行、报警链和 UI 视觉仍需单独验收。WPF 夹具图片数量不得少于来源数量，默认夹具目录不足时脚本直接报错而不是降低来源数量。

## Windows 驻留程序当前实现

- 使用独立 `windows-resident` WS 身份与 Server 通信；只接受 `open-detector`、`close-detector` 两个固定生命周期命令（V10 决策 28：Windows 只剩一个检测端）。
- **启动方向已被 V10 反转**：驻留不再负责打开检测端，而是由检测端在启动时用 `UseShellExecute = true` 拉起（传入自身写出的 `%LOCALAPPDATA%\VisionGuard\resident-config.json`），使驻留脱离父进程生命周期；驻留自行登记登录自启。检测端正常退出或崩溃后驻留继续运行，因此仍能接受 `open-detector` 远程重新打开。
- 驻留程序目标框架为 .NET Framework 4.7.2 x64，JSON 使用 `JavaScriptSerializer`，WebSocket 使用与检测端同一份自研 `Net/MinimalWebSocketClient.cs`（源码链接复用，显式 TLS 1.2）；Release 目录必须同时包含主 EXE 与配置文件。**不再依赖 `websocket-sharp`**——该库以 `SslProtocols.Default` 协商 TLS，在 Windows 7 上退化为 TLS 1.0 会被服务端拒绝（检测端已在 Win7 实测确认该根因）。
- 驻留自身使用当前用户会话命名互斥体防止重复启动；连接认证成功后才发送组件心跳，远控完成回执携带 `requestId`、`phase=completed` 与目标设备，供 Server 严格关联请求。
- Win7 支持基线为 Windows 7 SP1 x64 + .NET Framework 4.7.2 + TLS 1.2 系统更新；代码与产物满足该基线不替代 Win7 实机/WSS 证书链验收。
- 与检测端通过当前用户会话事件完成启动握手和正常退出请求（`Local\VisionGuard.Detector.Running` / `.Shutdown`）；远程打开只启动检测端，不自动开始监控。
- 检测端与驻留共用同一应用标识 `Detector`，阻止手动启动和远程启动产生第二实例。
- 登录启动默认由驻留在被检测端拉起时自动登记（`--enable-startup <config>` / `--disable-startup` 仍可显式切换）；进程级握手可自动验证，登录重启、崩溃恢复、网络中断和完整远控 WSS 链路仍待补。

驻留与检测端均已迁移到 .NET Framework 4.7.2 x64，Release 产物不再依赖 .NET 9，达到 Win7 SP1 x64 的技术兼容条件。Win7 实机上的安装/启动、WSS/TLS 证书链、进程握手、远程开关、休眠/网络恢复和退出清理仍须单独验收，框架与产物满足基线不替代实机结论。

## 不应夸大的结论

- Release 编译通过不等于 Windows 功能完整交付。
- WPF 四个静态图片窗口推理通过不等于动态业务视频、报警截图、Server 中继或 UI 目检通过。
- DirectML 会话成功不等于所有节点都在 GPU 执行，也不等于其他 GPU/640 输入已验证。
- 驻留进程存在不等于远程控制全链路已验收；具体证据以验证报告为准。
