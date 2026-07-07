# VisionGuard

> AI 实时监控系统。Windows 检测端 → VPS 中继 → Android 接收端报警。

## 项目布局

```
detector/windows-winforms/   C# / .NET Framework 4.7.2 / WinForms   主力线 (Win7+)
detector/windows-wpf/        C# / .NET 9 / WPF / MVVM               视觉升级线 (Win10+)
detector/android/            Kotlin / CameraX / ONNX Runtime Mobile  安卓检测端
server/                      Node.js 20+ / TypeScript / Express / ws  中继服务器
receiver/android/            Kotlin / Jetpack Compose / OkHttp       安卓接收端
```

## 架构速览

**检测端推理链**：Capture → MaskApply → Preprocess → ONNX Inference → Parse → AlertDecision → Push

**遮罩**（三端对齐）：相对坐标 `[0,1]`，推理前 Bitmap 涂黑。**同时影响推理结果与报警截图**（涂黑区域不识别、不可见）。监控运行中热更新，下个 Tick 生效。

**WinForms vs WPF 差异**：

| | WinForms | WPF |
|---|---|---|
| 框架 | .NET Framework 4.7.2 | .NET 9 |
| 模型 | YOLOv5 nu/su/mu (`[1,84,2100]`, 无内置 NMS) | YOLO26 n/s/m (`[1,300,6]`) |
| ORT | 1.1.0 统一包（Managed 1.2.0 版本暂不处理） | 1.19.0 |
| WS | websocket-sharp | System.Net.WebSockets |
| 架构 | 事件驱动 Form1 partial class | MVVM (ViewModels/) |
| 版本源 | `AssemblyInfo.cs` + `ServerPushService.cs` | `AppConfig.cs`（`const string Version`） + `.csproj`（文件属性） |
| 输出路径 | `bin\Release\`（AnyCPU 与 x64 统一） | `bin\x64\` |
| 原生 DLL | ONNX Runtime 随 NuGet 输出到根目录 | ONNX Runtime 通过 csproj Target 提取到根目录（删除 runtimes/ 嵌套） |

**Win7 TLS 兼容**（WinForms 端）：

- `Program.cs` 入口处 `AppContext.SetSwitch("Switch.System.Net.DontEnableSystemDefaultTlsVersions", true)` + `ServicePointManager.SecurityProtocol \|= Tls12`，强制 .NET 底层 SslStream 用显式 TLS 1.2 而非 Win7 OS 默认（OS 默认不含 TLS 1.2）。
- 证书链校验回调 `ValidateServerCertificate` 允许 Win7 根证书存储过期场景（DNS 名匹配时放行）。
- `LegacyTlsTunnelService`（stunnel 本地隧道）已改为**手动开启**（`UseLegacyTlsTunnel = true`），默认关闭。stunnel **不随构建输出**（`CopyToOutputDirectory=Never`），仅在 Win7 用户手动启用时需独立部署。

**模型清单**（.onnx 不入版本控制，需通过导出脚本生成）：

| 端 | 系列 | 可选模型（文件名） |
|----|------|-------------------|
| WinForms | YOLOv5 | `yolov5nu_320` `yolov5nu_640` `yolov5su_320` `yolov5su_640` `yolov5mu_320` `yolov5mu_640` |
| WPF | YOLO26 | `yolo26n_320` `yolo26n_640` `yolo26s_320` `yolo26s_640` `yolo26m_320` `yolo26m_640` |
| Android 检测 | YOLO26 | `yolo26n_320` `yolo26n_640` `yolo26s_320` `yolo26s_640`（m 模型可选，需酌情加入） |

### 模型按需下载（不随发行包打包）

模型文件不再随发行包分发。客户端在首次启动 / 切换模型时从 Server 下载，本地缓存复用。参见下方"发行包约束"。

**本地缓存路径**：

| 端 | 路径 | 管理类 |
|---|---|---|
| WinForms | `%APPDATA%\VisionGuard\models\{modelKey}.onnx` | `Utils\ModelManager.cs` |
| WPF | `%APPDATA%\VisionGuard\models\{modelKey}.onnx` | `Utils\ModelManager.cs` |
| Android | `filesDir/models/{modelName}_{inputSize}.onnx` | `OnnxInferenceEngine.kt` `downloadModel()` |

**首次安装 / 旧版升级**：启动时自动将旧路径（exe 同目录 `Assets\`）的模型迁移到 `%APPDATA%` 缓存目录，避免重复下载。

**下载 URL**：`https://visionguard.xgwnje.cn/models/{modelKey}.onnx`，无需鉴权。

**Server 静态文件**：

| 路由 | 目录 | 鉴权 | 内容 |
|------|------|------|------|
| `/releases/` | `server/data/releases/` | 无 | 客户端更新包 (zip/apk) |
| `/models/` | `server/data/models/` | 无 | ONNX 模型文件 |
| `/screenshots/` | `server/data/screenshots/` | `X-API-Key` | 报警截图 |

**Android 检测端特有**：
- 数码裁切变焦 1x–5x：纯软件中心裁切，不调 CameraX API。zoom≥3→1920×1080，zoom≥2→1280×960
- 高分辨率 640×640 需 SoC 白名单（骁龙 7/8 Gen、天玑 8/9、麒麟、Exynos）
- 无 Preview 绑定，仅 ImageAnalysis，降低 GPU 负载
- 模型不再打包到 APK assets（`assets/models/` 为空目录），首次启动通过 OkHttp 从 Server 下载到 `filesDir/models/`

**Server 核心模块**：
- `ConnectionManager`：WS 认证/心跳/广播/中继
- `AlertStore`：内存缓冲 200 条/设备 + `data/alerts.json` 持久化
- `ScreenshotCleanup`：72h TTL

WS 三角色：`windows` / `android` / `android-detector`

**心跳**：检测端 **3s**（带业务状态），接收端 **30s**（仅保活），幽灵阈值统一 **45s**，无传输层 ping。

**自动更新**：Server 提供 `/api/update` 查询接口 + `/releases/*` 静态文件下载。
- 启动时自动检查 → 弹窗提示（**非强制**，用户可跳过）
- Windows / Android 检测等设置或服务器页提供**手动"检查更新"按钮**；Android 接收端在警报页连接状态条提供手动检查更新入口
- Windows 有更新时弹 `OK/Cancel` 对话框；Android Service 自动检查时**仅发通知**不自动下载，Android 接收端手动检查走警报页状态条 + AlertDialog / Toast，提示文案必须包含当前版本号
- WS 认证版本过低返回 `needs-update` 强制升级
- 发布：`node scripts/release.js <version>`

**版本**：根目录 `VERSION` 为权威来源，`scripts/sync-version.js` 一键同步全端。注意：WPF 端运行时版本取自 `AppConfig.cs` 的 `Version` 常量，而非 `.csproj` 的 MSBuild 属性；`sync-version.js` 须同时更新这两处。

**Android 接收端**：MVVM + 前台 Service（`foregroundServiceType="remoteMessaging"`），底部 Tab：警报 / 设备。警报页连接状态条显示服务器连接状态和在线设备数，点击后手动检查版本更新。

## 构建与发行

### 构建输出路径（必须统一）

| 端 | 配置 | 输出路径 | CLI 与 VS IDE 是否一致 |
|---|---|---|---|
| WinForms | Debug\|AnyCPU / Debug\|x64 | `bin\Debug\` | ✓ |
| WinForms | Release\|AnyCPU / Release\|x64 | `bin\Release\` | ✓ |
| WPF | Debug / Release | `bin\x64\` | ✓（`OutputPath` 无条件属性） |
| Server | - | `dist/` | N/A |
| Android | Debug / Release | Gradle 标准路径 | N/A |

**禁止** cli 和 IDE 输出到不同目录。

### 发行包约束（必须遵守）

**禁止出现在发行包中的文件**：
- `*.pdb` — 调试符号（WinForms: `DebugType=none`；WPF: csproj Target 清理）
- `*.lib` — 原生导入库（WPF: csproj Target 清理）
- `*.dll.config` — .NET 9 无用 binding redirects（WPF: csproj Target 清理）
- `tools/stunnel/**` — Win7 遗留，手动启用，不随包（WinForms: `CopyToOutputDirectory=Never`）
- `Assets/*.onnx` — 模型文件，按需下载
- WPF `runtimes/` 下的非 `win-x64` 目录 — 用 csproj Target 从根源删除，剩余 `win-x64/native/` 内容移到根目录后删掉 `runtimes/`
- `Assets/*.png` 如无代码引用则设 `CopyToOutputDirectory=Never`

**原则**：从 **.csproj 根源**控制输出内容，不在 `release.js` 中事后删除。每次改动后必须编译验证并用 `Get-ChildItem` 检查输出目录。

### 发行包体积参考（v4.3.0，不含模型）

| 端 | 体积 | 主要组成 |
|---|---|---|
| WinForms | ~2.5 MB | exe + onnxruntime.dll + NuGet DLLs，不含模型 |
| WPF | ~4.9 MB | exe/dll + onnxruntime.dll（根目录）+ Assets 图标，不含模型 |
| Android 检测 | ~41.5 MB | APK，不含模型 |
| Android 接收 | ~22.3 MB | APK |

### 发布脚本

`scripts/release.js`：同步版本 → 编译五端 → 收集模型到 `server/data/models/` → 压缩 zip/apk（排除 Assets 目录）→ 更新 `releases.json`。

## 约束与注意事项

0. **版本号不能自动变更**：`sync-version.js`、`release.js`、修改 `VERSION` 文件等操作必须由开发者明确指令触发。禁止在编译、修复 bug、提交代码时自动升级版本号。
1. 修改 `server/` 和 Android 端代码前确认影响范围（多端协议耦合）
2. Server 截图路径 `data/screenshots/<alertId>.(png|jpg)`，通过 HTTP 下载（需 `X-API-Key`）
3. NTP 时钟同步：Windows 端启动时同步；Android 接收端也同步（显示端到端耗时）
4. Windows 网络恢复自动重连（30s 防抖）；Android 网络切换立即重建 WS（清除 OkHttp 连接池）
5. Android 检测端模型文件**不打包**到 APK（`assets/models/` 为空），首次启动时从 Server 下载到 `filesDir/models/`
6. Android 14+：`startForeground()` 须在 Service 启动 5s 内调用
7. 检测端 `foregroundServiceType="camera"`，接收端 `="remoteMessaging"`，不要混用
8. 遮罩持久化：WinForms→settings.ini（SimpleJson），Android→DataStore（Gson），格式不同但语义等价
9. WinForms 退出：窗口 X 直接关程序，托盘右键退出/显示，最小化到托盘
10. 接收端 `foregroundServiceType="remoteMessaging"` — Android 15+ 可能有政策风险，暂维持现状
11. **改动前先验证**：修改构建配置（csproj/gradle）或删除文件前，先 `grep` 确认无代码引用，再动手
12. **从根源修复**：构建输出不干净时，改 csproj/gradle 配置，不要在 release.js 中事后删除
13. **模型状态 UI**：统一在设置页的模型选择处显示下载状态 + 下载按钮，不要单独建模型管理页面
14. **编译后必查**：改动后编译通过不算完，必须用 `Get-ChildItem` 检查输出目录是否有多余文件

## Server 配置参考

关键 `.env` 字段：`PORT` / `API_KEY`（为空时 Server 拒绝启动）/ `SCREENSHOT_TTL_HOURS=72` / `ALERT_TTL_HOURS=168` / `MAX_UPLOAD_BYTES=2097152` / `ENABLE_HTTP_SCREENSHOT_UPLOAD=true` / `MAX_WS_CONNECTIONS=100`

当前新 VPS 公共 DNS、端口、Nginx SNI 路由维护在 `D:\ObjectCode\Server-infra`。`visionguard.xgwnje.cn` 线上路径为公网 `443` -> Nginx stream SNI -> `127.0.0.1:9443` -> VisionGuard Node `127.0.0.1:3000`；不要用旧式独立 `listen 443 ssl` 站点覆盖当前架构。

## 关键常量

| 常量 | 位置 |
|------|------|
| `SERVER_URL = "https://visionguard.xgwnje.cn"` | 两端 `AppConstants.kt` + WinForms `Form1.cs` + WPF `AppConfig.cs` |
| `API_KEY` | C# 两端环境变量 `VISIONGUARD_API_KEY` 优先，发行包保留兼容兜底；Android 两端 Gradle 注入，可在各自 `local.properties` 配置同名键 |
| 检测端包名 `com.xgwnje.visionguard` | `app_name = "VG 检测"` |
| 接收端包名 `com.xgwnje.visionguard_android` | `app_name = "VG 接收"` |
| 模型下载 URL | `{SERVER_URL}/models/{modelKey}.onnx` |
| Windows 模型缓存 | `%APPDATA%\VisionGuard\models\` |
| Android 模型缓存 | `{filesDir}/models/` |

## Skill 与工程管理

> 详细构建流程、版本管理、子智能体规则已迁移到独立 skill。AGENTS.md 只保留架构与约束知识，执行流程见 skill。

| Skill | 用途 | 触发 |
|-------|------|------|
| `visionguard-build` | 五端编译（Server/WinForms/WPF/Android-Detector/Android-Receiver） | `/visionguard-build` 或"编译" |
| `visionguard-e2e` | 端到端 / 设备 / 模拟器 / 运行证据验证（含真机发现、AVD 兜底、logcat/截图采集） | "端到端测试"、"模拟器验证"、"实机验证"、"自动化验证" |
| `version-alignment` | 全端版本号检查与批量修改 | `/version-alignment` 或"版本对齐" |
| `push-update` | 推送客户端更新 | 发布新版本 |
| `vps-server-info` | VPS 连接信息 | 部署/排查 |

Agent 清单（`.Codex/agents/`）：`scanner`（搜索/审查/扫描）/ `builder`（编译验证）

## 详细文档

- 当前解释性文档入口：[docs/codex/00-index.md](docs/codex/00-index.md)
- Windows 检测端专题：[docs/codex/30-windows-detector.md](docs/codex/30-windows-detector.md)
