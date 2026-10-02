# Operations

本文只维护可执行入口、授权边界和验证分类。模块事实见对应专题，验证结果见[验证报告](90-verification-report.md)，不在此复制完整报告。

## 内测迭代

测试默认使用当前版本的全套组件和当前配置、数据格式。协议或结构变更时同步相关端，按需重新配置或重建测试环境；不默认验证旧版本混用、增加数据迁移或保留旧入口。兼容需求只按 owner 明确指定的版本或数据范围执行，规则见 [AGENTS.md](../../AGENTS.md#内测开发与迭代原则)。

## 构建

仓库组件的统一构建入口（客户端 Release、服务含 Web）：

```powershell
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-build\scripts\build-all.ps1 -Target All
```

可用目标为 `Server`、`WPF`、`WindowsResident`、`AndroidDetector`、`AndroidReceiver`、`AndroidNotifier`，也可使用组合目标 `Windows`、`Android`（包含通知节点）。脚本只编译并检查产物，不打包、不上传、不部署、不改版本。

视觉驻留的生命周期命令以源码为准：`open-detector`、`close-detector`（驻留由检测端拉起并自行存活）。

构建结果必须按组件分别报告，并在完成后检查：

- 视觉中继及 Web：`server/dist/index.js`、`server/dist/console/index.html`；先分别运行 `npm --prefix server ci` 与 `npm --prefix receiver/web ci`，需要 Node.js 22.18 或以上
- VisionGuard 视觉节点统一入口：`detector/windows-package/bin/Release/VisionGuard.Detector.Windows.exe`；内部运行时位于 `runtimes/modern/` 与 `runtimes/legacy/`，各自只保留匹配的 `native\modern\`、`native\legacy\`，包根目录不得残留 `onnxruntime.dll` 或 `DirectML.dll`
- 视觉驻留：`detector/windows-resident/bin/Release/net472/VisionGuard.Resident.Windows.exe`
- 视觉检测（Android）：`detector/android/app/build/outputs/apk/release/app-release.apk`
- VisionGuard 控制台：`receiver/android/app/build/outputs/apk/release/app-release.apk`
- VisionGuard 通知节点：`notifier/android/app/build/outputs/apk/release/app-release.apk`

Windows 发行输出不得包含 `.pdb`、`.lib`、`.dll.config`、`.onnx`、`Assets/` 或 `alerts/`；模型按需下载，不随发行包分发。详细模型与项目文件边界见[模型资源](35-model-assets.md)。

## 应用名称与安装身份

名称及工程/包名的对应关系见[项目概览](10-project-overview.md#组件名称与技术标识)。手机桌面、系统应用详情和通知使用“视觉检测”或“VisionGuard 控制台”，不添加平台前缀。

当前 Android 包名为 `com.xgwnje.visionguard.detector` 和 `com.xgwnje.visionguard.receiver`。包名决定安装身份；首次使用当前源码构建包需作为新应用安装并重新配置服务连接与权限，不能用它覆盖安装其他包名的应用。两端继续使用被忽略的共享签名配置，改包名不要求更换签名密钥。

Windows 当前构建从统一目录根启动 `VisionGuard.Detector.Windows.exe`，视觉驻留程序为 `VisionGuard.Resident.Windows.exe`。复制完整目录，再将桌面快捷方式指向该入口；已发布包的实际文件以对应发布说明为准。

## 运行与设备验证

### 节点登记

每个节点分别登记角色、节点类型、平台与固定设备 ID；检测端和驻留即使使用同一设备 ID，也必须使用不同凭据。登记入口只输出文件位置，不输出密钥：

```powershell
node scripts/provision-node.js visual-test detector visual windows
node scripts/provision-node.js visual-test lifecycle resident windows
node scripts/provision-node.js console-test console console web
node scripts/provision-node.js notifier-test notifier notification android
```

登记表位于被忽略的 `.local/node-identities.json`，单节点配置位于 `.local/nodes/`。为客户端注入对应文件中的凭据和设备 ID；服务设置 `VISIONGUARD_IDENTITIES_FILE` 为登记表绝对路径，并在新增、撤销或替换登记后重启。共享 HTTP 管理密钥不授予 WS 角色。登记文件、客户端配置与构建注入值不得提交或打印到日志。当前不提供后台登记界面；隔离测试控制台可自动分配临时身份。

### Web 与通知节点配置

服务完成 Server 构建后，从同一服务的 `/console/` 打开 Web 控制台；输入通道、登记的 Web 节点 ID 与凭据。外部使用 HTTPS，回环本地测试可使用 HTTP。Web 产物包含在服务 `dist` 中，现有服务包无需另建静态站点。仅本地构建不表示公网入口已部署。

局域网免填写验收：仅在隔离服务设置 `VISIONGUARD_TEST_CONSOLE_AUTOLOGIN=true`、独立 `VISIONGUARD_DATA_DIR` 和 `BIND_HOST=0.0.0.0`；防火墙仅放行测试端口到本地子网。其他设备打开 `http://<本机局域网 IPv4>:<PORT>/console/` 即自动登录；不同页面使用不同临时身份，凭据不写入浏览器存储或登记文件，服务重启后刷新页面可重新获得身份。测试会话有效期 24 小时，最多保留 512 个未过期身份。结束时停止隔离服务并移除本次防火墙规则；正常服务保持默认关闭。

统一告警时间：Web「设置 → 统一时间」选择北京时间或 UTC 并保存。标准保存在当前服务的数据目录，所有在线 Web 和通知节点接收同一设置；新连接与重连获取当前标准。通知节点持久化最近标准，切换后既有告警按新时区重绘。服务参考时间来自服务进程；该功能统一显示时区，不修改设备系统时钟。

通知节点在「接警 → 连接设置」输入 WSS `/ws` 地址、通道、登记的通知节点 ID、名称和凭据，保存后开启通知节点。声音在接警页与铃声库设置。Web「通知范围」选择通知节点，保存全部或指定检测节点/来源，观察通知节点范围更新。指定空集合不接收服务事件，仍保留本地服务中断监测。

可复现的模拟器联动测试使用 `server/test/notification-node-fixture.cjs`：先构建服务，在可见平板模拟器上运行。fixture 仅绑定回环 4318/4319，随机登记及证据写入被忽略的 `.local/group2-verification/`，模拟检测节点，不替代真实 WPF 采集推理。该 fixture 模拟检测节点；新通知节点的实机与完整链路验收须另行安排，不能沿用拆分前 Vigil 的 UI 结论。结束后关闭测试进程和可见模拟器。

### 隔离运行

端到端测试必须使用独立视觉中继进程，与生产通道隔离。准备独立测试登记表，并在当前 PowerShell 进程设置环境变量，再以前台方式启动：

```powershell
$env:VISIONGUARD_IDENTITIES_FILE = '<absolute-private-test-registry-path>'
$env:VISIONGUARD_CONSOLE_API_KEY = '<registered-console-key>'
powershell -ExecutionPolicy Bypass -File .\scripts\start-isolated-test-server.ps1 -Port 3100 -Channel vnext-e2e
```

该入口把数据写入被忽略的 `.local/e2e-server/<channel>/`。VisionGuard 视觉节点和驻留进程使用 `VISIONGUARD_SERVER_URL=http://127.0.0.1:3100` 与同名 `VISIONGUARD_CHANNEL`；模拟器接收端构建使用 `VISIONGUARD_SERVER_URL=http://10.0.2.2:3100`。通道不一致必须认证失败，不能回退到旧协议或公共广播域。

驻留程序完成 Release 构建且隔离视觉中继已启动后，可验证认证、心跳和带请求关联的生命周期失败回执：

该脚本固定使用 `resident-win7-smoke/lifecycle/resident/windows`，控制台登记平台必须为 `test`。用独立测试登记表配置这些身份，勿与已运行的驻留共用身份。

```powershell
$env:VISIONGUARD_SERVER_URL = 'http://127.0.0.1:3100'
$env:VISIONGUARD_CHANNEL = 'vnext-e2e'
$env:VISIONGUARD_RESIDENT_API_KEY = '<registered-resident-key>'
$env:VISIONGUARD_CONSOLE_API_KEY = '<registered-console-key>'
$env:VISIONGUARD_CONSOLE_DEVICE_ID = '<registered-test-console-id>'
node .\scripts\test-windows-resident.js
```


确定性 WPF 报警传输探针复用生产 `ServerPushService`，必须在独立视觉中继与接收端已连接后运行：

```powershell
$env:VISIONGUARD_CHANNEL = 'vnext-e2e'
$env:VISIONGUARD_DETECTOR_API_KEY = '<registered-wpf-alert-chain-probe-key>'
dotnet run --project .\tests\WpfAlertChain.Probe\WpfAlertChain.Probe.csproj -c Release -- http://127.0.0.1:3100
```

运行验证入口：

```powershell
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode Discover
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode ServerBuild
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode WpfPersonDetection
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode WpfParserContract
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode ResidentLaunch
```


Android 运行 smoke 使用 `-Mode AndroidDetectorSmoke` 或 `-Mode AndroidReceiverSmoke`；默认使用 Debug 构建，只有发行验证才使用 `-BuildType Release`。设备选择顺序是已连接且状态为 `device` 的真机，再是可用模拟器；多台真机必须用 `-DeviceSerial` 指定。

结果分类不能合并：

- `ServerBuild` 只验证 TypeScript 编译和 `server/dist/index.js` 产物；兼容别名 `ServerSmoke` 也只做同一件事，不是 HTTP/WS 运行测试。
- Android 启动 smoke 只验证安装、启动、前台服务/进程状态和观测窗口内无崩溃，不验证检测端→视觉中继→接收端报警链。
- `WpfPersonDetection` 按 `-WpfSourceCount`（默认 4）由脚本内的 `Start-WpfFixtureWindows` 创建可见 WinForms 静态图片窗口，经 `WindowHandle` 捕获后逐路验证 `person`、FPS 和来源隔离；夹具图片数量不得少于来源数。它是类库推理补充证据，不能替代真实 WPF 主程序、动态视频、报警链或 UI 目检。
- 纯计算契约（不开窗口、不建推理会话）：`CardLayoutPlan` 驱动 `CardLayoutPlanner`，断言卡片比例夹紧、1/2/4 张网格排得下、画面短边回归线与退化输入；`PerformanceWatchdog` 驱动 `PerformanceWatchdog`，断言性能提示的判定边界（实测 < 目标×80%）、30 秒持续时间、提示文案与三个口径常量。两者都只证明数学与口径，不证明真实界面观感或实机帧率。
- `WpfParserContract` 按推理档位各构建一次 `tests/WpfInference.Benchmark` 并对真实图片断言输出形态、形态与档位一致、未知长度被拒、原生 ONNX Runtime 来自档位目录、解析出预期业务目标（默认 `person`，置信度 ≥0.5）。不打开窗口、不依赖 GPU，只证明「模型 → 解析 → 检测框」。
- `ResidentLaunch` 自动生成独立测试登记表和逐角色凭据；它按 `-ResidentPort`（默认 3123，端口被占用会直接报错而不复用旧实例）启动隔离视觉中继，对两个档位分别断言「检测端拉起同目录驻留 → 驻留在超时内进入单实例握手 → 服务端 device-list 报 `components.resident = running`」。它只会结束自己拉起的驻留（按驻留配置路径匹配），不使用真实 settings。不覆盖远程 `open-detector`/`close-detector` 实际动作、登录自启与重启恢复。
- 真实窗口采集、真机 UI、完整报警链、持续运行和故障恢复分别记录为人工/真机/完整 E2E 结果。
- 视觉驻留使用 .NET Framework 4.7.2 x64；构建和隔离链路 smoke 仍不能替代 Win7 SP1 x64 目标环境中的启动、WSS、登录重启和网络恢复验收。
- Win7 SP1 x64 前置环境可在虚拟机共享目录中双击 `scripts/run-win7-prerequisites.cmd` 核验；它调用 `scripts/check-win7-prerequisites.ps1` 检查系统版本、KB4490628、KB4474419、KB3140245、WinHTTP TLS 1.2 注册表、KB4019990 与 .NET Framework 4.7.2，并把机器可读结果写入 `artifacts/e2e/win7-prerequisites.json`。共享目录必须临时允许虚拟机写入该报告，验收后可恢复只读。

证据写入 `artifacts/e2e/<timestamp>/`。不要为了本地验证清除应用数据，除非任务明确要求；脚本启动的模拟器必须在结束时关闭。未明确要求时不操作生产服务。

## 发布授权

正式发布唯一入口：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\publish-release.ps1 -Version <version> -Target All -UploadVps
```

该命令会同步版本、构建、准备签名包、更新 release 元数据、按目标上传并可部署视觉中继；每一步都需要明确发布授权。仅检查前置条件时使用 `-PreflightOnly`，明确只发客户端时使用 `-SkipServerDeploy`。GitHub push、tag 和 Release 仍需显式开关。

发布前必须确认 Android 签名材料、Windows ZIP 清洁度、元数据大小和目标范围；发布后才可执行公网 `/health`、`/api/update`、`HEAD 200` 和 byte-range `206` 验证。发布脚本是唯一的正式打包/部署实现。

**分端上线（某端本次不发布）**：`-Target` 是单值，要发多个端就按端分批跑（例如 `-Target Windows -UploadVps`、`-Target AndroidReceiver -UploadVps`、`-Target Server -UploadVps`）。未发布的端在 `server/data/releases.json` 的**该平台条目内部**标 `"heldBack": true` 并保持上一个已发布版本，使更新接口继续返回旧版本而不是指向不存在的文件；`sync-version.js` 与 `publish-release.ps1` 会保留并跳过该条目。`check-docs`、`publish-release.ps1` 的 GitHub-only 资产收集与 `.github/workflows/release.yml` 都会跳过 heldBack 平台并给出提示；但 GitHub Release 两条入口在「一个平台都没对齐目标版本」时仍会失败，避免版本号打错时发出空 Release。

## 配置与服务边界

- 视觉中继真实密钥使用 `server/.env` 或部署环境变量，不能提交。
- VisionGuard 视觉节点使用 `VISIONGUARD_DETECTOR_API_KEY`，视觉驻留使用 `VISIONGUARD_RESIDENT_API_KEY`；设备 ID 通过 `VISIONGUARD_DEVICE_ID` 固定为登记值。`VISIONGUARD_SETTINGS_PATH` 可指向隔离的绝对配置路径；普通运行仍使用 `%APPDATA%\VisionGuard\settings.ini`。Android 控制台、视觉检测分别由 Gradle 读取 `VISIONGUARD_CONSOLE_API_KEY`、`VISIONGUARD_DETECTOR_API_KEY`，并注入设备 ID、服务地址和通道。
- 本地私密配置集中保存在被 Git 忽略的 `.local/`；其中 `visionguard-release.env` 保存 API Key 与 Android 签名参数，`visionguard-android-release.p12` 保存签名证书。Android 两端优先读取各自 `local.properties`，缺失时回退到这份集中配置。迁移工作区时必须额外复制 `.local/`、两个 Android `local.properties` 和 `server/.env`；仅执行 `git clone` 无法恢复这些文件。
- VisionGuard 正式域名：`https://visionguard.xgwnje.cn`；根域 `https://xgwnje.cn` 不是新客户端服务地址。
- 当前 VPS/DNS/SNI 事实由 `Server-infra` 项目维护；不要运行旧式 `server/deploy.sh --nginx` 覆盖现有 SNI 架构。
- `VERSION` 只由 owner 明确授权的版本流程修改；普通构建、测试、修复和文档治理不得改动它。

## 文档审核

```powershell
node scripts/check-docs.js
node --test scripts/check-docs.test.js scripts/release-workflow.test.js scripts/check-powershell-encoding.test.js
```

审核会检查 Markdown 链接和锚点、UTF-8 无 BOM、版本来源、组件入口、四个 WS 角色、模型打包边界、Skill 与脚本契约、现有组件命名与产品边界和旧入口不存在。新增或删除文档/Skill/入口后必须先更新对应唯一来源，再运行审核。

`.github/workflows/docs-audit.yml` 在 PR 与 `main` 推送时运行上述静态检查；每周一 03:17 UTC（北京时间 11:17）及手动触发时，额外运行 `node scripts/check-online-release-contract.js`。该巡检请求正式服务的 `/health`、各平台 `/api/update`、发行包 `HEAD` 与 Range 下载，并将返回的版本、URL、大小与 `server/data/releases.json` 对照；它用于发现仓库发布元数据和线上实际服务的漂移，不替代正式发布后的人工验收。
