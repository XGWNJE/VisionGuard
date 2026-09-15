# Verification Report

本文是 VisionGuard 的验证证据台账，不是产品路线图，也不把源码存在、编译成功、帧循环、FPS、界面启动或截图展示写成完整功能通过。每条结论都注明验证范围；详细命令由[运维文档](60-operations.md)和两个保留 Skill 维护。

## 判定词

- **已自动验证**：有可复现命令和机器可读结果，断言覆盖了所写语义。
- **主体实现**：源码已具备功能，但验证范围不足以宣布完整交付。
- **待人工目检**：需要 owner 检查真实 UI、窗口采集或发行包表现。
- **待真机**：需要授权的真实 Android 设备或目标硬件。
- **未实现/未来规划**：当前源码没有该能力，不能用局部 smoke 代替。

## 当前仓库证据

| 范围 | 状态 | 本次证据能证明什么 | 证据 |
|---|---|---|---|
| 文档与治理审核 | 已自动验证（本次运行） | Markdown 链接/锚点、编码、版本、组件、入口、Skill 和产品边界符合契约 | `node scripts/check-docs.js`；本文件所在提交后的命令输出 |
| Server 单测与协议测试 | 已自动验证（本次运行） | 14 项测试通过；覆盖通道不匹配拒绝、报警落盘后 ACK、重复重试不广播、同 ID 冲突拒绝、告警/截图安全、控制请求关联，以及 2026-09-15 新增的下发字段与语义：`device-list` 携带 `maxSources`/`sourceLimitExceeded`、超限心跳整组拒绝后保留旧来源快照并由下一个正常心跳清除标记、驻留-only 设备的业务命令回“该设备当前没有检测端在线”。设备级命令仍按整机中继，由检测端解释为全部来源 | `npm --prefix server test`；`server/dist/index.js` |
| 新协议隔离测试通道 | 已自动验证（本次运行） | 独立 Server 进程使用临时端口和独立数据目录启动，`/health` 返回指定 `vnext-runtime-smoke` 通道；协议测试证明其他通道认证被拒绝 | `scripts/start-isolated-test-server.ps1`；本次本地运行输出；`server/test/control-request.test.ts` |
| Windows 报警可靠发件箱 | 已自动验证（单元/构建） | WPF 队列跨实例保留、同 ID 替换、ACK 后持久删除、损坏文件隔离均通过；WPF/WinForms Release 编译通过。尚未执行真实断网重连和跨进程截图链 | `dotnet run --project tests/WindowsConfig.Tests/WindowsConfig.Tests.csproj -c Release`；`visionguard-build -Target WPF`；`visionguard-build -Target WinForms` |
| Android 接收端控制完成语义 | 已自动验证（单元/构建） | 只把字段完整的 `completed` 回执映射为结构化结果，缺失阶段的旧载荷被拒绝；冷却档位保留设备当前值（不在预设档位时并入选项，未修改不产生变更）、缺少 `maxSources`/`sourceLimitExceeded` 的旧记录按默认值解析；Debug 单测与 APK 构建通过 | `receiver/android/gradlew.bat testDebugUnitTest assembleDebug` |
| WPF → 隔离 Server → Android 接收端报警链 | 已自动验证（确定性传输链） | `WpfAlertChain.Probe` 复用生产 WPF `ServerPushService` 发送 `person` 报警与真实 PNG；Server 完成落盘、ACK、广播和截图备份，Android API 36 模拟器认证隔离通道、收到报警并缓存同一 `alertId` 截图，界面可检索到探针报警。修复发件箱按通道分区后复测只新增一条记录。该测试与同轮四窗口人员推理分别通过，但尚未把推理触发与传输探针合成一个进程内测试 | `artifacts/e2e/20260912-234853/summary.json`；`tests/WpfAlertChain.Probe/`；隔离数据 `.local/e2e-server/vnext-e2e/alerts.json`；模拟器日志中的 `6c5dc71a-7c83-4cb5-ae57-0ff82ee89c35` |
| ServerBuild | 已自动验证 | TypeScript 编译成功且 `server/dist/index.js` 存在；不是 HTTP/WS 运行 E2E | `artifacts/e2e/20260910-063530/summary.json`、`server-build.txt` |
| WPF 四路真实窗口人员推理 | 已自动验证（100% 与 200%） | 200% 缩放下以 DPI-unaware net472 窗口工具承载 6 个独立窗口，候选客户区为 1280×720 而目标自绘为 640×360；规范化后 WPF DirectML 每路约 3 FPS 且每帧命中 `person`（60/60、87/87、92/92、98/98、103/103、70/70）。无黑屏或非预期采集错误，窗口子区域裁剪、移动缩放、遮挡、最小化恢复、关闭隔离和停止/重配隔离全部通过；100% 同链路回归也通过 | `artifacts/e2e/wpf-window-person-detection-client-plane-200pct.json`；`artifacts/e2e/wpf-window-person-detection-client-plane-pass.json`；`visionguard-build -Target Windows` |
| WPF 三路人员图片推理 | 已自动验证（历史记录） | 三张含人图片每路至少一帧 `person`，并通过停止隔离、配置隔离和 DirectML 失败回退；该轮断言的是当时生效的“CPU 多路拒绝”边界，该边界已于 2026-09-13 撤销（见路线图 8.12） | `artifacts/e2e/20260910-063535/summary.json`、`artifacts/e2e/20260910-063535/wpf-person-detection.json` |
| WPF DirectML/CPU 负样本对照 | 已自动验证（有限范围） | 当前界面截图样本在两后端均无有效检测；不证明人员召回或代表性精度 | `artifacts/v3/wpf-directml-cpu-parity.json` |
| WPF 现代化主界面 | 已自动验证（100% 与 200%）/待 owner 目检 | 100% 下 Release 程序及三个控制台页面的运行、交互与布局验证通过。2026-09-15 在 200% 首次复验时曾因自定义 DPI 基线初始化和模态框重入持续弹出“调度程序处理已暂停，但仍在处理消息”；删除启动 DPI 基线、停止/重启策略并修复窗口关闭递归后，重新以 owner 可见的真实 Release 主程序验证：只有一个 `VisionGuard` 顶层窗口，切换“运行环境/当前来源”后仍响应，正常关闭在 10 秒内退出，应用日志无 `.NET Runtime`、Application Error 或 WER 事件 | `artifacts/e2e/20260915-225349/wpf-real-window-200pct.png`；历史 100% 证据 `artifacts/e2e/wpf-density-source.png`、`artifacts/e2e/wpf-density-environment.png`、`artifacts/e2e/wpf-density-connection-final.png` |
| 来源术语与设置键迁移 | 已自动验证（单元 + 两端真实启动） | 三端统一使用“来源”：WPF 卡片与标签页显示“来源 N”“当前来源”，顶栏出现“新增来源/删除来源”（只有一个来源时删除不可用），默认 1 个来源；旧设置键 `Signal.*` 一次性迁移到 `Source.*` 且旧键保留作备份，迁移的补写、幂等与数量键优先级由 `WindowsConfig.Tests` 断言；用旧键种子启动 WinForms 隔离配置，确认来源名、来源身份与冷却值全部迁移且旧键保留。`sourceId` 取值未改，避免接收端历史与静音偏好换主。来源数量另经交互验证：对未声明上限的服务端连续点“新增来源”，UI 计数从 1 增至 9 并落盘 `Source.Indexes`，不再卡在 4 路（此前 WPF 会把缺失的 `maxSources` 当成默认 4 路）| `artifacts/e2e/verify-source-settings-migration.ps1`；`artifacts/e2e/verify-wpf-source-count.ps1`；`artifacts/e2e/wpf-source-naming.png`；`dotnet run --project tests/WindowsConfig.Tests/WindowsConfig.Tests.csproj -c Release` |
| WPF 采集尺寸与遮罩窗口约束 | 已自动验证（边界、运行枚举与实际拖拽）/待人工目检（遮罩布局） | 统一断言宽高分别严格大于 100 物理像素：`100×101`、`101×100` 和 `99×500` 均拒绝，`101×101` 接受；200% DPI 映射断言覆盖 `50 DIP → 100 px` 拒绝和 `50.5 DIP → 101 px` 接受。Win32 创建并调整为 `100×150` 物理像素的可见测试窗口未出现在实际枚举结果中；在 Release 程序中用系统鼠标实际拖出 `93×93 px` 屏幕选区后，选择器保持打开并显示“选区过小”，未接受配置。窗口选择确认、区域选择、配置就绪、启动、屏幕截图和窗口截图均复用相同边界；遮罩窗口声明 520×360 逻辑像素最小尺寸并在构造时钳制 | `dotnet run --project tests/WindowsConfig.Tests/WindowsConfig.Tests.csproj -c Release`；`visionguard-build -Target WPF`；Windows UI Automation + Win32 鼠标输入；`CaptureSizeConstraints` DPI 映射及边界断言；`MaskEditorWindow.xaml` 最小尺寸 |
| WPF 窗口重绑与故障隔离 | 已自动验证（Windows 单侧）/待真实 GPU 设备故障 | 窗口恢复规则覆盖精确身份命中、唯一稳定身份下标题变化重绑、同身份多候选拒绝和旧配置同名窗口拒绝；实际纯黑 WPF 窗口产生明确黑屏异常。四窗口运行测试覆盖移动缩放、遮挡、最小化故障隔离及恢复、关闭隔离；受控 DirectML 推理引擎在真实捕获/预处理定时链路中抛错后，故障保留在对应来源、其余来源继续运行（此前的“四路统一停机”边界已于 2026-09-14 撤销）。尚未模拟显卡驱动重置或物理设备丢失 | `dotnet run --project tests/WindowsConfig.Tests/WindowsConfig.Tests.csproj -c Release`；`artifacts/e2e/20260914-113036/wpf-person-detection.json`；`visionguard-build -Target WPF` |
| Windows 驻留程序 | 已自动验证（框架、构建与隔离链路）/待 Win7 实机 | 驻留已迁移到 .NET Framework 4.7.2 x64，Release 目录不再依赖 .NET 9；维护脚本构建为 0 警告/0 错误，进程命令返回码可测。独立 `resident-win7-e2e` 通道验证驻留认证、组件心跳、Server 生命周期命令转发及带 `requestId` 的完成回执；测试使用不存在的 WPF 路径断言失败原因，未干扰当前人工验收窗口。Win7 SP1 x64 上的实际启动、WSS 证书链、登录重启和网络恢复仍待目标系统 | `visionguard-build -Target WindowsResident`；`scripts/test-windows-resident.js`；`detector/windows-resident/bin/Release/net472/`；隔离 Server 运行日志 |
| Win7 SP1 x64 前置环境 | 已自动验证 | 目标系统确认为 Windows 7 SP1 x64；KB4490628、KB4474419、KB3140245、WinHTTP 32/64 位 TLS 1.2（值均为 2560）、KB4019990 与 .NET Framework 4.7.2（Release 461814）全部通过机器判定，应用运行验收前置条件已满足 | `scripts/check-win7-prerequisites.ps1`；`artifacts/e2e/win7-prerequisites.json`（2026-09-14T04:27:59Z） |
| WinForms 多来源主体 | 主体已自动验证（100% 与 200%）/待 Win7 实机 | 当前源码 Release 构建通过；200% 缩放下 DPI-unaware net472 夹具的 6 路 YOLOv5 CPU 推理每路均命中 `person`、无黑屏错误，约 2.91–2.94 FPS，窗口子区域裁剪尺寸断言、停止隔离和容量提示通过；100% 同链路也通过。此前把夹具改成 DPI-aware 只能绕过问题，当前夹具已恢复 DPI-unaware，并把生产捕获统一到目标客户区实际自绘范围。Win7 实机仍待验收 | `artifacts/e2e/20260915-223356/winforms-person-detection.json`；`artifacts/e2e/20260915-222848/winforms-person-detection.json`；`visionguard-e2e -Mode WinFormsPersonDetection -WinFormsSourceCount 6 -WinFormsModelPath <model-path>` |
| 设备级命令语义（全部来源） | 已自动验证（隔离 Server + 真实 WinForms） | 不带 `targetSourceId` 的 `pause` 由 Server 中继到检测端后被回执为“已向全部来源下发停止，结果见各来源状态”，回执不带来源标识；同一轮带 `targetSourceId` 的 `resume` 回执精确回写该来源（并以“未选择捕获区域”如实失败）。这取代了此前“设备级命令被静默落到第一路”的行为 | `scripts/test-device-level-command.js`；`artifacts/e2e/20260915-device-level-command.json` |
| Windows 宿主机约束与显示缩放策略 | 已自动验证（本次运行） | `WindowsConfig.Tests` 覆盖采集尺寸边界、100%/125%/150%/175%/200% 的 DIP→采集像素换算、黑屏判定、窗口重绑歧义、逐路故障与容量提示、报警发件箱及跨端设置合并；WPF 的自定义启动 DPI 基线与“变化后停止并重启”策略已删除，界面交由 .NET 9/WPF PerMonitorV2，采集几何独立按实际重绘范围处理。`WinFormsMultiSource.Tests` 覆盖协商上限只拒绝新增注册、逐路启停/配置/模型隔离与容量提示。该模式只跑宿主机约束，不涉及设备、真机采集或报警链 | `visionguard-e2e -Mode WindowsTests`；`artifacts/e2e/20260915-225619/windows-config-tests.txt`；`artifacts/e2e/20260915-225619/winforms-multi-source-tests.txt` |
| WinForms 来源协商与上限语义 | 已自动验证（隔离 Server） | 隔离 Server 以 `MAX_SOURCES_PER_DETECTOR=6` 启动时，生产 WinForms 端经真实心跳上报 6 个稳定来源 ID、逐来源检测频率与 `source-control` 能力，接收端角色按同一顺序读回；上限为 4 时同样的 6 路心跳不再发布来源，检查脚本按超时失败，证明 Server 对超限数组整组拒绝而不是静默截断 | `scripts/test-winforms-server-push.js`；`artifacts/e2e/20260914-winforms-server-push-6sources.json`；`artifacts/e2e/20260914-winforms-server-push-4sources.json`；`artifacts/e2e/20260914-winforms-server-push-overlimit.log` |
| Android 检测端/接收端启动 | 已自动验证（小米 15） | 指定 `7d3584e1` 设备上的 Debug 构建、安装、清数据、运行时权限、Activity、前台服务和观测窗口无崩溃；不证明完整告警链 | `artifacts/e2e/20260910-105355/summary.json` |
| Android 接收端模拟器启动 | 已自动验证（本次运行） | `VisionGuard_API36` 上 Debug 同签名覆盖安装、运行权限、Activity/进程状态和观测窗口无崩溃；Release APK 已单独构建，因模拟器已有不同签名包而未覆盖安装，未清除应用数据 | `artifacts/e2e/20260911-065633/summary.json`、`artifacts/e2e/20260911-065620/summary.json` |
| Android 两端服务器认证与 MI 6X 运行 | 历史记录（检测端部分已失效） | 2026-09-11 在 MI 6X 上检测端重新编译、安装、启动并记录 `WS 认证成功`，界面显示已连接；接收端改为严格接收当前协议后复测认证成功、丢弃 3 条旧记录。**2026-09-15 复核：该检测端证据早于 `channel` 通道闸门（`ddb06fc`），当前 Server 强制校验 `channel`，而 Android 检测端不发送该字段，因此本端在当前协议下无法认证；按路线图决策 19，Android 检测端已暂缓，此项不再代表当前实现状态。** 接收端本身带 `channel`，认证路径不受影响 | `artifacts/e2e/20260911-001601/summary.json`、`artifacts/e2e/20260911-003309/summary.json`、`artifacts/e2e/20260911-004712/summary.json`、对应 `android-*-logcat.txt` |
| Android NNAPI 实际推理 | 已自动验证（局部） | 归一化 `yolo26n_320` 在小米 15 上完成 CameraX → 预处理 → 真实推理，profile 记录 `NnapiExecutionProvider`；MI 6X 上同一 provider 实际落到 `nnapi-reference`，约 `471–499 ms/次`，慢于原始模型 CPU 回退的 `157–179 ms/次`。provider 命中不再直接写成硬件执行确认，尚未证明 GPU/NPU/DSP、全图下沉、长期稳定性或精度 | `artifacts/e2e/20260910-111229/android-nnapi-evidence-summary.json`、`artifacts/e2e/20260910-231809-mi6x-nnapi/summary.json`、`artifacts/v4/wpf-person-slicefix.json` |
| Android 设备环境发现 | 已自动验证（历史快照） | 小米 15 `7d3584e1` 曾以 ADB `device` 连接，SoC 为 `SM8750`、Android API 36；MI 6X `1fdbd0b1` 用于本轮 NNAPI、检测端和接收端验证。该记录不表示两台设备现在仍连接 | `artifacts/e2e/20260910-102912/inventory.json`、`artifacts/e2e/20260910-231809-mi6x-nnapi/summary.json`、`artifacts/e2e/20260911-004712/summary.json` |

## 当前实现与未来能力边界

- 当前 Server 维护 WS 认证、心跳、告警广播、截图/更新路由和连接在线状态；`online=false` 不等于 `DeviceOfflineAlert` 已生成或送达。
- `DeviceOfflineAlert`、权威多租户事件存储、逐接收端 inbox/ACK、独立 Web Management Console、Linux Edge Detector、多传感器融合和 Qualcomm QNN/NCNN 加速仍属于路线图范围；Windows 检测端到 Server 的持久 outbox/幂等 ACK 已实现，但不等于接收端离线可靠投递完成。
- WPF 四个静态图片窗口 smoke 证明真实 `WindowHandle` 捕获和人员推理，但不证明动态业务视频、报警截图、中继链路、长时稳定性或 UI 外观。
- Android 启动 smoke 不证明摄像头推理、模型下载、Server 连接或接收端展示的完整链路。
- Release 构建不等于正式发行包已通过；正式发行还需要签名、ZIP 清洁度、元数据、上传/部署和公网验证。

## 待人工、真机或生产验证

1. Windows 四路现代化 UI 的视觉层级、品牌配色和高 DPI 表现仍待 owner 目检；WinForms 三个标签页的职责拆分已留下截图与控件树（`artifacts/e2e/winforms-ui-check/`），能否接受仍由 owner 判断。动态窗口移动缩放、遮挡、关闭、最小化故障隔离及恢复已自动验证。
2. Android 检测端与接收端的真机 UI、归一化模型正式下载、摄像头长时运行、网络切换和温升/资源表现：仍待真机验收。
3. WPF 生产传输服务 → Server → Android 接收端的报警、截图归属和 ACK 已在隔离通道自动贯通；仍需补推理自然触发到传输的单进程测试、真实断网恢复、接收端离线补发和真机通知验收。
4. Win7 SP1 x64 下 Windows 驻留程序与 WinForms 的安装、TLS/WSS、进程握手、网络/休眠恢复和退出清理：待目标环境；驻留的框架/API 迁移已实现，但目标系统验收仍是路线图硬门槛。
5. 生产 VPS、正式发行 ZIP/APK、发布回滚和公网更新接口：本次治理未执行。

历史验证记录必须保留原始证据路径，并在重新运行后更新状态；不要仅因日期较新就把历史局部证据升级为完整验收。
