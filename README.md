<div align="center">

<img src="./icon/visionguard-windows.png" alt="VisionGuard 图标" height="64">

# VisionGuard

在 Windows 电脑上检测画面中的目标，通过 Server 转发告警和截图，在 Android 接收端查看结果与设备状态。

[使用方式](#使用方式) · [快速开始](#快速开始) · [当前组件](#当前组件) · [文档与许可](#文档与许可)

[![Version](https://img.shields.io/badge/version-4.5.1-1f6feb)](./VERSION) [![License](https://img.shields.io/badge/license-VGSAL--1.0-7c3aed)](./LICENSE)

</div>

## 使用方式

**适合**需要在自己的设备上运行视觉检测，并在 Android 手机上接收告警的个人或团队。目前已经实现的纯软件视觉方案为免费版；接入检测硬件探测器后进入付费版，具体权利以[许可证](./LICENSE)为准。

**当前边界**：Android 检测端暂缓更新；Server 的“离线”是连接状态，尚未实现独立的设备离线报警（`DeviceOfflineAlert`）。漏报风险是检测效果与故障处置的最高优先级。已发布 v4.5.1 的范围见[发布说明](./docs/releases/v4.5.1.md)。

1. Windows 检测端（Visual Detector）从屏幕或窗口采集画面，在本机运行视觉模型；当前人员检测以 `person` 类为验证对象。
2. 检测端把告警、截图和状态发往 Server；所有公网业务数据统一通过 Server，不再规划 P2P、ICE、STUN 或 TURN。正式服务地址为 `https://visionguard.xgwnje.cn`。
3. Android 接收端展示告警、截图和设备状态。连接状态不等于告警已送达。

仓库正在开发统一 Windows 包：用户从包根目录启动 `VisionGuard.exe`，启动器按系统选择 WPF modern 或 legacy 运行时。**这描述的是当前源码，不能当作 v4.5.1 已发布包的安装说明**；Win7 SP1 x64 的 legacy 档与 Windows 10/11 的 modern 档仍需按[验证报告](./docs/codex/90-verification-report.md)分别判断覆盖范围。

## 快速开始

使用已发布版本时先阅读 [v4.5.1 发布说明](./docs/releases/v4.5.1.md)，确认该版本的范围与升级提示。

以下命令从仓库根目录运行，用于验证源码和构建产物。需要 Windows、PowerShell、Node.js 20 或以上及 npm；构建 Windows 端还需支持 C# 12 的 .NET SDK。客户端服务配置见[运维文档](./docs/codex/60-operations.md#配置与服务边界)。

验证 Server 的测试与编译：

```powershell
cd server
npm ci
npm test
npm run build
cd ..
```

成功后应生成 `server/dist/index.js`。构建 Windows 检测端、驻留程序与统一包：

```powershell
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-build\scripts\build-all.ps1 -Target Windows
```

成功后应生成 `detector/windows-package/bin/Release/VisionGuard.exe`；构建通过不代表真机画面、告警送达或 Win7 实机已验收。要运行或验证客户端，请按[运维文档](./docs/codex/60-operations.md)选择对应流程。

## 当前组件

| 组件 | 当前范围 | 源码入口 |
|---|---|---|
| Windows 检测端 | 本地视觉检测；统一入口和 modern / legacy 运行时正在开发验证 | [启动器](./detector/windows-launcher/) · [WPF 运行时](./detector/windows-wpf/) |
| Windows 驻留程序 | 随 Windows 检测端运行，处理受控打开、关闭和状态上报 | [`detector/windows-resident/`](./detector/windows-resident/) |
| Android 检测端 | 当前暂缓；不在 v4.5.1 发布范围 | [`detector/android/`](./detector/android/) |
| Android 接收端 | 查看告警、截图和设备状态 | [`receiver/android/`](./receiver/android/) |
| Server | HTTP / WebSocket 中继、模型与更新路由 | [`server/`](./server/) |

组件的实现状态见[项目概览](./docs/codex/10-project-overview.md)；产品规划只在[路线图](./docs/codex/15-product-roadmap.md)维护，自动化与真机证据只在[验证报告](./docs/codex/90-verification-report.md)维护。

## 文档与许可

从[文档索引](./docs/codex/00-index.md)查找各模块说明；构建、配置、运行和发布边界见[运维文档](./docs/codex/60-operations.md)。提交问题或建议前请阅读[贡献说明](./CONTRIBUTING.md)。

| 要查什么 | 入口 |
|---|---|
| 组件与目录 | [项目概览](./docs/codex/10-project-overview.md) |
| 产品方向与验收闸门 | [产品路线图](./docs/codex/15-product-roadmap.md) |
| Server 接口与协议 | [Server](./docs/codex/20-server.md) |
| Windows 采集、推理与驻留 | [Windows 检测端](./docs/codex/30-windows-detector.md) |
| 模型与类别映射 | [模型资源](./docs/codex/35-model-assets.md) |
| Android 两端 | [检测端](./docs/codex/40-android-detector.md) · [接收端](./docs/codex/50-android-receiver.md) |
| 构建、配置与运行 | [运维](./docs/codex/60-operations.md) |
| 已验证范围与未覆盖项 | [验证报告](./docs/codex/90-verification-report.md) |
| 界面规范 | [设计索引](./docs/design/README.md) |

当前主线使用 [VGSAL-1.0](./LICENSE)：这是源码可见许可证，不是开源许可证。再分发、对外托管、产品集成或接入检测硬件需要[商业授权](./COMMERCIAL-LICENSE.md)；历史 MIT 版本的边界见 [LICENSE-HISTORY.md](./LICENSE-HISTORY.md) 和 [LICENSE-MIT](./LICENSE-MIT)。
