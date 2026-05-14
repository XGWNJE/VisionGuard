# VisionGuard

> AI 实时监控系统。Windows 检测端 → VPS 中继 → Android 接收端报警。

## 项目布局

```
detector/windows-winforms/   C# / .NET Framework 4.7.2 / WinForms   主力线 (Win7+)
detector/windows/            C# / .NET 9 / WPF / MVVM               视觉升级线 (Win10+)
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
| 模型 | YOLOv5nu (`[1,84,2100]`, 无内置 NMS) | YOLO26 (`[1,300,6]`) |
| ORT | 1.1.0 统一包（Managed 1.2.0 版本暂不处理） | 1.19.0 |
| WS | websocket-sharp | System.Net.WebSockets |
| 架构 | 事件驱动 Form1 partial class | MVVM (ViewModels/) |

**Android 检测端特有**：
- 数码裁切变焦 1x–5x：纯软件中心裁切，不调 CameraX API。zoom≥3→1920×1080，zoom≥2→1280×960
- 高分辨率 640×640 需 SoC 白名单（骁龙 7/8 Gen、天玑 8/9、麒麟、Exynos）
- 无 Preview 绑定，仅 ImageAnalysis，降低 GPU 负载

**Server 核心模块**：
- `ConnectionManager`：WS 认证/心跳/广播/中继
- `AlertStore`：内存缓冲 200 条/设备 + `data/alerts.json` 持久化
- `ScreenshotCleanup`：72h TTL

WS 三角色：`windows` / `android` / `android-detector`

**心跳设计**：
- 检测端（WinForms/WPF/Android检测）每 **3s** 发心跳，携带业务状态（isMonitoring/cooldown/confidence/targets）
- 接收端每 **30s** 发心跳，仅保活（不携带业务状态）
- 全端幽灵阈值统一 **45s** — 无消息则判定失联、断开重连
- 无传输层 ping，应用层心跳已覆盖连接检测

**版本门控**：`minClientVersion = '4.0.0'`。根目录 `VERSION` 文件为权威来源。

**Android 接收端**：MVVM + 前台 Service（`foregroundServiceType="remoteMessaging"`）。**无独立 Settings 屏**，远控参数散落在各 Screen 内联。

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
10. 接收端 `foregroundServiceType="remoteMessaging"` — Android 15+ 可能有政策风险，暂维持现状

## Server 配置参考

关键 `.env` 字段：`PORT` / `API_KEY`（为空时 Server 拒绝启动）/ `SCREENSHOT_TTL_HOURS=72` / `ALERT_TTL_HOURS=168` / `MAX_UPLOAD_BYTES=2097152` / `ENABLE_HTTP_SCREENSHOT_UPLOAD=true` / `MAX_WS_CONNECTIONS=100`

## 关键常量

| 常量 | 位置 |
|------|------|
| `SERVER_URL = "https://xgwnje.cn"` | 两端 `AppConstants.kt` + WinForms `Form1.cs` + WPF `AppConfig.cs` |
| `API_KEY` | 环境变量 `VISIONGUARD_API_KEY`（C#两端）+ `AppConstants.kt`（Android两端） |
| 检测端包名 `com.xgwnje.visionguard` | `app_name = "VG 检测"` |
| 接收端包名 `com.xgwnje.visionguard_android` | `app_name = "VG 接收"` |

## Skill 与工程管理

> 详细构建流程、版本管理、子智能体规则已迁移到独立 skill。CLAUDE.md 只保留架构与约束知识，执行流程见 skill。

| Skill | 用途 | 触发 |
|-------|------|------|
| `visionguard-build` | 五端编译（Server/WinForms/WPF/Android-Detector/Android-Receiver） | `/visionguard-build` 或"编译" |
| `version-alignment` | 全端版本号检查与批量修改 | `/version-alignment` 或"版本对齐" |

Agent 清单（`.claude/agents/`）：`architect` / `debugger` / `protocol-designer` / `scanner` / `build-validator` / `style-checker` / `refactor-batch`

## 详细文档

- WPF 迁移进度与约束：[MIGRATION_PROGRESS.md](detector/windows/MIGRATION_PROGRESS.md)
