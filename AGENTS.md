# AGENTS.md — VisionGuard 项目指南

> 为 Kimi Code 提供的项目上下文与开发约束。
> 本项目原由 Claude Code 维护，现迁移至 Kimi Code。

---

## 项目概览

VisionGuard 是基于 AI 的实时监控系统。Windows 检测端通过 YOLO26 推理，经自建服务器实时推送报警至 Android 手机。

```
detector/windows-winforms/ detector/android/         server/                    receiver/android/
  (Win检测端 WinForms主力)  (安卓检测端)       ──►  VPS 中继服务器  ──►         (接收端)
detector/windows/           后置摄像头 + ONNX          Node.js / WebSocket          Android 手机
  (Win检测端 WPF视觉升级)    YOLO26 目标检测            HTTP REST + WS               查看报警 / 远程控制
  屏幕/窗口捕获
  YOLOv5nu 目标检测
```

| 目录 | 技术栈 | 功能 |
|---|---|---|
| `detector/windows-winforms/` | C# / .NET Framework 4.7.2 / WinForms | **主力线**：YOLOv5nu + ORT 1.1.0，Win7 兼容 |
| `detector/windows/` | C# / .NET 9 / WPF | **视觉升级线**：YOLO26 + ORT 1.19.0，MVVM 架构 |
| `server/` | Node.js / TypeScript / Express / ws | 中继服务器：设备管理、报警转发、截图存储 |
| `receiver/android/` | Kotlin / Jetpack Compose / OkHttp | 接收报警通知、查看截图、远程控制检测端 |
| `detector/android/` | Kotlin / Jetpack Compose / CameraX / ONNX Runtime Mobile | Android 检测端（后置摄像头 + YOLO26 推理 + 报警推送） |

---

## Windows 双版本说明

WinForms 版（`detector/windows-winforms/`）为 **Win7 兼容主力维护线**，YOLOv5nu + ONNX Runtime 1.1.0。
WPF 版（`detector/windows/`）为 **.NET 9 视觉升级线**，YOLO26 + MVVM 架构，仅 Win10+。

> 详细进度、架构图、约束与待办见 **[MIGRATION_PROGRESS.md](detector/windows/MIGRATION_PROGRESS.md)**，本文档仅保留与跨端协作相关的要点。

### 当前状态

| 模块 | 状态 |
|------|------|
| 截屏 (BitBlt/PrintWindow) | ✅ |
| ONNX 推理 (YOLO26) | ✅ |
| 遮罩编辑 + 应用 | ✅ |
| 报警判定 + 本地截图 | ✅ |
| WebSocket 服务器推送 | ✅ |
| 实时预览 + 检测框叠加 | ✅ |
| 设置持久化 (settings.ini 兼容) | ✅ |
| 远程命令路由 (pause/resume/set-config) | ❌ 未接入 |
| 回归测试 | ⏳ 待执行 |

### 关键差异（WPF vs 旧 WinForms）

- **MVVM 架构**：`ViewModels/` 层独立，View 通过 Data Binding 驱动，消除命令式布局
- **3 页导航**：参数与目标合并为一个设置页（旧版 4 页）
- **WebSocket**：`websocket-sharp` → `System.Net.WebSockets.Client`（.NET 内置）
- **报警通知**：无本地托盘/音效，由 Android 接收端全权负责
- **GlobalKeyHook**：已移除（键盘钩子暂停推理的早期设计无实际调用链）
- **DPI**：WPF 原生设备无关像素 + app.manifest PerMonitorV2

---

## 版本管理

- **当前版本**：见根目录 [VERSION](VERSION)（纯人工备忘，无自动同步）
- **版本号规则**：
  - `feat:` → 次版本 +1（3.0.0 → 3.1.0）
  - `fix:` / `refactor:` / `perf:` → 修订号 +1（3.0.0 → 3.0.1）
  - `chore:` / `docs:` / `style:` → 不升级版本
  - `BREAKING CHANGE` → 主版本 +1
- **批量升级脚本**：[scripts/bump-version.sh](scripts/bump-version.sh)
  ```bash
  bash scripts/bump-version.sh patch   # 修订号 +1
  bash scripts/bump-version.sh minor   # 次版本 +1
  bash scripts/bump-version.sh major   # 主版本 +1
  bash scripts/bump-version.sh         # 交互式选择
  ```

---

## 各端详解

### detector/windows — Windows 推理检测端（WPF 新版）

> 完整开发者文档见 **[MIGRATION_PROGRESS.md](detector/windows/MIGRATION_PROGRESS.md)**。

**核心模块**：
- `Capture/` — 屏幕捕获 (BitBlt)、窗口捕获 (PrintWindow)、窗口枚举 + DWM 边界
- `Inference/` — ONNX Runtime 推理引擎 (2 线程)、YOLO 输出解析、图像预处理 (320×320)、遮罩应用
- `Services/` — `MonitorService`（ThreadPool Timer 主循环）、`AlertService`（冷却 + 截图 LRU 缓存）、`ServerPushService`（WS 单事件循环）
- `ViewModels/` — MVVM 层：`MainViewModel`（根，持有全部服务）、`MonitorViewModel`、`SettingsViewModel`、`ServerViewModel`
- `Views/` — WPF XAML：`MainWindow`（三栏布局）、`MonitorPage`、`SettingsPage`、`ServerPage`、`WindowPickerWindow`、`RegionSelectorWindow`、`MaskEditorWindow`
- `Models/` — `MonitorConfig`、`Detection`、`AlertEvent`、`DetectionItem`
- `Utils/` — `SettingsStore`（settings.ini 兼容）、`SimpleJson`、`NtpSync`、`SnapshotRenderer`、`LogManager`、`AppConfig`

**依赖**：`Microsoft.ML.OnnxRuntime` 1.19.0、`System.Drawing.Common` 9.0.0（Bitmap/GDI）
**模型**：`Assets/yolo26n.onnx` / `Assets/yolo26s.onnx`
**WS role**：`windows`
**目标框架**：net9.0-windows，x64

### detector/windows-winforms — Windows 推理检测端（WinForms，Win7 兼容主力线）

.NET Framework 4.7.2 项目，x64。核心模块：
`Capture/`、`Inference/`、`Services/`、`Models/`、`UI/`（WinForms 控件）、`Data/`、`Utils/`

- **模型**：YOLOv5nu（`[1,84,2100]`，绝对坐标，无内置 NMS），ONNX Runtime **1.1.0** 统一包
- **UI**：固定 960×640 暗色 WinForms，4 页（捕获/参数/目标/服务器），左侧图标菜单
- **遮罩**：v3.7.0 加入，相对坐标 [0,1]，`MaskEditorForm` 全屏拖拽编辑，`MaskApplier` 推理前 in-place 涂黑
- **退出**：窗口 X 直接关闭程序，托盘右键可退出/显示，最小化隐藏到托盘
- **依赖**：Microsoft.ML.OnnxRuntime 1.1.0、websocket-sharp
- **WS role**：`windows`
- **系统支持**：Windows 7 及以上（x64）

### server — 中继服务器

**技术栈**：Node.js 20+、TypeScript 6、Express 5、ws 8

**核心模块**：
- `routes/alert.ts` — POST `/api/alert` 接收报警上传（multipart/form-data，可选关闭）
- `routes/alerts.ts` — GET `/api/alerts?deviceId=&since=&limit=` 查询报警历史列表（按时间倒序）
- `routes/screenshot.ts` — GET `/screenshots/:id.png` 提供截图下载
- `services/ConnectionManager.ts` — WebSocket 连接管理：认证（含版本门控）、心跳、设备列表广播、报警广播、命令/配置/截图中继；支持三角色（`windows` / `android` / `android-detector`）
- `services/AlertStore.ts` — 报警记录存储：内存循环缓冲（默认 200 条/设备）+ 文件持久化（`data/alerts.json`，7 天 TTL）
- `services/ScreenshotCleanup.ts` — 截图过期清理（默认 TTL 72 小时）
- `middleware/auth.ts` — `X-API-Key` 校验中间件
- `models/types.ts` — 类型集中文件（DTO 与 WS 消息体）
- 健康检查：`GET /health`（无鉴权，返回 uptime）

**WebSocket 消息类型**：
| 方向 | 类型 | 发送方 | 说明 |
|---|---|---|---|
| → Server | `auth` | 所有端 | 认证（含 `role` / `deviceId` / `deviceName` / `version`） |
| ← Server | `auth-result` | Server | 认证结果，`success=false` 时含 `reason` |
| → Server | `heartbeat` | Windows / Android检测端 | 富状态心跳（15s） |
| → Server | `heartbeat-android` | Android接收端 | 极简心跳（20s） |
| → Server | `alert` | 检测端 | 报警推送（HTTP POST `/api/alert` + 截图，或纯 WS 模式） |
| → Server | `command` | Android接收端 | 下发控制命令（pause/resume/stop-alarm） |
| → Server | `set-config` | Android接收端 | 调整检测参数（cooldown/confidence/targets/maskRegions/digitalZoom） |
| → Server | `request-screenshot` | Android接收端 | 请求指定设备截图 |
| → Server | `screenshot-data` | 检测端 | 回传截图（base64 JPEG） |
| → Server | `command-ack` | 检测端 | 命令执行结果回执 |
| ← Server | `device-list` | Server | 设备列表广播 |
| ← Server | `alert` | Server | 报警推送（含 `timings` 端到端计时字段） |
| ← Server | `command-ack` | Server | 命令执行结果（含 relayed/实际结果两次） |

**部署**：VPS `216.36.111.208:3000`，systemd 服务 `visionguard`
**部署脚本**：[server/deploy.sh](server/deploy.sh)

### receiver/android — Android 接收端

**技术栈**：Kotlin 2.3.20、Jetpack Compose BOM 2026.03、AGP 9.1、minSdk 28 / targetSdk 36

**架构**：MVVM + 前台 Service + 单状态源事件循环

**核心模块**：
- `data/remote/WebSocketClient.kt` — OkHttp WebSocket 封装：退避重连、幽灵检测、Session 隔离
- `data/repository/SettingsRepository.kt` — DataStore 偏好设置持久化
- `data/cache/ScreenshotCache.kt` — 报警截图本地磁盘缓存（LRU 策略）
- `service/AlertForegroundService.kt` — 前台服务（`foregroundServiceType="remoteMessaging"`）：持有 WS 连接、接收报警、发送系统通知
- `service/BootReceiver.kt` — 开机自启 + `MY_PACKAGE_REPLACED` 接收器
- `service/NetworkMonitor.kt` — 独立网络监听类，触发立即重连
- `ui/screen/` — `AlertListScreen`、`AlertDetailScreen`、`DeviceListScreen`

**关键常量**：`SERVER_URL = "http://216.36.111.208:3000"`，`API_KEY = "XG-VisionGuard-2024"`

### detector/android — Android 检测端

**技术栈**：Kotlin 2.3.20、Jetpack Compose BOM 2026.03、CameraX 1.4.2、ONNX Runtime Mobile 1.20.0

**架构**：前台 Service（`foregroundServiceType="camera"`）+ CameraX `ImageAnalysis`（无 Preview）+ ONNX Runtime Mobile 纯 CPU 推理

**v3.6.0 新增 — 遮罩绘制 + 数码裁切变焦 1x~5x**：
- 纯软件预处理裁切，不调用 CameraX `setZoomRatio`
- `ImagePreprocessor.cropAndMask(bitmap, zoom, masks)` 集成裁切 + 遮罩涂黑
- CameraX 分辨率自适应：zoom≥3 → 1920×1080；zoom≥2 → 1280×960
- 检测框坐标通过 `cropOffset` 映射回原帧

---

## 开发规范

### 代码风格
- C#：遵循现有项目风格，4 空格缩进，PascalCase 命名
- 重构时优先使用现代 C# 特性（pattern matching、`required` 属性、`init` 访问器等）
- 不得修改 `server/` 和 Android 端的代码（除非协议升级）

### 构建与运行

**Server**：
```bash
cd server
npm install
npm run build
npm start
```

**Windows（WPF 新版 .NET 9）**：
```bash
cd detector/windows
dotnet build -c Release
dotnet publish -c Release -r win-x64 --self-contained true
```

**Windows（WinForms 旧版 .NET Framework 4.7.2）**：
- Visual Studio 2022 打开 `detector/windows-winforms/VisionGuard.csproj`

**Android**：
```bash
cd receiver/android
./gradlew assembleRelease
```

---

## 注意事项

1. **版本号管理**：纯人工控制。修改 WS 消息格式属于 BREAKING CHANGE，必须升级主版本号。
2. **协议兼容性**：多端协议（WS + HTTP）不得擅自修改。服务端有 `minClientVersion = '3.5.0'` 门控。
3. **截图双模式**：`ENABLE_HTTP_SCREENSHOT_UPLOAD` 控制上传策略。`true`=HTTP 上传；`false`=纯 WS 按需拉取。
4. **报警数据持久化**：Server `AlertStore` 内存循环缓冲 + 磁盘 `data/alerts.json`（7 天 TTL）。
5. **NTP 同步**：Windows 端启动时同步 NTP 时钟，确保报警时间戳准确。
6. **网络切换**：Windows 端监听 `NetworkAddressChanged`，30s 防抖后重连。
7. **Windows 新旧版本差异**：
   - WPF 新版（`detector/windows/`）：net9.0-windows，MVVM 架构，`System.Net.WebSockets.Client`，3 页导航
   - WinForms 旧版（`detector/windows-winforms/`）：.NET Framework 4.7.2，websocket-sharp，4 页导航——保留为参考
8. **Windows WPF 约束**：详见 [MIGRATION_PROGRESS.md](detector/windows/MIGRATION_PROGRESS.md) 第三章（线程安全、命令刷新机制、遮罩坐标系统、捕获回退链、SettingsStore 兼容性）
9. **仅 Windows 端在重构**：Android 检测端/接收端和 Server 保持原样
10. **WPF 待完成**：服务端远程命令路由暂停/恢复/设配置未接入（`CommandReceived`/`SetConfigReceived` 无人订阅）

---

## 从 Claude Code 迁移的说明

- 原项目使用 `CLAUDE.md` 作为项目指南，现已合并至本 `AGENTS.md`
- 原 `.claude/settings.json` 中的权限配置为 Claude Code 本地执行白名单，Kimi Code 采用不同的权限模型
- 项目级配置现统一使用 `AGENTS.md`（本文件）

---

*最后更新：2026-05-04 — WPF 迁移 Phase 7a 完成，文档对齐*
