# Project Overview

本文维护 VisionGuard 的当前项目地图、组件命名和实现边界。各模块实现见对应专题；逐项验证证据只在[验证报告](90-verification-report.md)维护。

## 产品定位

项目当前处于内测开发。迭代以当前需求和实现效率为准，允许破坏性更新，不承诺旧内测版本的协议、配置或数据兼容；只有明确提出兼容要求时才处理。各端、文档和测试随当前实现同步调整，具体操作规则见 [AGENTS.md](../../AGENTS.md#内测开发与迭代原则)。

VisionGuard 按检测节点、统一服务和控制台划分职责：检测节点采集、判断并上报检测事件；统一服务负责连接、认证、状态、告警流转和控制转发；控制台统一查看、配置和管理各类节点。

当前 Windows 检测节点的应用名为 **VisionGuard 视觉节点**，Android 摄像端为 **VisionGuard 镜头推流**，Web 与 Android 管理端为 **VisionGuard 控制台**。统一服务沿用内部技术名称“视觉中继”（`VisionGuard.Relay`）。视觉驻留是 Windows 节点的生命周期辅助组件。

基础账号将一套设备、画面、事件、截图、控制和通知范围隔离。组件使用账号密码登录，服务生成设备身份与会话；用户不再填写通道、设备 ID 或匹配密钥。一个在线推理节点自动关联镜头，多个节点按名称选择。账号创建由管理员命令完成，当前没有公开注册、团队权限或销售计费。

后续能力通过扩展节点类型接入同一套服务和控制台，避免按硬件另建系统。当前已实现统一接入基础：登记角色、节点类型与平台，支持视觉/传感器事件、控制回执、通知收件确认及中断监测。VisionGuard 通知节点已接入后台通知与声音，Web 已承担跨端管理和通知范围配置；硬件驱动尚未接入；契约见[视觉中继](20-server.md#统一接入契约)。

本项目采用 MIT License，以根目录 [LICENSE](../../LICENSE) 为准；第三方依赖、模型和素材遵循各自许可证。Windows 对外只交付一个统一包，由 Win7 兼容启动器按系统选择两个内部 WPF 推理运行时。

## 当前实际组件

仓库维护以下七个组件；原 Vigil 是独立应用，不作为 VG 的外部源码依赖。表格只描述实现范围，验收状态见[验证报告](90-verification-report.md)。

| 规范名称 | 平台 | 路径 | 当前实现范围 |
|---|---|---|---|
| VisionGuard 视觉节点 | Windows | `detector/windows-launcher/`、`detector/windows-wpf/` | 多来源检测；统一包与入口；启动器按系统选择 legacy / modern，负责整包更新与失败回滚 |
| 视觉驻留 | Windows | `detector/windows-resident/` | net472 x64 后台进程；独立 WS 身份、单实例握手与生命周期控制 |
| VisionGuard 镜头推流 | Android | `detector/android/` | 前台 CameraX 采集、最高 720P、实时画面经服务转发；切后台或锁屏停止；不在手机推理 |
| VisionGuard 控制台 | Android | `receiver/android/` | 设备/来源查看、告警详情、逐来源控制和参数配置；识别通用身份和无图事件；保留当前实现，跨端管理由 Web 承担 |
| VisionGuard 控制台 | Web | `receiver/web/` | 同源管理节点/来源、事件、当前参数及每个通知节点的接收范围 |
| VisionGuard 通知节点 | Android | `notifier/android/` | 后台接警、持久化报警队列、有限次数播放、记录、统一时间；无本机通知关键词监听 |
| 视觉中继 | 服务端 | `server/` | 账号与设备会话、账号隔离、媒体转发、状态、告警流转、控制与文件分发 |

### 当前链路

```text
VisionGuard 镜头推流 ──实时摄像头──▶ 视觉中继 ──指定来源──▶ Windows 视觉节点
VisionGuard 视觉节点 ──检测事件、状态──▶ 视觉中继 ──事件、状态──▶ Web 控制台
视觉中继 ──实时报警──▶ VisionGuard 通知节点（保存后确认收件、进入声音队列）
VisionGuard 视觉节点 ◀──控制、配置── 视觉中继 ◀──控制、配置、通知范围── Web 控制台
视觉驻留 ──生命周期状态、控制──▶ 视觉中继
```

以上是当前实现链路；本机、模拟器与独立公网的运行证据以验证报告为准，不代表真机和正式环境验收。

## 组件名称与技术标识

显示名与技术标识分开维护：工程标识采用 `VisionGuard.<角色>.<平台>`，Android 包名采用 `com.xgwnje.visionguard.<角色>`，npm 包名采用 `visionguard-<角色>`。镜头推流仍使用视觉节点身份；协议角色、更新平台、进程握手和本地配置标识以源码契约为准。

| 组件名称 | 工程标识 | 技术标识 |
|---|---|---|
| VisionGuard 视觉节点 | `VisionGuard.Detector.Windows`；启动器 `VisionGuard.Detector.Windows.Launcher` | WS `detector` / `visual` / `windows`；更新平台 `wpf`；运行时 `modern` / `legacy` |
| 视觉驻留 | `VisionGuard.Resident.Windows` | WS `lifecycle` / `resident` / `windows`；程序 `VisionGuard.Resident.Windows.exe` |
| VisionGuard 镜头推流（Android） | `VisionGuard.Detector.Android` | WS `detector` / `visual` / `android`；组件 `android-camera`；更新平台 `android-detector`；包名 `com.xgwnje.visionguard.detector` |
| VisionGuard 控制台 | `VisionGuard.Receiver.Android` | WS `console` / `console` / `android`；更新平台 `android-receiver`；包名 `com.xgwnje.visionguard.receiver` |
| VisionGuard 控制台（Web） | `visionguard-web-console` | WS `console` / `console` / `web`；同源 `/console/` |
| VisionGuard 通知节点 | `VisionGuard.Notifier.Android` | WS `notifier` / `notification` / `android`；包名 `com.xgwnje.visionguard.notifier` |
| 视觉中继 | `VisionGuard.Relay` | 源码目录 `server/`；构建目标 `Server`；npm 包名 `visionguard-relay` |

视觉中继当前实现连接认证、心跳、告警广播、截图/更新路由和设备在线状态；连接状态不等于通知收件确认，通知收件确认不等于声音播放。完整报警链的覆盖边界见[验证报告](90-verification-report.md)。

## 目录职责

| 目录 | 职责 |
|---|---|
| `detector/windows-launcher/` | VisionGuard 视觉节点统一入口与整包更新器（net472 / Win7+） |
| `detector/windows-wpf/` | VisionGuard 视觉节点两套内部 WPF 运行时源码 |
| `detector/windows-package/` | 构建生成的统一 Windows 目录；不作为独立源码组件 |
| `detector/windows-resident/` | 视觉驻留 |
| `detector/windows-shared/` | VisionGuard 视觉节点与驻留程序共用的进程/身份代码 |
| `detector/windows-wpf-smoke/` | 人员检测 smoke 工具，按配置的来源数量取证；用独立 net472 窗口承载来源，legacy 档因此也能在 Win7 上运行 |
| `detector/android/` | VisionGuard 镜头推流 |
| `android-shared/` | 三个 Android 组件共用的账号、加密会话与登录界面 |
| `receiver/android/` | VisionGuard 控制台 |
| `notifier/android/` | VisionGuard 通知节点；独立安装身份与本地数据 |
| `receiver/web/` | Web 控制台；构建到 `server/dist/console/` |
| `server/` | HTTP / WebSocket 视觉中继 |
| `scripts/` | 版本、构建、验证、发行和模型导出脚本 |
| `tests/` | 跨模块约束、WPF 报警链与推理辅助探针 |
| `.agents/skills/` | 三个需要脚本化或授权边界的项目级 Skill |
| `docs/codex/` | 项目事实、操作和验证文档 |
| `docs/design/` | 当前设计规范入口 |
| `icon/` | 当前应用图标素材 |

`artifacts/`、`server/data/releases/`、`server/data/models/`、各端 `bin/`、`obj/`、`build/`、`.gradle/` 和 `node_modules/` 是本地生成或缓存目录，不是源码结构。

## 统一概念与不变边界

- 推理链：`Capture -> MaskApply -> Preprocess -> ONNX Inference -> Parse -> AlertDecision -> Push`。
- 遮罩使用相对坐标 `[0,1]`，推理前涂黑，同时影响识别结果和报警截图。
- 视觉中继 WS 角色当前为 `detector`、`console`、`notifier`、`lifecycle`；节点类型和平台独立于角色。
- 正式服务域名为 `https://visionguard.xgwnje.cn`；根域 `https://xgwnje.cn` 不是新客户端的 VisionGuard 服务地址。
- `VERSION` 是唯一权威版本源，构建、修复和提交不得自动 bump。
- `server/` 与 Android 端协议强耦合；协议变化必须联动源码、测试和专题文档。
- 当前心跳实现为检测端 3 秒、保留的 Android 控制台 30 秒、Web 与通知节点 3 秒、视觉中继幽灵阈值 45 秒；连接超时会生成中断事件；检测中断与通知节点本地服务中断监测按统一契约分别处理。
- Windows 启动器、WPF 运行时和视觉驻留均为 .NET Framework 4.7.2 x64；legacy / modern 的系统和推理组合见[Windows 专题](30-windows-detector.md#入口与运行时)。Android 和视觉中继不承担 Win7 兼容义务。
- 所有公网业务数据统一通过视觉中继转发；不使用 P2P、ICE、STUN 或 TURN。
- 允许可管理的误报，漏报风险是检测效果与故障处置的最高优先级。

## 文档可信度

先核对源码、构建产物和测试结果，再维护当前 `docs/codex/`。截图或帧循环不能替代功能验收。模型、资源和类目映射以对应项目文件和源码静态表为准。
