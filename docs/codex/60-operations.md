# Operations

本文只维护可执行入口、授权边界和验证分类。模块事实见对应专题，验证结果见[验证报告](90-verification-report.md)，不在此复制完整报告。

## 构建

六个组件的统一 Release 构建入口：

```powershell
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-build\scripts\build-all.ps1 -Target All
```

可用目标为 `Server`、`WinForms`、`WPF`、`WindowsResident`、`AndroidDetector`、`AndroidReceiver`，也可使用组合目标 `Windows`、`Android`。脚本只编译并检查产物，不打包、不上传、不部署、不改版本。

Windows 驻留程序的生命周期命令以源码为准：`open-wpf`、`open-winforms`、`close-wpf`、`close-winforms`。

构建结果必须按组件分别报告，并在完成后检查：

- Server：`server/dist/index.js`
- Windows WinForms 检测端：`detector/windows-winforms/bin/Release/VisionGuard.exe`
- Windows WPF 检测端：`detector/windows-wpf/bin/x64/VisionGuard.exe`
- Windows 驻留程序：`detector/windows-resident/bin/Release/net472/VisionGuard.Resident.exe`
- Android 检测端：`detector/android/app/build/outputs/apk/release/app-release.apk`
- Android 接收端：`receiver/android/app/build/outputs/apk/release/app-release.apk`

Windows 发行输出不得包含 `.pdb`、`.lib`、`.dll.config`、`.onnx`、`Assets/` 或 `alerts/`；模型按需下载，不随发行包分发。详细模型与项目文件边界见[模型资源](35-model-assets.md)。

## 运行与设备验证

新协议端到端测试必须使用独立 Server 进程，不能连接仍承载旧客户端的线上通道。先在当前 PowerShell 进程设置测试密钥，再以前台方式启动：

```powershell
$env:VISIONGUARD_API_KEY = '<local-test-key>'
powershell -ExecutionPolicy Bypass -File .\scripts\start-isolated-test-server.ps1 -Port 3100 -Channel vnext-e2e
```

该入口把数据写入被忽略的 `.local/e2e-server/<channel>/`。WPF、WinForms 和驻留进程使用 `VISIONGUARD_SERVER_URL=http://127.0.0.1:3100` 与同名 `VISIONGUARD_CHANNEL`；模拟器接收端构建使用 `VISIONGUARD_SERVER_URL=http://10.0.2.2:3100`。通道不一致必须认证失败，不能回退到旧协议或公共广播域。

驻留程序完成 Release 构建且隔离 Server 已启动后，可验证认证、心跳和带请求关联的生命周期失败回执：

```powershell
$env:VISIONGUARD_SERVER_URL = 'http://127.0.0.1:3100'
$env:VISIONGUARD_CHANNEL = 'vnext-e2e'
node .\scripts\test-windows-resident.js
```

WinForms 检测端与隔离 Server 的来源协商链路（不使用设备，不需要模拟器），先构建 WinForms Release，并让隔离 Server 以目标上限启动：

```powershell
$env:MAX_SOURCES_PER_DETECTOR = '6'
powershell -ExecutionPolicy Bypass -File .\scripts\start-isolated-test-server.ps1 -Port 3100 -Channel vnext-e2e
# 另一个终端
$env:VISIONGUARD_SERVER_URL = 'http://127.0.0.1:3100'
$env:VISIONGUARD_CHANNEL = 'vnext-e2e'
$env:VISIONGUARD_API_KEY = '<local-test-key>'
node .\scripts\test-winforms-server-push.js .\artifacts\e2e\winforms-server-push.json 6
```

脚本为隔离配置写入指定数量的来源身份并断言 Server 实际收到同样的来源 ID、逐来源检测频率和 `source-control` 能力；

设备级命令语义（不带 `targetSourceId` 的命令 = 作用于全部来源）同样需要隔离 Server：

```powershell
$env:VISIONGUARD_SERVER_URL = 'http://127.0.0.1:3100'
$env:VISIONGUARD_CHANNEL = 'vnext-e2e'
$env:VISIONGUARD_API_KEY = '<local-test-key>'
node .\scripts	est-device-level-command.js .rtifacts\e2e\winforms-device-level-command.json 2
```

脚本用隔离配置启动 WinForms 检测端，先发不带来源的 `pause`，断言回执成功、不带 `targetSourceId` 且说明作用于全部来源；再发带 `targetSourceId` 的 `resume`，断言回执精确回写该来源。
来源数量是第 3 个参数（默认 4，即回归基线）。Server 上限低于该数量时心跳的 `sources` 数组会被整组拒绝，脚本会超时失败，因此它同时是上限语义的负向检查。

确定性 WPF 报警传输探针复用生产 `ServerPushService`，必须在独立 Server 与接收端已连接后运行：

```powershell
$env:VISIONGUARD_CHANNEL = 'vnext-e2e'
dotnet run --project .\tests\WpfAlertChain.Probe\WpfAlertChain.Probe.csproj -c Release -- http://127.0.0.1:3100 '<local-test-key>'
```

运行验证入口：

```powershell
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode Discover
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode ServerBuild
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode WindowsTests
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode WpfPersonDetection
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode WinFormsPersonDetection
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode WinFormsPersonDetection -WinFormsSourceCount 6 -WinFormsDurationSeconds 300 -WinFormsModelPath <model-path>
```

Android 运行 smoke 使用 `-Mode AndroidDetectorSmoke` 或 `-Mode AndroidReceiverSmoke`；默认使用 Debug 构建，只有发行验证才使用 `-BuildType Release`。设备选择顺序是已连接且状态为 `device` 的真机，再是可用模拟器；多台真机必须用 `-DeviceSerial` 指定。

结果分类不能合并：

- `ServerBuild` 只验证 TypeScript 编译和 `server/dist/index.js` 产物；历史兼容别名 `ServerSmoke` 也只做同一件事，不是 HTTP/WS 运行测试。
- Android 启动 smoke 只验证安装、启动、前台服务/进程状态和观测窗口内无崩溃，不验证检测端→Server→接收端报警链。
- `WindowsTests` 只在宿主机跑 `tests/WindowsConfig.Tests` 与 `tests/WinFormsMultiSource.Tests` 两组机器可判定约束（采集尺寸、黑屏判定、窗口重绑、崩溃/容量策略、发件箱、设置合并、显示缩放策略与多来源协调器隔离），不验证设备、真机采集或报警链。
- `WpfPersonDetection` 按 `-WpfSourceCount`（默认 4）打开对应数量的独立可见浏览器窗口，经 `WindowHandle` 捕获，要求每路至少一帧 `person` 且实际达到 2.5 FPS，并验证来源隔离、CPU 超容量提示和后端故障边界；夹具目录人像图数量不得少于来源数量，默认目录不足时用 `-WpfFixtureDirectory` 指定，脚本会报出实际张数而不是自动降低路数。不证明动态视频、报警链或 UI 目检。**夹具捕获注意**：这套用浏览器 `--app` 图片窗口承载来源，但当前 Chrome 的 GPU 合成层不进 `PrintWindow`，实测会抓到空白暗帧（表现为采集正常、但每路 0 命中）；需要可靠夹具时改用 net472 窗口工具承载（GDI 渲染可稳定捕获），参考 `artifacts/e2e/verify-wpf-multisource.ps1`。
- `WinFormsPersonDetection` 按 `-WinFormsSourceCount`（默认 4）打开 net472 独立窗口，复用生产 WinForms 的 YOLOv5 CPU 管线验证人员命中、逐路 FPS、容量提示与停止隔离；加 `-WinFormsDurationSeconds` 后改为持续运行取证，报告逐路实测 FPS、帧间隔 P95 与停滞来源，且不设帧率硬门槛——超容量只提示性能不足。模型须已缓存或用 `-WinFormsModelPath` 指定。Win7 可双击 `scripts/run-win7-winforms-smoke.cmd` 运行同一验证。两个 Windows smoke 夹具与被测端一样在启动时确定单一 DPI；夹具进程若保持 DPI 不感知，系统会按位图拉伸窗口而 `PrintWindow` 不拉伸，捕获画面会带大面积黑边。
- 真实窗口采集、真机 UI、完整报警链、持续运行和故障恢复分别记录为人工/真机/完整 E2E 结果。
- Windows 驻留程序已经迁移到 .NET Framework 4.7.2 x64；构建和隔离链路 smoke 仍不能替代 Win7 SP1 x64 目标环境中的启动、WSS、登录重启和网络恢复验收。
- Win7 SP1 x64 前置环境可在虚拟机共享目录中双击 `scripts/run-win7-prerequisites.cmd` 核验；它调用 `scripts/check-win7-prerequisites.ps1` 检查系统版本、KB4490628、KB4474419、KB3140245、WinHTTP TLS 1.2 注册表、KB4019990 与 .NET Framework 4.7.2，并把机器可读结果写入 `artifacts/e2e/win7-prerequisites.json`。共享目录必须临时允许虚拟机写入该报告，验收后可恢复只读。

证据写入 `artifacts/e2e/<timestamp>/`。不要为了本地验证清除应用数据，除非任务明确要求；脚本启动的模拟器必须在结束时关闭。未明确要求时不操作生产服务。

## 发布授权

正式发布唯一入口：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\publish-release.ps1 -Version <version> -Target All -UploadVps
```

该命令会同步版本、构建、准备签名包、更新 release 元数据、按目标上传并可部署 Server；每一步都需要明确发布授权。仅检查前置条件时使用 `-PreflightOnly`，明确只发客户端时使用 `-SkipServerDeploy`。GitHub push、tag 和 Release 仍需显式开关。

发布前必须确认 Android 签名材料、Windows ZIP 清洁度、元数据大小和目标范围；发布后才可执行公网 `/health`、`/api/update`、`HEAD 200` 和 byte-range `206` 验证。发布脚本是唯一的正式打包/部署实现，不恢复已删除的旧发布入口。

## 配置与服务边界

- Server 真实密钥使用 `server/.env` 或部署环境变量，不能提交。
- Windows 两端优先读取 `VISIONGUARD_API_KEY`；Android 两端由 Gradle 注入 `BuildConfig.API_KEY`。
- Windows 两个检测端都可用 `VISIONGUARD_SETTINGS_PATH` 指向隔离的绝对配置路径（自动化验证互不干扰）；普通运行不设置时仍使用 `%APPDATA%\VisionGuard\settings.ini`。
- 本地私密配置集中保存在被 Git 忽略的 `.local/`；其中 `visionguard-release.env` 保存 API Key 与 Android 签名参数，`visionguard-android-release.p12` 保存签名证书。Android 两端优先读取各自 `local.properties`，缺失时回退到这份集中配置。迁移工作区时必须额外复制 `.local/`、两个 Android `local.properties` 和 `server/.env`；仅执行 `git clone` 无法恢复这些文件。
- VisionGuard 正式域名：`https://visionguard.xgwnje.cn`；根域 `https://xgwnje.cn` 不是新客户端服务地址。
- 当前 VPS/DNS/SNI 事实由 `Server-infra` 项目维护；不要运行旧式 `server/deploy.sh --nginx` 覆盖现有 SNI 架构。
- `VERSION` 只由 owner 明确授权的版本流程修改；普通构建、测试、修复和文档治理不得改动它。

## 文档审核

```powershell
node scripts/check-docs.js
node --test scripts/check-docs.test.js scripts/release-workflow.test.js
```

审核会检查 Markdown 链接和锚点、UTF-8 无 BOM、版本来源、六个组件入口、四个 WS 角色、模型打包边界、Skill 与脚本契约、路线图产品边界和旧入口不存在。新增或删除文档/Skill/入口后必须先更新对应唯一来源，再运行审核。
