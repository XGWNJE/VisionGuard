# AGENTS.md — VisionGuard 项目指南

> 为 Kimi Code 提供的项目上下文与开发约束。
> 本项目原由 Claude Code 维护，现迁移至 Kimi Code。

---

## 项目概览

VisionGuard 是基于 AI 的实时监控系统。Windows 检测端通过 YOLO26 推理，经自建服务器实时推送报警至 Android 手机。

```
detector/windows/    detector/android/         server/                    receiver/android/
  (Win检测端)           (安卓检测端)       ──►  VPS 中继服务器  ──►         (接收端)
  屏幕/窗口捕获         后置摄像头 + ONNX          Node.js / WebSocket          Android 手机
  YOLO26 目标检测       YOLO26 目标检测            HTTP REST + WS               查看报警 / 远程控制
```

| 目录 | 技术栈 | 功能 |
|---|---|---|
| `detector/windows/` | C# / .NET Framework 4.7.2 / WinForms | 屏幕/窗口捕获 + YOLO26 ONNX 推理 + 报警推送 |
| `server/` | Node.js / TypeScript / Express / ws | 中继服务器：设备管理、报警转发、截图存储 |
| `receiver/android/` | Kotlin / Jetpack Compose / OkHttp | 接收报警通知、查看截图、远程控制检测端 |
| `detector/android/` | Kotlin / Jetpack Compose / CameraX / ONNX Runtime Mobile | Android 检测端（后置摄像头 + YOLO26 推理 + 报警推送） |

---

## 当前重点工作：Windows 检测端 .NET 9 UI 重构

### 目标
- 将 `detector/windows/` 从 **.NET Framework 4.7.2 + WinForms + GDI+ 自绘** 升级至 **.NET 9 + WinForms/WPF（待定）**
- 彻底消除所有已知 UI 缺陷和隐患
- **功能零改动**：AI 推理、报警、WebSocket、截图、遮罩等逻辑完全保留

### 已知技术债务（详见 `detector/windows/PROJECT_AUDIT.md`）

1. **DPI 适配半吊子**：声明了 `PerMonitorV2`，但 `OnDpiChanged` 仅调窗口大小，内部控件坐标不刷新
2. **绝对坐标布局**：所有页面内控件均为 `ref int y` 硬编码，窗口无法自由缩放
3. **零数据绑定**：全命令式事件驱动，UI 与业务紧耦合
4. **HiddenScrollCheckedListBox 为 Win32 Hack**：篡改 `GWL_STYLE` + 拦截 `WM_NCPAINT`，版本兼容性风险极高
5. **GDI+ 性能瓶颈**：`DetectionOverlayPanel` 每帧 `Clone()` Bitmap，纯软件渲染
6. **项目格式老旧**：旧版 `.csproj` + `packages.config`

### 重构约束（不可违反）

- **功能不变**：YOLO 推理、报警状态机、WebSocket 通信、服务器协议、INI 配置格式、遮罩行为全部保持
- **协议兼容**：WS 消息格式、HTTP API、认证方式不得修改（涉及多端协议兼容性）
- **Assets 不变**：`yolo26n.onnx` / `yolo26s.onnx` 模型文件、图标、音效保持原样
- **坐标系统不变**：遮罩区域继续使用相对坐标 `[0,1]`，与 Android 端行为对齐

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

### detector/windows — Windows 推理检测端

**核心模块**：
- `Capture/` — 屏幕捕获、窗口枚举、子区域选择、`GlobalKeyHook.cs` 全局热键
- `Inference/` — ONNX Runtime 推理引擎、YOLO 输出解析、`ImagePreprocessor.cs` 图像预处理、`MaskApplier.cs` 推理前在 Bitmap 上 in-place 涂黑遮罩
- `Services/` — 监控服务（定时推理循环）、报警服务（声光通知）、服务器推送服务（WS 连接）
- `Models/` — DTO 数据对象（`AlertEvent.cs` / `Detection.cs` / `MonitorConfig.cs`，含 `MaskRegions` 字段）
- `UI/` — 自定义 WinForms 控件（暗色主题、圆角按钮、检测框覆盖层）；`MaskEditorForm.cs` 全屏遮罩编辑器
- `Data/` — `CocoClassMap.cs`（独立目录）
- `Utils/` — NTP 时钟同步、设置持久化、截图渲染器

**关键文件**：
- `Form1.cs` — 主窗体：字段、构造、配置构建、状态控制
- `Form1.Monitor.cs` — 监控控制：区域选择、启停、回调
- `Form1.Server.cs` — 服务器连接、设置持久化、远程配置
- `Form1.UI.cs` — UI 构建：主布局、4 页面、辅助方法
- `OnnxInferenceEngine.cs` — ONNX Runtime 封装
- `YoloOutputParser.cs` — YOLO26 输出解析（格式 `[1, 300, 6]`，已内置 NMS，6 = [x1, y1, x2, y2, conf, class_id]）
- `CocoClassMap.cs` — COCO 80 类中英文映射，`TargetClassNames` 定义 6 类监控目标子集

**UI 架构**：固定 960×640 暗色 WinForms，左侧图标菜单 + 右侧内容区（预览 58% + 页面 42%）
- **捕获页**：区域/窗口选择、**遮罩区域绘制**（启动 `MaskEditorForm`）、当前遮罩计数、开始/停止监控
- **参数页**：置信度阈值 Slider（10–95%，显示 "N%"）、目标采样率 Slider（1–5 次/秒）、警报推送冷却时间 Slider（1–300 秒）、模型选择下拉框（yolo26n / yolo26s）
- **目标页**：6 个 `CheckBox`（人 / 自行车 / 汽车 / 摩托车 / 客车 / 卡车），默认只勾选"人"；空选视为检测全部
- **服务器页**：连接状态、设备名、手动重试

**v3.7.0 新增 — 遮罩绘制（Mask）**（与 Android v3.6.0 行为对齐）：
- 数据结构：`MonitorConfig.MaskRegions: List<RectangleF>`，相对坐标 X/Y/Width/Height ∈ [0,1]，最小相对尺寸 `0.02`
- 编辑入口：捕获页「遮罩区域…」按钮 → 抓一帧底图 → `MaskEditorForm` 多矩形拖拽编辑器（撤销/清空/取消/确定 + ESC，半透明红色填充 + 进行中黄色虚线）
- 耦合点：`MaskApplier.cs` 在 `MonitorService.cs` `OnTick` capture 完、`ToTensor` 之前 `Graphics.FillRectangle` 黑色 in-place 涂黑
- **重要副作用**：遮罩同时影响推理与报警截图与 UI 预览（涂黑区域不被识别，截图与 `DetectionOverlayPanel` 也是黑的）
- 持久化：settings.ini key `MaskRegions`，自定义 DTO `{left, top, right, bottom}` 经 `Utils.SimpleJson` 序列化
- 热更新：监控运行中编辑遮罩 → `_monitorService.UpdateConfig(BuildConfig())` 走 `Volatile.Write`，下个 Tick 即生效

**依赖**：Microsoft.ML.OnnxRuntime 1.17+、websocket-sharp
**模型**：`Assets/yolo26n.onnx`（~9.4MB）/ `Assets/yolo26s.onnx`（~37MB），COCO 80 类
**ONNX 线程数**：固定 2 线程（与 Android 检测端一致，不可调）
**WS role**：`windows`
**目标框架（当前）**：.NET Framework 4.7.2，x64
**目标框架（重构后）**：.NET 9，x64

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

**Windows（当前 .NET Framework 4.7.2）**：
- Visual Studio 2022 打开 `detector/windows/VisionGuard.csproj`
- Release 输出已精简：ClickOnce 关闭，阻止 `.pdb`/`.xml` 复制

**Windows（重构后 .NET 9）**：
```bash
cd detector/windows
dotnet build -c Release
dotnet publish -c Release -r win-x64 --self-contained true
```

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
7. **Windows 与 Android 检测端差异**：
   - Windows 端已支持遮罩绘制（v3.7.0），行为与 Android 完全对齐
   - Windows 端**不支持**数码裁切变焦、高分辨率开关、SoC 白名单（这些为移动端特性）
8. **Windows 检测端重构期间**：Android 端和 Server 保持原样，仅 Windows 端升级

---

## 从 Claude Code 迁移的说明

- 原项目使用 `CLAUDE.md` 作为项目指南，现已合并至本 `AGENTS.md`
- 原 `.claude/settings.json` 中的权限配置为 Claude Code 本地执行白名单，Kimi Code 采用不同的权限模型
- 项目级配置现统一使用 `AGENTS.md`（本文件）

---

*最后更新：2026-05-03 — 初始化 Kimi Code 项目配置，启动 Windows 检测端 .NET 9 重构*
