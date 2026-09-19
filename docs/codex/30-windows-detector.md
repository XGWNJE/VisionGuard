# Windows 检测端与驻留程序

本文只维护 Windows 检测端（WPF，net472 单一构建）和 Windows 驻留程序的当前实现事实。WinForms 检测端已退役，依据与两档位边界见[产品路线图 8.13](15-product-roadmap.md)。路线状态和验收结果见[项目概览](10-project-overview.md)、[产品路线图](15-product-roadmap.md)和[验证报告](90-verification-report.md)。

## 规范名称与职责

| 规范名称 | 路径 | 职责 |
|---|---|---|
| Windows 检测端（WPF Visual Detector） | `detector/windows-wpf/` | .NET Framework 4.7.2 / WPF / MVVM；同一份源码按 `OrtProfile` 产出两个推理档位——legacy 供 Win7 SP1 x64（CPU + 原生 1.1.0 + YOLOv5），modern 供 Win10/11（DirectML + 原生 1.19.0 + YOLO26）；来源数量按 Server 协商上限生成 |
| Windows 驻留程序（Windows Resident） | `detector/windows-resident/` | .NET Framework 4.7.2 x64 当前用户后台进程；承接主程序生命周期远控，不承接推理；框架与 API 已对齐 Win7 SP1 x64 |

Windows 检测端使用 WS 角色 `windows`；驻留程序使用独立的 `windows-resident` 角色。驻留程序与检测端按同一 `deviceId` 聚合，但连接和状态不能混为一个组件。

## 共通检测链路

`Capture -> MaskApply -> Preprocess -> ONNX Inference -> Parse -> AlertDecision -> Push`

Windows 检测端支持窗口/屏幕捕获、遮罩、预处理、ONNX 推理、告警冷却和 Server 推送；遮罩使用相对坐标 `[0,1]`，推理前涂黑并同时影响推理结果和告警截图。

解析按模型输出张量形态分档，两档契约不同、不能共用一套下标：

| 档位 | 模型 | 输出形态 | 后处理 |
|---|---|---|---|
| legacy（Win7 SP1 x64） | `yolov5*` | `[1,84,N]`，N=2100（320 输入）或 8400（640 输入），通道优先，框为中心点绝对像素 | 取 80 类分数最大值作置信度，**解析侧逐类 NMS**（IoU 0.45），再取前 5 |
| modern（Win10/11） | `yolo26*` | `[1,300,6]`，`[x1,y1,x2,y2,confidence,class_id]` | 模型图内已含 NMS，只做阈值/类别过滤，取前 5 |

形态只按输出长度判定（1800 与 84×{2100,8400} 互不整除，判定唯一）；长度不属于任一档契约时显式抛错，错误以“推理”故障显示在对应来源，不会按另一档的下标硬读张量。输出形态在变化时记录一行诊断日志，便于区分“档位选错模型”和“捕获/推理真故障”。

控制命令方面，带 `targetSourceId` 的命令只作用于该来源；不带来源标识的设备级 `pause`/`resume` 作用于全部来源，等价于界面上的“启动已配置 / 全部停止”，不会静默只操作第一路。运行中的来源拒绝改配置，必须先停止该来源。

状态文案统一为**检测中**（逐来源与设备级一致）；没有任何完整配置的来源时显示**未就绪**，此时启动入口不可用。来源页**没有保存/撤销按钮**：参数改动即自动保存（防抖 500ms 落盘），其中模型、检测类别、阈值、频率、冷却是冷改动，改动后在下一次启动该来源时生效，界面在参数区下方以「已保存：阈值、检测类别 · 重新启动此来源后生效」提示；采集目标三件套（窗口 / 选区 / 遮罩）不经过冷改动路径，变更即重建来源并立即生效。运行中上述参数控件仍不可用，切换模型会被明确拒绝。

来源数量行为：默认 1 个来源，用户在 Server 下发上限内手动新增/删除（删除保底保留 1 个，运行中的来源必须先停止）；上限只是上界，不再等于槽位数量。只在服务端**真的下发了** `maxSources` 时才收窄上限：服务端未声明上限时保持本地已放开的范围（最大 16 路），不静默压回默认 4 路。界面文案与标签页统一使用**来源**这一说法：“当前来源”为标签页名，设置键前缀为 `Source.{i}.`（早期 `Signal.*` 键做一次性迁移并保留作备份），类型名为 `SourceViewModel`。`sourceId` 取值保持不变（`default`、`signal-N`）——它是接收端历史与静音偏好的归属键，不随显示名改动。

## WPF 当前实现

- .NET Framework 4.7.2、WPF + MVVM；legacy 档为 YOLOv5 输出解析 + 固定 CPU，modern 档为 YOLO26 输出格式 `[1,300,6]` + DirectML 默认后端并支持显式 CPU 回退。两档引擎源码共用，唯一按档位分岔的是 `AppendExecutionProvider_DML` 一行。模型与档位必须配套：legacy 档只列 `yolov5*`、modern 档只列 `yolo26*`，把另一档的模型喂进来会在解析层显式报错而不是静默不出框。
- `MonitorService` 负责窗口/区域捕获、推理、告警和 UI 更新；多来源协调器按 Server 协商上限维护来源槽位，每个来源拥有独立配置、ONNX Session、定时器和冷却状态，不再提供产品图片直读来源。
- 主窗口移除左侧导航、页内重复品牌块、来源区说明栏和全局底部信息栏，中央为**按可用空间实时求解的自适应监控网格**；右侧控制台只有两页：**当前来源**与**全局设定**（原“运行环境”与“连接”两页已合并，合并后 `SettingsPage`/`ServerPage` 与对应 XAML 已删除）。`GlobalSettingsViewModel` 只做页面级组合，推理设备与模型资源仍归 `SettingsViewModel`、连接/设备身份/驻留/更新仍归 `ServerViewModel`，两套状态不合并成一个类；页内用嵌套 `DataContext` 定位。控制台移除重复页标题、操作说明和嵌套卡片，主要按钮、单行输入框、下拉框与多选入口统一为 40px 高，次要清除动作允许使用 28px 紧凑高度。当前来源名称与状态合并为单行，点击名称原位编辑，回车或失焦只自动保存名称。“当前来源”只分三组并按实际作业顺序排列：**采集目标**（窗口 / 选区 / 遮罩，共用一次“重置”）→ **识别设置**（模型、检测类别）→ **检测参数**（阈值、频率、冷却），末尾只有一行冷改动提示。“全局设定”按“推理设备（后端）→ 模型资源 → 服务器 → 设备身份 → 驻留程序 → 客户端更新”顺序排列，每个区块标题与操作各占一行。逐路阈值、类别、频率和冷却只在当前来源中配置，其中检测类别使用基于 COCO 80 类中英文映射的下拉多选菜单。每路画面内独立显示更新时间、推理耗时、最后报警与实际后端。
- 实时预览卡片区**最多 4 张、不分页**：`Views/CardGridPanel.cs`（自定义 `Panel`）与 `Services/CardLayoutPlanner.cs`（纯计算求解器）按面板自身可用像素求网格——1 张 = 1×1、2 张 = 1×2 或 2×1（取更宽的一边）、3–4 张 = 2×2；单元格宽高比夹紧在 **1:1.2 ~ 1.2:1**（长边不超过短边的 1.2 倍），超出时收窄格子并把空间留成间隔，宁可留白也不把卡片拉宽；画面一律等比缩放，不裁剪也不拉伸，避免切掉边缘目标或让检测框与画面错位。卡片自身 UI 已压缩到最小：标题行 26、操作行 28、内边距 3、外边距 2、间距 4（`CardLayoutPlanner.CardChromeHeight` = 68，改卡片模板必须同步该常量），底部信息条由两行四段并为一行三段，省下的 35 DIP 全部给画面区。
- 卡片内**画面区**短边由窗口最小尺寸兜底：`MainWindow` 的 MinWidth 1200 / MinHeight 880 与 `CardLayoutPlanner.MinimumWindowWidth/MinimumWindowHeight` 一致。最小窗口下 1 张画面短边 717、2 张 420、4 张 **323**，均达到「短边 ≥320」的要求；1920×1080 下 4 张为 423。
- 预览与推理分离：只有进入预览的来源刷画面，其余来源照常采集、推理与报警推送，`FrameProcessed` 跳过位图转换与检测框更新（省掉每路每帧的 `BitmapSource` 与 UI 通知风暴），移出预览时释放最后一帧位图。
- 「全局来源」：卡片区底部工具条上的切换按钮把卡片区**原位**换成简易编号卡片网格，`Views/GlobalSourcePanel.cs` 按可用空间求列数与行数、把卡片**铺满整个容器**（最后一行不满时按整行宽度均分拉伸；宽扁容器会自动增加列而不是拉出一张超宽卡）。每张编号卡片显示编号、名称、运行状态与「实时预览 / 仅推理」，点击整块卡片切换；**选中态不用勾选框**（太小、不显眼），而是整块背景换成主色实底 + 白字，未选中的用卡片表面色 + 次级文字，一眼能看出哪 4 个在预览。**最多 4 个**；勾满后再点第 5 个会被拒绝并提示「实时预览最多 4 个，请先取消一个再勾选」，不自动顶掉正在看的画面。选择写入设置键 `Layout.PreviewSourceIndexes`，首次运行默认前 4 个来源；退出全局视图后主视图只排布选中的来源。
- 卡片区是自适应列（`MinWidth` 680，与 `CardLayoutPlanner.MinimumCardsPanelWidth` 一致），**右侧检查区默认取最窄的 320**、把其余空间全部让给卡片区；两者之间有可拖分隔条（`GridSplitter`，检查区宽度写入设置键 `Layout.InspectorPanelWidth`，限 320–900）。检查区宽度用 `GridLength` 类型绑定：`ColumnDefinition.Width` 就是 GridLength，早先用 double 绑定 `Layout.CardsPanelWidth` 时静默失效，卡片区与检查区各占一半、设置值从来没生效过。面板宿主经 `CardGridPanel.Host` 依赖属性显式绑定 `MultiSourceViewModel`，不再沿可视树上溯取 `ItemsControl.DataContext`（那是窗口级的 `MainViewModel`，上溯永远拿不到画面比例）。
- 来源数量上限由 Server 的 `MAX_SOURCES_PER_DETECTOR`（默认 16）下发，检测端不会自行放宽。新增按钮不可用时把原因写在提示里（“已达服务端上限：服务端允许最多 N 路来源”）：默认值曾是 4，按钮静默变灰且界面无任何提示，被误读成“布局改崩了、加到 4 路就加不了”。
- **单实例与启动顺序**：主窗口由 `App.OnStartup` 显式创建，`App.xaml` 不再声明 `StartupUri`。`StartupUri` 的窗口创建发生在 `OnStartup` 返回之后，“已有实例在运行”的分支无法取消它——应用已进入关闭状态却仍去显示窗口，WPF 会抛 “Cannot set Visibility to Visible or call Show, ShowDialog, Close, or WindowInteropHelper.EnsureHandle while a Window is closing”，Win7 实机上表现为一个与真实原因无关的“Microsoft .NET Framework”崩溃对话框。重复启动现在直接干净退出，并写一行原因到下面的崩溃日志。
- **崩溃日志**：`%LOCALAPPDATA%\VisionGuard\detector-crash.log`。`Utils.LogManager` 只写 `Debug.WriteLine`（Release 编译后整条语句被移除），所以此前的实机崩溃是完全静默的；`App` 的未处理异常处理与“启动退出”现在都追加落盘（含完整堆栈）。应用关闭过程中不再弹错误框——`MessageBox` 自己也是窗口，在关闭期间 `Show` 会抛出同一个异常并盖掉真实错误。
- 布局求解是确定性的，并由 `visionguard-e2e -Mode CardLayoutPlan`（`tests/WpfInference.Benchmark --layout-plan`）在最小窗口 1200×880 / 1420×880 / 1920×1080 下对 1/2/4 张断言：卡片宽高比落在 1:1.2 ~ 1.2:1、网格恰好排得下且不裁剪（1 = 1×1、2 = 1×2 或 2×1、3–4 = 2×2）、1:1 画面短边（最小窗口下 1 张 ≥500、2 张 ≥380、4 张 ≥320）、宽扁与窄高容器靠留白吸收比例差异而不是产出畸形卡片、同输入结果一致、零可用空间返回无效布局而不抛异常。它只证明布局数学；真实界面视觉、分隔条手感与全局来源的选中交互已由 owner 在 2026-09-20 目检通过。
- 采集目标三件套必须一起重置：窗口或选区一换，原遮罩坐标就失去意义，因此界面只提供整体“重置”（有遮罩时先确认），不再分别提供“清除目标”和独立的“区域排除”区块。
- 模型资源区是一个清单而不是“下拉 + 状态 + 下载按钮”：本档位每个模型一行，行内直接给出展示名、`✓ 已下载 / 未下载 / 下载中 n%`、下载进度条和行内下载按钮（已下载时按钮转成不可点的“已就绪”）。模型缓存为 `%APPDATA%\VisionGuard\models\`，旧 `Assets/` 模型会迁移。
- 下载失败必须可读：`ModelManager.LastFailureReason` 记录具体原因（HTTP 状态码或异常类型与消息），由模型行直接显示；服务端断流导致字节数与 `Content-Length` 不一致时判为失败并删除临时文件重下，不会把半截文件当成功。档位判定在 `NativeLibrarySelector` 的类型初始化阶段完成，因此 `ModelManager.ModelKeys` 这类静态清单不会在 `Initialize()` 之前读到错误的档位（legacy 档曾因此列出 `yolo26*`）。
- 来源页的模型下拉**只列本机已下载的模型**：未下载的模型选了也启动不了，把它们列出来等于把失败推给用户。当前选中模型若不在本机，会在读取时收敛到第一个已下载模型；一个都没下载时下拉禁用，并在下方显示“本机还没有模型：请到「全局设定 → 模型资源」下载后再选”。在全局设定里下载完成后，来源页下拉立即能看到新模型（下载完成会触发一次可用性刷新，不需要重启）。
- Release 输出按档位分别为 `bin\x64\modern\` 与 `bin\x64\legacy\`；两档的原生库各自放在 `native\modern\`、`native\legacy\`，应用根目录不得出现 `onnxruntime.dll` 或 `DirectML.dll`（根目录残留会让 DllImport 先命中根目录，程序显式报错而不静默换档）。档位、托管包版本、编译期开关、输出目录与原生库布置全部由 `detector/windows-shared/NativeLibraries.props` 单点定义，检测端、人员 smoke 与推理探针共用；`OrtProfile` 必须按 MSBuild 全局属性传播（`-p:OrtProfile=legacy` 同时作用于被引用的检测端工程），否则会出现“探针按 legacy、生产程序集按 modern”的假验证。建会话后检测端还会校验实际加载的原生库确实来自本档位目录：机器上存在系统级同名 `onnxruntime.dll` 时会按模块名抢占，实测表现为一推理就无诊断信息的进程终止，现在会变成可读的启动故障。导航 PNG 以内嵌 Resource 提供，模型和 `Assets/` 不随发行包分发。
- WPF 采集目标使用统一像素下限：窗口客户区、屏幕选区和窗口子区域的宽度与高度必须分别严格大于 100 像素；屏幕选区拖拽提示与提交校验共用 DIP 到物理像素映射，窗口子区域则直接使用 `PrintWindow` 客户区帧内像素。窗口枚举直接过滤任一边不达标的候选，确认、启动和实际截图层继续做防御式校验。遮罩仍是帧内 `[0,1]` 相对坐标，不受客户区像素尺寸变化影响。
- WPF 不维护启动时 DPI 基线，也不会因缩放变化停止来源或要求重启；PerMonitorV2 负责窗口布局与输入坐标更新，采集帧则按每次捕获的实际重绘范围自洽。常见缩放比例共用同一路径，没有按 100%/150%/200% 分支。
- 新选择的窗口会持久化标题、窗口类名和进程名；恢复时优先精确匹配，标题变化后只有稳定身份唯一时才自动重绑，同名或同身份多候选会保持停止并要求重新选择。旧配置继续使用唯一标题匹配。
- `PrintWindow` 返回全黑画面时会产生“疑似黑屏”采集故障并显示在对应来源，不再只写调试日志。运行期故障（捕获、黑屏、推理、处理）全部保留在发生故障的来源并显示原因，当前不存在会触发全局停机的故障类型，也没有对应的策略位：故障种类 `MonitorFailureKind` 只用于逐来源上报（`MonitorFailurePolicy` 这个恒为 `false` 的占位策略已删除）。FPS 只统计成功帧。
- 多路 CPU 允许运行，性能提示按**实测帧率**判断：容量基线已移除（2026-09-20）——“容量”菜单、`SettingsViewModel.Capacity.*` 与 `CapacityPolicy` 全部删除，不再让用户猜“DirectML 几路 / CPU 几路”。`Services/PerformanceWatchdog.cs`（纯计算）用 10 秒滚动窗口的实测帧率对比这一路设定的目标帧率：**低于目标 80% 且连续持续 30 秒**才确认不足，尚未出帧（fps ≤ 0）不按性能判定（那是启动中或故障，由来源的 Error 表达）。确认不足后卡片状态显示「性能不足 x/y FPS」、ToolTip 给完整说明，并由 `MultiSourceViewModel` 弹一次提醒（**冷却 10 分钟**，弹窗列出是哪几路与目标/实际帧率）。不拒绝启动、不自动减路或降帧，性能不足与故障分开表达；验证探针等无界面宿主不弹窗，只保留状态字段供断言。
- 报警先写入按 `channel` 分区的 `%LocalAppData%\VisionGuard\*alert-outbox-<channel>.json`，断网或进程重启后重发；只有 Server 返回持久化 `alert-ack` 才移除，随后异步发送截图。队列损坏时原文件会隔离为带时间戳的 `.corrupt-*` 并明确记录，而非静默清空。检测端支持用 `VISIONGUARD_SERVER_URL` 与 `VISIONGUARD_CHANNEL` 接入独立测试实例，跨通道积压不会互相重放。
- Windows 人员 smoke 按当前配置的来源数量取证（`-SourceCount`，范围 2–16，四路为回归基线）：`detector/windows-wpf-smoke/` 打开独立可见的 net472 窗口逐个 `WindowHandle` 捕获，夹具用 net472 是因为 legacy 档要能在 Win7 上运行。已通过逐路 `person`、停止/重配隔离、移动缩放、遮挡、最小化故障隔离及恢复、关闭隔离、CPU 超容量允许运行并提示、DirectML 初始化回退和运行期推理故障逐路隔离；动态视频、其他 GPU、显卡驱动故障、持续运行、报警链和 UI 视觉仍需单独验收。夹具图片数量不得少于来源数量，默认夹具目录不足时脚本直接报错而不是降低来源数量。只依赖静态图片 fixture 的旧人员检测窗口与入口已随 WinForms 退役一并删除，当前的缺口见[验证报告](90-verification-report.md)。
- 档位/模型输出契约有独立入口：`visionguard-e2e -Mode WpfParserContract` 会分别按 `OrtProfile`（legacy 与 modern）构建 `tests/WpfInference.Benchmark`，对真实图片断言输出形态识别、形态与本档位一致、未知长度被拒、原生 ONNX Runtime 来自本档位目录、解析出预期业务目标（默认 `person`，置信度 ≥0.5）且框落在画面内。它不打开窗口、不依赖 GPU，因此两档都能在普通 Windows 宿主上复算；它只证明「模型 → 解析 → 检测框」这一段，不证明窗口捕获、报警推送、UI 显示或多路帧率。

## Windows 驻留程序当前实现

- 使用独立 `windows-resident` WS 身份与 Server 通信；只接受 `open-detector`、`close-detector` 两个固定生命周期命令（V10 决策 28：Windows 只剩一个检测端）。
- **启动方向已被 V10 反转**：驻留不再负责打开检测端，而是由检测端在启动时用 `UseShellExecute = true` 拉起（传入自身写出的 `%LOCALAPPDATA%\VisionGuard\resident-config.json`），使驻留脱离父进程生命周期；驻留自行登记登录自启。检测端正常退出或崩溃后驻留继续运行，因此仍能接受 `open-detector` 远程重新打开。
- 驻留程序目标框架为 .NET Framework 4.7.2 x64，JSON 使用 `JavaScriptSerializer`，WebSocket 使用与检测端同一份自研 `Net/MinimalWebSocketClient.cs`（源码链接复用，显式 TLS 1.2）；Release 目录必须同时包含主 EXE 与配置文件。**不再依赖 `websocket-sharp`**——该库以 `SslProtocols.Default` 协商 TLS，在 Windows 7 上退化为 TLS 1.0 会被服务端拒绝（检测端已在 Win7 实测确认该根因）。
- 驻留自身使用当前用户会话命名互斥体防止重复启动；连接认证成功后才发送组件心跳，远控完成回执携带 `requestId`、`phase=completed` 与目标设备，供 Server 严格关联请求。
- Win7 支持基线为 Windows 7 SP1 x64 + .NET Framework 4.7.2 + TLS 1.2 系统更新；代码与产物满足该基线不替代 Win7 实机/WSS 证书链验收。
- 与检测端通过当前用户会话事件完成启动握手和正常退出请求（`Local\VisionGuard.Detector.Running` / `.Shutdown`）；远程打开只启动检测端，不自动开始监控。
- 检测端与驻留共用同一应用标识 `Detector`，阻止手动启动和远程启动产生第二实例。
- **驻留与检测端是配套分发**：构建时驻留产物被复制进每个档位的输出目录（`bin\x64\<档位>\` 同时含 `VisionGuard.exe` 与 `VisionGuard.Resident.exe[.config]`），发布包只打包该档位目录，并校验包根同时含驻留 EXE 与配置。先前只有发布 ZIP 合并驻留、档位目录不含它，本地/离线整体拷贝档位目录时就会缺驻留。
- **拉起失败必须可见**：`ResidentLauncher` 保留状态快照（是否找到、是否运行、是否握手成功、失败原因）并写 `%LOCALAPPDATA%\VisionGuard\resident-launch.log`；连接页的“驻留程序”一行只表达状态（运行中 / 未找到驻留程序 / 未运行+原因），不附加角色说明或路径文案，并提供刷新（未运行时重试拉起）。此前只写 `Debug.WriteLine`，Release 包在用户机器上完全静默——Win7 实测驻留没起来时界面与接收端都没有任何提示。
- 驻留配置文件由检测端每次启动重写，**必须用 JSON 序列化器生成**：手写拼接会把 Windows 路径里的反斜杠写成 JSON 非法转义，驻留在反序列化处 fatal 退出（2026-09-17 查实的 Win7/Win10 共用根因）。写入后检测端会立即回读自检，不通过就拒绝拉起并报出原因。
- 验证入口：`visionguard-e2e -Mode ResidentLaunch` 用隔离 Server 对两个档位分别断言「检测端找到同目录驻留 → 驻留在超时内进入单实例握手 → 服务端 device-list 报 `components.resident = running`」。它不覆盖远程 `open-detector`/`close-detector` 实际动作、登录自启与重启恢复。旧的 `scripts/test-windows-resident.js`（直接运行驻留、不经过检测端拉起）仍可用于驻留自身协议验证。
- 登录启动默认由驻留在被检测端拉起时自动登记（`--enable-startup <config>` / `--disable-startup` 仍可显式切换）；进程级握手可自动验证，登录重启、崩溃恢复、网络中断和完整远控 WSS 链路仍待补。

驻留与检测端均已迁移到 .NET Framework 4.7.2 x64，Release 产物不再依赖 .NET 9，达到 Win7 SP1 x64 的技术兼容条件。Win7 实机上的安装/启动、WSS/TLS 证书链、进程握手、远程开关、休眠/网络恢复和退出清理仍须单独验收，框架与产物满足基线不替代实机结论。

## 不应夸大的结论

- Release 编译通过不等于 Windows 功能完整交付；两档位各自 0 警告 0 错误也不等于 Win7 真机推理已通过。
- 静态图片窗口推理通过不等于动态业务视频、报警截图、Server 中继或 UI 目检通过。
- DirectML 会话成功不等于所有节点都在 GPU 执行，也不等于其他 GPU/640 输入已验证。
- 驻留进程存在不等于远程控制全链路已验收；具体证据以验证报告为准。
