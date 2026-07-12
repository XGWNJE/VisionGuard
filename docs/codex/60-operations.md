# Operations

这个文件只记录对协作有用、且已经从仓库确认过的操作约束。

## 构建入口

- Server：`cd server && npm ci && npm run build`
- WinForms：打开 `detector/windows-winforms/VisionGuard.slnx`
- WPF：打开 `detector/windows-wpf/VisionGuard.sln`
- Android Detector：使用 `detector/android/`
- Android Receiver：使用 `receiver/android/`

## 发布边界

- 未经明确要求，不改 `VERSION`
- 未经明确要求，不运行 `scripts/sync-version.js`
- 未经明确要求，不运行 `scripts/release.js`
- 未经明确要求，不运行 `scripts/bump-version.sh`
- 正式上线入口：`powershell -ExecutionPolicy Bypass -File .\scripts\publish-release.ps1 -Version <version> -Target All -UploadVps`
- 只检查发布环境：在正式上线命令后追加 `-PreflightOnly`，确认通过后再去掉该开关执行上线。
- 发布脚本必须先预检再同步版本：Server-infra env/Paramiko、WinForms `packages.config` restore、Android Java/build-tools/签名、远端路径都要在长构建前失败。
- `Target All` 或 `Target Server` 搭配 `-UploadVps` 时会部署 Server 代码到 `/opt/visionguard-server`；明确只发客户端时才加 `-SkipServerDeploy`。
- 上线验证顺序是上传 release 文件、部署 Server、再查公网 `/health`、`/api/update`、`HEAD 200` 和 `Range 206`，避免验证命中旧 Server。

## 本地敏感配置

- Server 真实密钥放 `server/.env` 或部署环境变量，不提交。
- Windows 两端优先从环境变量 `VISIONGUARD_API_KEY` 读取 API key，发行包保留兼容兜底，避免未配置环境变量的旧安装断联。
- Android 两端从 Gradle 注入 `BuildConfig.API_KEY`；本地可在各自 `local.properties` 写 `VISIONGUARD_API_KEY=...`，也可用 Gradle property 或环境变量。
- Android 两端共用仓库根目录 `.local/visionguard-android-release.p12` 与 `.local/visionguard-release.env`；PKCS12 使用 4096 位 RSA 发布密钥，`.local/` 整目录被 Git 忽略，密码不得写入跟踪文件或命令行。
- 首次初始化签名：`powershell -ExecutionPolicy Bypass -File .\scripts\initialize-android-signing.ps1`。只有明确接受旧安装无法覆盖升级时才使用 `-Rotate` 轮换签名；脚本会在 `.local/` 中保留旧材料备份。
- Android Gradle Release 与 `publish-release.ps1` 都读取同一份共享签名配置；环境变量仍可作为自动化环境的高优先级覆盖。签名预检可用 `publish-release.ps1 -Version <当前版本> -Target Android -PreflightOnly`，不会同步版本或发布。
- 常规 `assembleRelease`、`build`、`visionguard-build -Target Android` 默认强制签名；签名材料缺失时在打包前失败，不允许静默产出 unsigned 包。

## 服务域名边界

- VisionGuard 正式域名：`https://visionguard.xgwnje.cn`
- 个人主页根域：`https://xgwnje.cn`
- 不要把新客户端配置回根域或泛用 `api.xgwnje.cn`
- 当前 VPS 由 `D:\ObjectCode\Server-infra` 维护公共 DNS、端口和 Nginx SNI 结构。
- 当前线上路径为公网 `443` -> Nginx stream SNI -> `127.0.0.1:9443` -> `127.0.0.1:3000`。
- 不要直接运行旧 `server/deploy.sh --nginx` 覆盖当前 VPS 的 SNI/9443 架构。

## 线上状态

- 2026-06-29 已切换 `visionguard.xgwnje.cn` 到新 VPS `212.135.41.88` 的 VisionGuard Node 服务。
- 公网 `/health`、更新接口、发行包下载、模型下载和 `/ws` 已通过 smoke 验证。
- Android 接收端实机 UI 已显示可连接，后续继续观察真实告警链路。
- 当前结论是连接和分发链路可用，不等同于完整端到端报警验证。

## 建议验证顺序

1. 先确认影响范围
2. 再改最小文件集
3. 最后补构建/测试

## E2E / Device Smoke

- 端到端、模拟器、实机、logcat、桌面交互验证入口：`.agents/skills/visionguard-e2e/`
- 快速环境发现：`powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode Discover`
- Android 自动化默认顺序：已授权真机 > `VisionGuard_API36` > `Pixel_3a_XL` > 仅构建/Server smoke
- 证据目录：`artifacts/e2e/<timestamp>/`
- 未明确要求时，不打正式 VPS，不改版本号，不发布，不部署

## 易错点

- `server/` 和 Android 端协议耦合很强
- 遮罩修改会同时影响识别和截图
- Android 前台服务类型不能混用
- `README.md` 可能存在编码问题，不应作为单一真相
- 项目内旧解释文档已迁移后删除，后续不要恢复双份维护
- 根域存在旧客户端兼容入口，不代表根域仍是 VisionGuard 正式服务地址
- 发行包冗余文件必须从 .csproj/gradle 根源解决，不要在 release.js 中事后删除
- 修改构建配置或删除文件前，先 `grep` 确认无代码引用
- 编译通过后必须 `Get-ChildItem` 检查输出目录，确保无多余文件
- 模型文件不打包进发行包，发布时由 `scripts/publish-release.ps1` 收集到 `server/data/models/`
- Android Release 验证必须用 `assembleRelease`（不是 Debug）；仅在明确进行编译证据验证时可加 `-PVISIONGUARD_ALLOW_UNSIGNED_RELEASE=true` 生成 unsigned APK，该产物禁止发布或交付
- 不要把 `server/deploy.sh` 的本机 `/mnt/d/...` 输出误认为服务部署在 WSL；真正的线上运行目录必须通过 VPS 上的 `systemctl is-active visionguard`、`/opt/visionguard-server/package.json` 和公网 `/health` 验证。
