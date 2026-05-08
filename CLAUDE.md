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
| ← Server | `device-list` / `alert` / `command-ack` | 广播与回执 |

版本门控：`minClientVersion = '3.5.0'`，低版本 WS 认证直接拒绝。

幽灵检测：`deviceOfflineMs = 75_000` + 应用层 ping + WS 协议层 ping 双层探测。

截图双模式：`ENABLE_HTTP_SCREENSHOT_UPLOAD` — true=HTTP 上传，false=纯 WS 按需拉取。

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
10. WPF 远程命令路由（pause/resume/set-config）尚未接入，`CommandReceived`/`SetConfigReceived` 无人订阅

## Server 配置参考

关键 `.env` 字段：`PORT` / `API_KEY` / `SCREENSHOT_TTL_HOURS=72` / `ALERT_TTL_HOURS=168` / `MAX_UPLOAD_BYTES=2097152` / `ENABLE_HTTP_SCREENSHOT_UPLOAD=true`

## 关键常量

| 常量 | 位置 |
|------|------|
| `SERVER_URL = "http://216.36.111.208:3000"` | 两端 `AppConstants.kt` |
| `API_KEY = "XG-VisionGuard-2024"` | 两端 `AppConstants.kt` |
| 检测端包名 `com.xgwnje.visionguard` | `app_name = "VG 检测"` |
| 接收端包名 `com.xgwnje.visionguard_android` | `app_name = "VG 接收"` |

## 详细文档

- WPF 迁移进度与约束：[MIGRATION_PROGRESS.md](detector/windows/MIGRATION_PROGRESS.md)
