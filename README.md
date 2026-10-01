<div align="center">

<img src="./icon/visionguard-windows.png" alt="VisionGuard 图标" height="64">

# VisionGuard

VisionGuard 由检测节点、统一服务和控制台协作。当前用 VisionGuard 视觉节点在 Windows 电脑上检测画面中的目标，经服务转发告警和截图，在 Android 的 VisionGuard 控制台查看、配置和管理检测节点。

[使用方式](#使用方式) · [快速开始](#快速开始) · [当前组件](#当前组件) · [路线规划](#路线规划) · [文档与许可](#文档与许可)

[![Version](https://img.shields.io/badge/version-4.5.1-1f6feb)](./VERSION) [![License](https://img.shields.io/badge/license-MIT-7c3aed)](./LICENSE)

</div>

## 使用方式

项目目前处于**内测开发**，迭代允许破坏性更新，不承诺旧版本的协议、配置或数据兼容；测试环境可能需要重新配置或重建。当前定位见[项目概览](./docs/codex/10-project-overview.md#产品定位)。

**适合**需要在自己的设备上运行视觉检测，并在 Android 手机上接收告警的个人或团队。

**当前边界**：视觉检测（Android）暂缓更新；视觉中继的“离线”是连接状态，尚未实现独立的设备离线报警。漏报风险是检测效果与故障处置的最高优先级。已发布 v4.5.1 的范围见[发布说明](./docs/releases/v4.5.1.md)。

1. VisionGuard 视觉节点从屏幕或窗口采集画面，在本机运行视觉模型；当前人员检测以 `person` 类为验证对象。
2. 检测端把告警、截图和状态发往视觉中继；所有公网业务数据统一通过视觉中继，不使用 P2P、ICE、STUN 或 TURN。正式服务地址为 `https://visionguard.xgwnje.cn`。
3. VisionGuard 控制台展示告警、截图和设备状态，并提供当前节点支持的逐来源控制与参数配置。连接状态不等于告警已送达。

当前源码构建统一 Windows 目录：从目录根启动 `VisionGuard.Detector.Windows.exe`，Win7 SP1 x64 选择 legacy 内部运行时，Windows 10/11 选择 modern。已发布包的安装与升级以[发布说明](./docs/releases/v4.5.1.md)为准；当前构建与实机覆盖见[验证报告](./docs/codex/90-verification-report.md)。

## 快速开始

使用已发布版本时先阅读 [v4.5.1 发布说明](./docs/releases/v4.5.1.md)，确认该版本的范围与升级提示。

以下命令从仓库根目录运行，用于验证源码和构建产物。需要 Windows、PowerShell、Node.js 20 或以上及 npm；构建 Windows 端还需支持 C# 12 的 .NET SDK。客户端服务配置见[运维文档](./docs/codex/60-operations.md#配置与服务边界)。

验证视觉中继的测试与编译：

```powershell
cd server
npm ci
npm test
npm run build
cd ..
```

成功后应生成 `server/dist/index.js`。构建 VisionGuard 视觉节点、驻留程序与统一包：

```powershell
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-build\scripts\build-all.ps1 -Target Windows
```

成功后应生成 `detector/windows-package/bin/Release/VisionGuard.Detector.Windows.exe`；构建通过不代表真机画面、告警送达或 Win7 实机已验收。要运行或验证客户端，请按[运维文档](./docs/codex/60-operations.md)选择对应流程。

## 当前组件

| 组件 | 平台 | 当前范围 | 源码入口 |
|---|---|---|---|
| VisionGuard 视觉节点 | Windows | 本地视觉检测；统一入口和 modern / legacy 内部运行时 | [启动器](./detector/windows-launcher/) · [WPF 运行时](./detector/windows-wpf/) |
| 视觉驻留 | Windows | 随 VisionGuard 视觉节点运行，处理受控打开、关闭和状态上报 | [`detector/windows-resident/`](./detector/windows-resident/) |
| 视觉检测 | Android | 当前暂缓；不在 v4.5.1 发布范围 | [`detector/android/`](./detector/android/) |
| VisionGuard 控制台 | Android | 查看告警、截图和设备状态；逐来源控制与参数配置 | [`receiver/android/`](./receiver/android/) |
| 视觉中继 | 服务端 | 统一服务；连接、认证、状态、告警流转和控制转发，保留内部技术名称 | [`server/`](./server/) |

组件的实现状态见[项目概览](./docs/codex/10-project-overview.md)，自动化与真机证据只在[验证报告](./docs/codex/90-verification-report.md)维护。

上述应用名对应当前源码构建；已发布包的显示名以该版本产物为准。

## 路线规划

> [!NOTE]
> **接下来，值得期待**
>
> 以下是后续方向，不保证开发或交付，尚未排期。

| 方向 | 你可以期待什么 | 当前状态 |
|---|---|---|
| 扩展节点类型 | 各类检测节点共用统一服务和控制台，避免每种硬件另建系统 | 未排期 |

架构职责与当前实现边界见[项目概览](./docs/codex/10-project-overview.md)。

## 文档与许可

从[文档索引](./docs/codex/00-index.md)查找各模块说明；构建、配置、运行和发布边界见[运维文档](./docs/codex/60-operations.md)。提交问题或建议前请阅读[贡献说明](./CONTRIBUTING.md)。

| 要查什么 | 入口 |
|---|---|
| 组件与目录 | [项目概览](./docs/codex/10-project-overview.md) |
| 视觉中继接口与协议 | [视觉中继](./docs/codex/20-server.md) |
| Windows 采集、推理与驻留 | [VisionGuard 视觉节点](./docs/codex/30-windows-detector.md) |
| 模型与类别映射 | [模型资源](./docs/codex/35-model-assets.md) |
| Android 两端 | [检测端](./docs/codex/40-android-detector.md) · [VisionGuard 控制台](./docs/codex/50-android-receiver.md) |
| 构建、配置与运行 | [运维](./docs/codex/60-operations.md) |
| 已验证范围与未覆盖项 | [验证报告](./docs/codex/90-verification-report.md) |
| 界面规范 | [设计索引](./docs/design/README.md) |

本项目采用 [MIT License](./LICENSE)。第三方依赖、模型和素材遵循各自许可证。
