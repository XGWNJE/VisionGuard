# Verification Report

本文是 VisionGuard 的验证证据台账，不是产品路线图，也不把源码存在、编译成功、帧循环、FPS、界面启动或截图展示写成完整功能通过。每条结论都注明验证范围；详细命令由[运维文档](60-operations.md)和两个保留 Skill 维护。

历史记录只保留结论与证据路径，过程细节不再重述；被替代的入口必须在记录里标明失效，避免被当成仍然可跑。

## 判定词

- **已自动验证**：有可复现命令和机器可读结果，断言覆盖了所写语义。
- **主体实现**：源码已具备功能，但验证范围不足以宣布完整交付。
- **待人工目检**：需要 owner 检查真实 UI、窗口采集或发行包表现。
- **待真机**：需要授权的真实 Android 设备或目标硬件。
- **未实现/未来规划**：当前源码没有该能力，不能用局部 smoke 代替。

## 当前仓库证据

| 范围 | 状态 | 本次证据能证明什么 | 证据 |
|---|---|---|---|
| 文档与治理审核 | 已自动验证（本次运行） | Markdown 链接/锚点、编码、版本、组件、入口、Skill 和产品边界符合契约 | `node scripts/check-docs.js`；`node --test scripts/check-docs.test.js scripts/release-workflow.test.js scripts/version-sync.test.js` |
| Server 单测与协议测试 | 已自动验证（本次运行） | 18 项测试通过；覆盖通道不匹配拒绝、报警落盘后 ACK、重复重试不广播、同 ID 冲突拒绝、告警/截图安全、控制请求关联、`device-list` 的 `maxSources`/`sourceLimitExceeded`、超限心跳整组拒绝后保留旧快照并由下一个正常心跳清除标记、驻留-only 设备的业务命令回“该设备当前没有检测端在线”，以及按推理档位分发更新包的解析（平台别名、档位回落、Android 不受档位影响） | `npm --prefix server test`；`server/test/update-route.test.ts` |
| 新协议隔离测试通道 | 已自动验证 | 独立 Server 进程使用临时端口和独立数据目录启动，`/health` 返回指定通道；协议测试证明其他通道认证被拒绝 | `scripts/start-isolated-test-server.ps1`；`server/test/control-request.test.ts` |
| V10 Windows 检测端统一 · 批次①（net472 迁移） | 已自动验证（构建 + 真实窗口启动） | `detector/windows-wpf` 目标框架由 `net9.0-windows` 改为 `net472`，新增显式入口 `Program.cs`（设置 TLS 1.2）与 `Runtime/IsExternalInit.cs`、`Runtime/Net472Compat.cs`；修复 36 处 API 差异。5 个依赖工程（`WpfAlertChain.Probe`、`WpfInference.Benchmark`、`windows-wpf-smoke`、`SingleInstance.Probe`、`WindowsConfig.Tests`）同步迁到 net472 并各自构建通过（其中 WinForms 侧工程与 `WindowsConfig.Tests` 已随后退役删除，现存探针见[运维文档](60-operations.md)）。Release 构建 0 错误；真实 Release 窗口启动后正常关闭 | `dotnet build detector/windows-wpf/VisionGuard.csproj -c Release`；`detector/windows-wpf/bin/x64/VisionGuard.exe`；各依赖工程 `dotnet build` |
| V10 · 批次②（双推理档位） | 已自动验证（两档构建 + 原生库路径 + 档位 UI） | 同一份源码按 `OrtProfile` 构建两档，各自锁定同代托管/原生配对：`modern`=托管 ORT 1.19.0 + 原生 1.19.0 + DirectML + YOLO26；`legacy`=托管 ORT 1.2.0 + 原生 1.1.0 + 固定 CPU + YOLOv5。原生库不留在应用根目录，布置到 `native\<档位>\` 后由 `Runtime/NativeLibrarySelector.Initialize()` 用**绝对路径预加载**选择（`PATH` 会被系统目录抢占，已实测）。两档 Release 构建各 0 警告 0 错误；真实进程模块枚举确认 legacy 加载 `bin\x64\legacy\native\legacy\onnxruntime.dll`、modern 加载 `bin\x64\modern\native\modern\onnxruntime.dll`，两档根目录均无 ORT/DirectML 原生库；UI Automation 读到 legacy 档 `YOLOv5su 320` + `CPU · Windows 7 固定后端`、modern 档 `YOLO26s 320` + `DirectML · GPU 加速`。**未验证**：legacy 档在目标系统上的实际推理与多路帧率 | `visionguard-build -Target WPF`；`detector/windows-wpf/bin/x64/{modern,legacy}/`；进程模块枚举与 UI Automation 输出 |
| V10 · 批次③（自研 WebSocket 客户端） | 已自动验证（真实服务器认证 + 长连接） | 新增 `Net/MinimalWebSocketClient.cs`，继承 `System.Net.WebSockets.WebSocket`，对上层与 `ClientWebSocket` 等价；显式 `SslProtocols.Tls12`、HTTP 升级与 `Sec-WebSocket-Accept` 校验、掩码帧读写、ping→pong、close 处理；产物中已无 `websocket-sharp`。modern 档连接生产 Server 持续 70 秒无断开；legacy 档 UI Automation 读到连接页 `● 已连接`、`当前版本 4.4.4`。**未验证**：目标系统上的实际连接、断网恢复与重连退避 | `dotnet build detector/windows-wpf/VisionGuard.csproj -c Release`；真实 Release 窗口 70 秒运行；UI Automation 连接页文本 |
| V10 · 批次④（驻留单次启动与统一命名） | 已自动验证（隔离 Server 全链路 + 真实进程行为） | **角色反转**：检测端启动时探测 `Local\VisionGuard.Resident.SingleInstance`，未运行则用 `UseShellExecute = true` 拉起驻留（写 `%LOCALAPPDATA%\VisionGuard\resident-config.json`），使驻留脱离父进程生命周期并自动登记登录自启；驻留的 WebSocket 与检测端共用同一份 `MinimalWebSocketClient`，同样移除 `websocket-sharp`。**统一命名**：命令 `open-detector`/`close-detector`、组件 `detectorApp`、配置键 `DetectorPath`、应用标识 `Detector`，已贯通驻留、`ConnectionManager`、接收端模型与 UI（单一「打开/关闭检测端」入口）、`check-docs.js` 断言与文档。隔离 Server 上「启动检测端 → 驻留自动启动 → 杀掉检测端 → 驻留存活 → `open-detector` 重新拉起」整条链路通过，回执 `phase=forwarded success=true`；接收端 `testDebugUnitTest` 与 `assembleDebug` 通过 | `visionguard-build -Target Windows`；隔离 Server + 真实 Release 检测端与驻留进程；`%LOCALAPPDATA%\VisionGuard\resident.log`；`HKCU\Software\Microsoft\Windows\CurrentVersion\Run` |
| V10 · WPF 多路人员推理烟测复验（2/3/4 路真实窗口） | 已自动验证（全项通过） | `visionguard-e2e -Mode WpfPersonDetection` 在自身进程内用 `System.Windows.Forms` 打开真实可见顶层窗口（每个显示一张含人图片），逐路 `WindowHandle` 采集后跑 modern 档真实推理。**2 路**：`passed=true`、逐路 2.91 FPS；**3 路**：`default` 58 帧全命中、`signal-2` 84 帧中 44 帧命中、`signal-3` 69 帧全命中，逐路 2.90–2.91 FPS；**4 路（回归基线）**：逐路 2.91 FPS。三轮均 `unexpectedRuntimeErrors` 为空、`ActiveBackend=DirectML`、11 项断言（含 `directMlRuntimeFailureIsolated`）全为真。**同轮修掉两个烟测自身缺陷**（都不是检测端缺陷，检测端的故障注入与逐路隔离逻辑未改动）：夹具首个窗体在宿主最小化启动时可能继承最小化状态、客户区 0×0，被 `WindowEnumerator` 过滤后导致进程崩溃，已加显式 `WindowState=Normal` 与客户区校验；故障注入用例原先固定用 `windows[0]`/`windows[1]`，而 `closeIsolation` 会先关掉 `windows[1]`，使 2 路时该项必然误判为 false，现改用仍然打开的窗口并在 2 路时给两个来源显式不同身份。**未覆盖**：6 路（默认夹具目录只有 4 张人像图，脚本按设计报错而非降低来源数）、动态视频、其他 GPU、显卡驱动重置、持续运行、报警链与 UI 目检 | `visionguard-e2e -Mode WpfPersonDetection -WpfSourceCount {2,3,4}`；逐轮证据 `artifacts/e2e/20260917-040252/wpf-person-detection.txt`、`artifacts/e2e/20260917-040339/wpf-person-detection.txt`、`artifacts/e2e/20260917-040428/wpf-person-detection.txt`；汇总 `artifacts/e2e/20260917-v10-wpf-smoke-verify.txt` |
| V10 · WinForms 退役 | 已自动验证（两档构建 + 悬空引用清扫）/待人工、真机 | Windows 只剩 `detector/windows-wpf/` 的单一 net472 构建，两档位配对见批次②。退役后全仓悬空引用归零（scripts/.agents/.github 仅剩禁止回退的否定断言），两档构建仍各 0 警告 0 错误。**已删除的覆盖（净减损，不得写成仍然存在）**：`tests/WindowsConfig.Tests` 的采集几何、共享 settings 合并/迁移与窗口重绑断言，`tests/WinFormsMultiSource.Tests`，以及只依赖静态图片 fixture 的人员检测覆盖，当前均无等价入口 | `detector/windows-wpf/bin/x64/{legacy,modern}/` 与各自 `native/{legacy,modern}/onnxruntime.dll`；`visionguard-build -Target Windows` |
| WPF → 隔离 Server → Android 接收端报警链 | 已自动验证（确定性传输链） | `WpfAlertChain.Probe` 复用生产 WPF `ServerPushService` 发送 `person` 报警与真实 PNG；Server 完成落盘、ACK、广播和截图备份，Android API 36 模拟器认证隔离通道、收到报警并缓存同一 `alertId` 截图。该测试与人员推理烟测分别通过，但尚未把推理触发与传输探针合成一个进程内测试 | `artifacts/e2e/20260912-234853/summary.json`；`tests/WpfAlertChain.Probe/`；模拟器日志中的 `6c5dc71a-7c83-4cb5-ae57-0ff82ee89c35` |
| ServerBuild | 已自动验证 | TypeScript 编译成功且 `server/dist/index.js` 存在；不是 HTTP/WS 运行 E2E | `artifacts/e2e/20260910-063530/summary.json`、`server-build.txt` |
| WPF 现代化主界面 | 已自动验证（100% 与 200%）/待 owner 目检 | 100% 下 Release 程序及三个控制台页面的运行、交互与布局通过。200% 复验时曾因自定义 DPI 基线初始化和模态框重入持续弹“调度程序处理已暂停”，删除启动 DPI 基线、停止/重启策略并修复窗口关闭递归后，真实 Release 主程序只有一个顶层窗口、切页有响应、10 秒内正常退出、无 `.NET Runtime`/Application Error/WER 事件。2026-09-16 复核：顶部重复摘要与本机全局启停已移除，来源卡片标题栏各自提供新增/删除图标 | `artifacts/e2e/20260915-225349/wpf-real-window-200pct.png`；`artifacts/e2e/wpf-density-source.png`、`wpf-density-environment.png`、`wpf-density-connection-final.png` |
| WPF 采集尺寸与遮罩窗口约束 | 已自动验证（边界与实际拖拽）/待人工目检（遮罩布局） | `CaptureSizeConstraints` 断言宽高分别严格大于 100 物理像素：`100×101`、`101×100`、`99×500` 拒绝，`101×101` 接受；200% DPI 映射覆盖 `50 DIP → 100 px` 拒绝与 `50.5 DIP → 101 px` 接受。Win32 创建的 `100×150` 可见窗口未出现在实际枚举结果中；在 Release 程序中实际拖出 `93×93 px` 选区后选择器保持打开并提示“选区过小” | `visionguard-build -Target WPF`；Windows UI Automation + Win32 鼠标输入；`MaskEditorWindow.xaml` 最小尺寸 |
| Windows 驻留程序 | 已自动验证（构建 + 隔离链路） | 驻留已迁到 .NET Framework 4.7.2 x64，`visionguard-build` 构建 0 警告 0 错误。独立通道验证驻留认证、组件心跳、Server 生命周期命令转发及带 `requestId` 的完成回执，并用不存在的检测端路径断言失败原因 | `visionguard-build -Target WindowsResident`；`scripts/test-windows-resident.js`；`detector/windows-resident/bin/Release/net472/` |
| Win7 SP1 x64 前置环境 | 已自动验证 | 目标系统确认为 Windows 7 SP1 x64；KB4490628、KB4474419、KB3140245、WinHTTP 32/64 位 TLS 1.2（均为 2560）、KB4019990 与 .NET Framework 4.7.2（Release 461814）全部通过机器判定 | `scripts/check-win7-prerequisites.ps1`；`artifacts/e2e/win7-prerequisites.json` |
| Android 接收端控制完成语义 | 已自动验证（单元/构建） | 只把字段完整的 `completed` 回执映射为结构化结果，缺失阶段的旧载荷被拒绝；冷却档位保留设备当前值、缺少 `maxSources`/`sourceLimitExceeded` 的旧记录按默认值解析；Debug 单测与 APK 构建通过 | `receiver/android/gradlew.bat testDebugUnitTest assembleDebug` |
| Android 检测端/接收端启动 | 已自动验证（小米 15） | 指定 `7d3584e1` 设备上的 Debug 构建、安装、清数据、运行时权限、Activity、前台服务和观测窗口无崩溃；不证明完整告警链 | `artifacts/e2e/20260910-105355/summary.json` |
| Android 接收端模拟器启动 | 已自动验证 | `VisionGuard_API36` 上 Debug 同签名覆盖安装、运行权限、Activity/进程状态和观测窗口无崩溃；Release APK 已单独构建，因模拟器已有不同签名包而未覆盖安装 | `artifacts/e2e/20260911-065633/summary.json`、`artifacts/e2e/20260911-065620/summary.json` |
| Android NNAPI 实际推理 | 已自动验证（局部） | 归一化 `yolo26n_320` 在小米 15 上完成 CameraX → 预处理 → 真实推理，profile 记录 `NnapiExecutionProvider`；MI 6X 上同一 provider 实际落到 `nnapi-reference`，约 `471–499 ms/次`，慢于原始模型 CPU 回退的 `157–179 ms/次`。provider 命中不等于硬件执行确认，尚未证明 GPU/NPU/DSP、全图下沉、长期稳定性或精度 | `artifacts/e2e/20260910-111229/android-nnapi-evidence-summary.json`、`artifacts/e2e/20260910-231809-mi6x-nnapi/summary.json`、`artifacts/v4/wpf-person-slicefix.json` |
| Android 设备环境发现 | 已自动验证（历史快照） | 小米 15 `7d3584e1` 曾以 ADB `device` 连接，SoC `SM8750`、Android API 36；MI 6X `1fdbd0b1` 用于 NNAPI 与两端验证。该记录不表示两台设备现在仍连接 | `artifacts/e2e/20260910-102912/inventory.json`、`artifacts/e2e/20260910-231809-mi6x-nnapi/summary.json`、`artifacts/e2e/20260911-004712/summary.json` |

## 历史记录（入口已失效，仅保留结论与证据）

- **WinForms 检测端多来源主体**：200% 缩放下 DPI-unaware net472 夹具的 6 路 YOLOv5 CPU 推理每路命中 `person`、无黑屏，约 2.91–2.94 FPS，子区域裁剪、停止隔离与容量提示通过；100% 同链路也通过。证据 `artifacts/e2e/20260915-223356/winforms-person-detection.json`、`artifacts/e2e/20260915-222848/winforms-person-detection.json`；入口 `-Mode WinFormsPersonDetection` 与 `detector/windows-winforms-smoke/` **已随退役删除**。
- **WinForms 原生主界面布局**：按 WPF 结构重排为“当前来源 / 运行环境 / 连接”三页，真实 Release 窗口启动、切页与关闭通过。证据 `artifacts/e2e/20260916-080406/winforms-release-current-source-200pct.png`、`artifacts/e2e/20260916-080406/winforms-release-runtime-environment-actual-key-200pct.png`、`artifacts/e2e/20260916-080406/winforms-release-connection-200pct.png`；`visionguard-build -Target WinForms` **已无对应工程**。
- **WinForms 设备级命令语义**：不带 `targetSourceId` 的 `pause` 回执“已向全部来源下发停止”，带 `targetSourceId` 的 `resume` 精确回写该来源；取代了此前“设备级命令被静默落到第一路”的行为。证据 `artifacts/e2e/20260915-device-level-command.json`；驱动 `scripts/test-device-level-command.js` **已删除，Windows 侧当前无等价入口**，协议语义本身未变。
- **Windows 宿主机约束与显示缩放策略**：`WindowsConfig.Tests` 曾覆盖采集尺寸边界、100%–200% 的 DIP→采集像素换算、黑屏判定、窗口重绑歧义、逐路故障与容量提示、报警发件箱及跨端设置合并；`WinFormsMultiSource.Tests` 曾覆盖协商上限只拒绝新增注册、逐路启停/配置/模型隔离与容量提示。**两个工程均已删除，上述断言不再有回归基线**，`-Mode WindowsTests` 一并移除。证据 `artifacts/e2e/20260915-225619/windows-config-tests.txt`、`winforms-multi-source-tests.txt`。
- **WinForms 来源协商与上限语义**：隔离 Server 以 `MAX_SOURCES_PER_DETECTOR=6` 启动时，6 路心跳被识别为 6 个稳定来源；上限为 4 时同样心跳整组被拒而不是静默截断。该结论属 Server 侧语义，仍在 Server 单测覆盖内；检测端侧无等价入口。证据 `artifacts/e2e/20260914-winforms-server-push-6sources.json`、`artifacts/e2e/20260914-winforms-server-push-4sources.json`、`artifacts/e2e/20260914-winforms-server-push-overlimit.log`；驱动 `scripts/test-winforms-server-push.js` **已删除**。
- **V10 端到端回归（退役前）**：2 路真实窗口烟测 13/14 项通过，逐路命中与 2.9 FPS 达标，唯一未通过的 `directMlRuntimeFailureIsolated` 已于 2026-09-17 查明是**夹具窗口未真正显示（客户区 0×0）**所致，不是产品缺陷；产品侧故障注入与逐路隔离逻辑未改动。证据 `artifacts/e2e/v10-wpf-smoke.json`；驱动 `scripts/test-wpf-person-detection.ps1` 与夹具 `detector/windows-winforms-smoke/` **均已删除**。
- **WPF 四路/三路人员推理（夹具形态变更前）**：200% 缩放下以 DPI-unaware net472 窗口工具承载 6 个独立窗口，规范化后 DirectML 每路约 3 FPS 且每帧命中 `person`（60/60、87/87、92/92、98/98、103/103、70/70），子区域裁剪、移动缩放、遮挡、最小化恢复、关闭隔离与停止/重配隔离全部通过；三路图片推理一轮断言的是当时生效的“CPU 多路拒绝”边界，该边界已于 2026-09-13 撤销。证据 `artifacts/e2e/wpf-window-person-detection-client-plane-200pct.json`、`artifacts/e2e/wpf-window-person-detection-client-plane-pass.json`、`artifacts/e2e/20260910-063535/summary.json`。
- **WPF 窗口重绑与故障隔离**：窗口恢复覆盖精确身份命中、唯一稳定身份下标题变化重绑、同身份多候选拒绝与旧配置同名窗口拒绝；受控 DirectML 引擎在真实定时链路抛错后故障保留在对应来源、其余来源继续运行。窗口重绑断言原由 `tests/WindowsConfig.Tests`（**已删除**）覆盖；尚未模拟显卡驱动重置或物理设备丢失。证据 `artifacts/e2e/20260914-113036/wpf-person-detection.json`。
- **WPF DirectML/CPU 负样本对照**：当前界面截图样本在两后端均无有效检测；不证明人员召回或代表性精度。证据 `artifacts/v3/wpf-directml-cpu-parity.json`。
- **Android 两端服务器认证与 MI 6X 运行**：2026-09-11 检测端在 MI 6X 上认证成功，接收端严格接收新协议后复测成功。**2026-09-15 复核：该检测端证据早于 `channel` 通道闸门（`ddb06fc`），当前 Server 强制校验 `channel` 而 Android 检测端不发送该字段，本端在当前协议下无法认证；按路线图决策 19 Android 检测端已暂缓。** 接收端带 `channel`，认证路径不受影响。证据 `artifacts/e2e/20260911-001601/summary.json`、`20260911-003309/summary.json`、`20260911-004712/summary.json`。

## 当前实现与未来能力边界

- 当前 Server 维护 WS 认证、心跳、告警广播、截图/更新路由和连接在线状态；`online=false` 不等于 `DeviceOfflineAlert` 已生成或送达。不把源码存在当成功能已交付。
- `DeviceOfflineAlert`、权威多租户事件存储、逐接收端 inbox/ACK、独立 Web Management Console、Linux Edge Detector、多传感器融合和 Qualcomm QNN/NCNN 加速仍属路线图范围；Windows 检测端到 Server 的持久 outbox/幂等 ACK 已实现，但不等于接收端离线可靠投递完成。
- Windows 静态图片窗口 smoke 证明真实 `WindowHandle` 捕获和人员推理，但不证明动态业务视频、报警截图、中继链路、长时稳定性或 UI 外观。
- 采集几何、共享 settings 合并/迁移、窗口重绑和多来源协调器隔离当前没有自动化回归基线，不能写成仍然受测。
- Android 启动 smoke 不证明摄像头推理、模型下载、Server 连接或接收端展示的完整链路。
- Release 构建不等于正式发行包已通过；正式发行还需要签名、ZIP 清洁度、元数据、上传/部署和公网验证。

## 待人工、真机或生产验证

1. Windows 检测端现代化 UI 的视觉层级、品牌配色和高 DPI 表现仍待 owner 目检；动态窗口移动缩放、遮挡、关闭、最小化故障隔离及恢复已自动验证。
2. Windows 7 SP1 x64 目标系统验收（驻留与 legacy 档检测端的安装、启动、TLS/WSS 证书链、推理、多路帧率、进程握手、网络/休眠恢复与退出清理）由 owner 自行完成；本报告只到离机与宿主機取证，不得写成已通过。
3. Android 检测端与接收端的真机 UI、归一化模型正式下载、摄像头长时运行、网络切换和温升/资源表现。
4. WPF 生产传输服务 → Server → Android 接收端的报警、截图归属和 ACK 已在隔离通道自动贯通；仍需补推理自然触发到传输的单进程测试、真实断网恢复、接收端离线补发和真机通知验收。
5. 生产 VPS、正式发行 ZIP/APK、发布回滚和公网更新接口：尚未执行。
6. 首次连接 Server 的 Windows 检测端部署缺口：生产环境需要部署含 `wpf-legacy` 平台键与按档位分发逻辑的 Server 版本。
7. 下次发行前的动作项：`server/src/index.ts` 的 `app.use('/models', express.static(.../data/models))` 是模型下载的唯一通路，`scripts/publish-release.ps1` 的 `Copy-Models` 只从 `detector\windows-wpf\Assets\` 收集 `*.onnx`（目录受 `.gitignore` 排除、不入版本控制）。旧的 YOLOv5 模型原先放在已删除的 `detector\windows-winforms\Assets\`，因此**发行前必须先把 YOLOv5 的 6 个模型放进 `detector\windows-wpf\Assets\`**（可用 `scripts/export-yolov5-models.py` 导出，或从 Server 的 `/models/<键>.onnx` 取回），否则 legacy 档会下载不到模型而无法推理。本报告不声称该步骤已完成。

历史验证记录必须保留原始证据路径，并在重新运行后更新状态；不要仅因日期较新就把历史局部证据升级为完整验收。
