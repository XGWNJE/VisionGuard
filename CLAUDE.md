# VisionGuard

> AI 实时监控系统。Windows 检测端 → VPS 中继 → Android 接收端报警。

## 项目布局

```
detector/windows-winforms/   C# / .NET Framework 4.7.2 / WinForms   主力线 (Win7+)
detector/windows/            C# / .NET 9 / WPF / MVVM               视觉升级线 (Win10+)
detector/android/            Kotlin / CameraX / ONNX Runtime Mobile  安卓检测端
server/                      Node.js 20+ / TypeScript / Express / ws 中继服务器
receiver/android/            Kotlin / Jetpack Compose / OkHttp       安卓接收端
```

## 构建与运行

```bash
# Server
cd server && npm install && npm run build && npm start

# Server 部署到 VPS
bash server/deploy.sh              # 仅同步 src/
bash server/deploy.sh --full       # 含 package.json + npm install
bash server/deploy.sh --nginx      # 含 Nginx 配置更新
bash server/setup-tls.sh           # 首次 TLS 证书申请 (仅需一次)

# Windows WinForms — Visual Studio 2022 打开 detector/windows-winforms/VisionGuard.csproj
# Windows WPF      — cd detector/windows && dotnet build -c Release
# Android          — Android Studio 打开 receiver/android 或 detector/android
#                     ./gradlew assembleRelease
```

## 架构

### 检测端（WinForms / WPF / Android）

**推理链**：Capture → MaskApply → Preprocess → ONNX Inference → Parse → AlertDecision → Push

**遮罩**（三端对齐）：相对坐标 `[0,1]`，推理前 Bitmap 涂黑。**同时影响推理结果与报警截图**（涂黑区域不识别、不可见）。监控运行中热更新，下个 Tick 生效。

**WinForms vs WPF 差异**：

| | WinForms | WPF |
|---|---|---|
| 框架 | .NET Framework 4.7.2 | .NET 9 |
| 模型 | YOLOv5nu (`[1,84,2100]`, 无内置 NMS) | YOLO26 (`[1,300,6]`) |
| ORT | 1.1.0 统一包 | 1.19.0 |
| WS | websocket-sharp | System.Net.WebSockets |
| 架构 | 事件驱动 Form1 partial class | MVVM (ViewModels/) |
| 特有功能 | 本地声光报警 | — |

**WinForms 与 Android 检测端差异**：WinForms 无数码变焦、无高分辨率开关、无 SoC 白名单。其余功能（遮罩、目标选择、参数调整）行为对齐。

**Android 检测端特有**：
- 数码裁切变焦 1x–5x：纯软件中心裁切，不调 CameraX API。zoom≥3→1920×1080，zoom≥2→1280×960。变化时 unbind+rebind 热更新
- 高分辨率 640×640 需 SoC 白名单（骁龙 7/8 Gen、天玑 8/9、麒麟、Exynos）
- 无 Preview 绑定，仅 ImageAnalysis，降低 GPU 负载

### Server

核心模块：`ConnectionManager`（WS 认证/心跳/广播/中继）、`AlertStore`（内存缓冲 200 条/设备 + `data/alerts.json` 持久化）、`ScreenshotCleanup`（72h TTL）

WS 三角色：`windows` / `android` / `android-detector`

WS 消息协议（关键）：

| 方向 | 类型 | 说明 |
|---|---|---|
| → Server | `auth` | 认证（role/deviceId/deviceName/version） |
| → Server | `heartbeat` | 富状态 15s（检测端）/ 极简 20s（接收端） |
| → Server | `alert` | 报警（HTTP POST 或纯 WS） |
| → Server | `command` / `set-config` | 接收端→Server→检测端 远控 |
| ← Server | `device-list` / `alert` / `command-ack` / `ping` | 广播与回执 |
| ↔ | `request-screenshot` / `screenshot-data` | 截图按需拉取（**4.0 将移除**） |
| ↔ | `disconnect-reason` / `session-info` | 客户端断开诊断上报 |

版本门控：`minClientVersion = '3.5.0'`，低版本 WS 认证直接拒绝。当前全端版本 `3.7.0`，根目录 `VERSION` 文件为权威来源。Android 端版本号由 `build.gradle.kts:versionName` 驱动，`WsMessage.kt` 通过 `BuildConfig.VERSION_NAME` 动态读取。

幽灵检测：`deviceOfflineMs = 75_000` + 应用层 ping + WS 协议层 ping 双层探测。幽灵清理使用 `<=` 比较确保边界一致。

截图模式：当前 `ENABLE_HTTP_SCREENSHOT_UPLOAD` — true=HTTP 上传，false=纯 WS 按需拉取。**计划 4.0.0 统一改为自动推送**（alert 消息内嵌截图 Base64，去按需拉取）。

Server 角色管理：`windows` + `android-detector` 同存 `windowsClients` Map，`android` 存 `androidClients` Map。**计划 4.0.0 改为独立三 Map**（detectorWindows / detectorAndroid / receiver），各自独立幽灵阈值。

### Android 接收端

MVVM + 前台 Service（`foregroundServiceType="remoteMessaging"`）。**无独立 Settings 屏**，远控参数（cooldown/confidence/targets/maskRegions/digitalZoom）散落在 `DeviceListScreen` 和 `AlertDetailScreen` 内联。

三页标题栏为自定义实现（`Surface + Row + statusBars padding`），未抽取复用组件，修改时需同步三处。

## 版本管理

- 版本号：`feat→minor / fix|refactor|perf→patch / BREAKING CHANGE→major`
- 同步脚本：`bash scripts/bump-version.sh [patch|minor|major]`
- WS 消息格式变更 = BREAKING CHANGE → 主版本 +1

## 约束与注意事项

1. 修改 `server/` 和 Android 端代码前确认影响范围（多端协议耦合）
2. Server 截图路径 `data/screenshots/<alertId>.png`，通过 HTTP 下载（需 `X-API-Key`）
3. NTP 时钟同步：Windows 端启动时同步；Android 接收端也同步（显示端到端耗时）
4. Windows 网络恢复自动重连（30s 防抖）；Android 网络切换立即重建 WS（清除 OkHttp 连接池）
5. Android 检测端 4 个 ONNX 模型打包到 APK assets，首次启动按选择复制到 `filesDir/models/`
6. Android 14+：`startForeground()` 须在 Service 启动 5s 内调用
7. 检测端 `foregroundServiceType="camera"`，接收端 `="remoteMessaging"`，不要混用
8. 遮罩持久化：WinForms→settings.ini（SimpleJson），Android→DataStore（Gson），格式不同但语义等价
9. WinForms 退出：窗口 X 直接关程序，托盘右键退出/显示，最小化到托盘
10. WPF 远程命令路由（pause/resume/set-config）已全部接入 `MainViewModel.cs:120-147`，`CommandReceived` / `SetConfigReceived` 均有订阅
11. 报警本地队列：WinForms 和 Android 检测端在 WS 断连时缓存最多 50 条报警，恢复后批量重发，超 5 分钟丢弃
12. 接收端 `foregroundServiceType="remoteMessaging"` — Android 15+ 可能有政策风险（Google Play 要求此类型必须对接 FCM），暂维持现状，备忘后续评估改为 `dataSync`

## Server 配置参考

关键 `.env` 字段：`PORT` / `API_KEY`（为空时 Server 拒绝启动）/ `SCREENSHOT_TTL_HOURS=72` / `ALERT_TTL_HOURS=168` / `MAX_UPLOAD_BYTES=2097152` / `ENABLE_HTTP_SCREENSHOT_UPLOAD=true` / `MAX_WS_CONNECTIONS=100`

## 关键常量

| 常量 | 位置 |
|------|------|
| `SERVER_URL = "https://xgwnje.cn"` | 两端 `AppConstants.kt` + WinForms `Form1.cs` + WPF `AppConfig.cs` |
| `API_KEY = "XG-VisionGuard-2024"` | 两端 `AppConstants.kt` |
| 检测端包名 `com.xgwnje.visionguard` | `app_name = "VG 检测"` |
| 接收端包名 `com.xgwnje.visionguard_android` | `app_name = "VG 接收"` |

## 工程管理策略

### 子智能体使用规则

**模型路由**：重度思考（架构设计/调试/协议设计）→ `model: opus`，轻度任务（扫描/构建/重构/风格检查）→ `model: haiku`。

**并行优先**：无依赖的子任务必须并行派发。典型场景：
- 代码提交前：`scanner` + `style-checker` + `build-validator` 并行
- 跨端变更验证：各端 `build-validator` 同一次派发
- 大规模搜索：多个 Explore 或 `scanner` 并行，避免主会话膨胀

**Agent 清单**（`.claude/agents/`）：

| Agent | 模型 | 用途 | 触发时机 |
|-------|------|------|---------|
| architect | opus | 跨栈架构设计、方案评审 | 多端协议变更、技术选型、Breaking Change |
| debugger | opus | 跨栈 Bug 根因分析 | 难复现 bug、WS 通信异常、推理无输出 |
| protocol-designer | opus | WS 消息协议设计 | 消息格式变更、版本兼容分析 |
| scanner | haiku | 死代码/未用资源/重复代码 | 提交前检查、清理任务 |
| build-validator | haiku | 多平台构建验证 | 提交前全平台编译检查 |
| style-checker | haiku | C#/Kotlin/TS 命名与格式 | 提交前风格一致性检查 |
| refactor-batch | haiku | 批量机械重构 | 统一重命名、提取重复代码、import 整理 |

**内置 Agent 补充**：`Explore`（只读搜索探索）、`general-purpose`（通用多步任务）、`Plan`（实现方案设计）。

### 变更验证流水线

提交前必做（可并行）：
1. `build-validator` — 全平台编译
2. `scanner` — 死代码/未用资源扫描
3. `style-checker` — 风格一致性

所有验证结果汇总后确认无误再提交。

## 详细文档

- WPF 迁移进度与约束：[MIGRATION_PROGRESS.md](detector/windows/MIGRATION_PROGRESS.md)
