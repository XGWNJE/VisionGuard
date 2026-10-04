<div align="center">

<img src="./icon/visionguard-windows.png" alt="VisionGuard 图标" height="64">

# VisionGuard

VisionGuard 由检测节点、统一服务和控制台协作。视觉节点在 Windows 电脑上检测画面中的目标，经服务转发告警和截图；控制台管理节点，通知节点在 Android 后台接警与响铃。

[使用方式](#使用方式) · [快速开始](#快速开始) · [当前组件](#当前组件) · [路线规划](#路线规划) · [文档与许可](#文档与许可)

[![Version](https://img.shields.io/badge/version-0.6.0-1f6feb)](./VERSION) [![License](https://img.shields.io/badge/license-MIT-7c3aed)](./LICENSE)

</div>

## 使用方式

项目目前处于**0.x 内测开发**，尚未达到正式版要求；此前分发的 4.x 包也属于测试版本。迭代允许破坏性更新，不承诺旧版本的协议、配置或数据兼容；测试环境可能需要重新配置或重建。当前定位见[项目概览](./docs/codex/10-project-overview.md#产品定位)。

**适合**需要在自己的设备上运行视觉检测，并在手机、平板或电脑上管理节点，在 Android 通知节点中接警的个人或团队。

**当前边界**：0.6.0 提供管理员账号管理、自动设备命名与实时相机推流节点；设备、视频、控制和事件按账号隔离。连接状态不等于通知收件确认，通知收件确认不等于声音播放。漏报风险是检测效果与故障处置的最高优先级，人员检测以 `person` 类验证。安装与升级见[发行说明](./docs/releases/v0.6.0.md)，实机覆盖见[验证报告](./docs/codex/90-verification-report.md)。

1. 各组件登录同一账号后自动登记和匹配；相机推流节点以前台摄像头采集画面，经服务交给 Windows 视觉节点推理，也可继续使用本地屏幕或窗口来源。
2. 视觉节点把告警、截图和状态发往统一服务；所有公网业务数据统一通过统一服务转发，不使用 P2P、ICE、STUN 或 TURN。正式服务地址为 `https://visionguard.xgwnje.cn`。
3. Web 控制台展示事件、截图和设备状态，按能力提供节点/来源启停与参数配置，并分配各通知节点的全部或指定接收范围。通知节点保存实时报警后确认收件，再进入本地声音队列。

Windows 发行包使用统一目录：从目录根启动 `VisionGuard.Detector.Windows.exe`，Win7 SP1 x64 选择 legacy 内部运行时，Windows 10/11 选择 modern。

## 快速开始

[客户端发行包](https://github.com/XGWNJE/VisionGuard/releases/tag/v0.6.0) · [线上控制台](https://visionguard.xgwnje.cn/console/) · [发行说明](./docs/releases/v0.6.0.md)

各组件的发行版内置正式服务地址，只需登录同一账号；进入程序后可修改自动生成的本机名称。初始管理员为 `xgwnje`，已有账号沿用密码；全新服务的随机初始密码仅存私有数据目录，见[账号管理](./docs/codex/60-operations.md#基础账号与客户端登录)。管理员可在控制台创建账号、禁用账号、重置密码及设置权限。开发验收使用[隔离测试入口](./docs/codex/60-operations.md#本机隔离测试)。

以下命令从仓库根目录运行，用于验证源码和构建产物。需要 Windows、PowerShell、Node.js 22.18 或以上及 npm；构建 Windows 端还需支持 C# 12 的 .NET SDK。客户端服务配置见[运维文档](./docs/codex/60-operations.md#配置与服务边界)。

验证统一服务的测试与编译：

```powershell
npm --prefix receiver/web ci
cd server
npm ci
npm test
npm run build
cd ..
```

成功后应生成 `server/dist/index.js` 和 `server/dist/console/index.html`；启动服务后从同一服务的 `/console/` 打开控制台，外部访问须使用 HTTPS。构建视觉节点、驻留程序与统一包：

```powershell
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-build\scripts\build-all.ps1 -Target Windows
```

成功后应生成 `detector/windows-package/bin/Release/VisionGuard.Detector.Windows.exe`；构建通过不代表真机画面、告警送达或 Win7 实机已验收。要运行或验证客户端，请按[运维文档](./docs/codex/60-operations.md)选择对应流程。

## 当前组件

| 组件 | 平台 | 当前范围 | 源码入口 |
|---|---|---|---|
| 视觉节点 | Windows | 本地画面及远程镜头推理；统一入口、modern / legacy 运行时与内部后台驻留 | [启动器](./detector/windows-launcher/) · [WPF 运行时](./detector/windows-wpf/) · [内部驻留](./detector/windows-resident/) |
| 相机推流节点 | Android | 前台摄像头推流，最高 720P；通过统一服务接入 Windows 推理来源 | [`detector/android/`](./detector/android/) |
| 控制台 | Android | 查看告警、截图和设备状态；逐来源控制与参数配置 | [`receiver/android/`](./receiver/android/) |
| 控制台 | Web | 节点/来源管理、当前参数、事件与通知范围；跟随系统浅色/深色 | [`receiver/web/`](./receiver/web/) |
| 通知节点 | Android | VG 后台接警、声音队列、收件确认与告警记录 | [`notifier/android/`](./notifier/android/) |
| 统一服务 | 服务端 | 基础账号、设备隔离、实时画面转发、状态、告警和控制 | [`server/`](./server/) |

组件的中文显示名与工程、包名对应关系见[命名规范](./docs/codex/15-component-naming.md)，实现状态见[项目概览](./docs/codex/10-project-overview.md)，自动化与真机证据只在[验证报告](./docs/codex/90-verification-report.md)维护。

统一接入基础已实现：角色、节点类型与平台分开登记，复用能力、配置、控制和事件链；支持无图传感器事件、实时重试、通知节点收件确认与本地服务中断监测。硬件驱动待后续实施。通知节点使用独立包名与数据目录，可和原 Vigil 分开安装。契约见[统一服务](./docs/codex/20-server.md#统一接入契约)。

各端共用青色盾牌镜头应用图标，功能图标采用 Lucide；显示名全部使用中文。

## 路线规划

> [!NOTE]
> **接下来，值得期待**
>
> 统一接入、账号隔离和手机相机推流节点已进入当前源码，以下为后续方向；不保证开发或交付，均未排期，实施前再细化范围。

目标架构分为三个角色：**分布节点**（视觉推理节点、硬件探测节点、通知节点）、**统一服务**、**控制台**。通知节点通过服务消息接收报警，原 Vigil 继续作为独立应用维护。

首期个人使用，以及时通报为目标；控制台配置各通知节点的告警接收范围。恢复连接后不补响旧告警，持续检测中断和链路故障也作为告警处理。

| 顺序与分组 | 规划核心 | 复杂度 | 当前状态 |
|---|---|---|---|
| 第 3 组前：软件验证收尾 | 整理现有业务与主题验收；自动运行契约、构建及已连接真机验证，通过内容直接验收，受限项单独保留；详情见[验证计划](./docs/codex/90-verification-report.md#第-3-组之前的验证计划) | 中，依赖测试环境 | 可执行验证已完成；受限项待补 |
| 3. 硬件原型与现场验证 | 用现成传感器、开发板和电池模块，暂不做 PCB；面向 2–8 米内慢走与快速骑行，兼顾范围和成本，疑似有人即提醒；内置电池并预留直流供电，经移动 Wi-Fi 直连统一服务；依次验证检测、误漏报、及时送达、中断报警、供电和续航后再定组合 | 中高，需现场验证 | 初步设想 |
| 4. 视觉来源扩展 | 手机相机推流节点之外，按需接入采集卡及其他摄像设备的视频流；窗口、选区和遮罩编辑留本机 | 高，按来源逐项推进 | 方向已确认 |
| 5. 远期扩展 | Android 视觉节点优先考虑平板，输入方式与性能待评估；按需增加传感器，必要时融合结果；按使用频率评估远程画面、选区和遮罩编辑；原型稳定后考虑集成电路板与结构优化 | 高，按需评估 | 初步设想 |

硬件的独立检测试验可提前进行；联网接入与告警送达验证接在统一接入、通知节点之后。

架构职责与当前实现边界见[项目概览](./docs/codex/10-project-overview.md)。

## 文档与许可

从[文档索引](./docs/codex/00-index.md)查找各模块说明；构建、配置、运行和发布边界见[运维文档](./docs/codex/60-operations.md)。提交问题或建议前请阅读[贡献说明](./CONTRIBUTING.md)。

| 要查什么 | 入口 |
|---|---|
| 组件与目录 | [项目概览](./docs/codex/10-project-overview.md) |
| 名称、简称、工程与包名 | [命名规范](./docs/codex/15-component-naming.md) |
| 统一服务接口与协议 | [统一服务](./docs/codex/20-server.md) |
| Windows 采集、推理与驻留 | [视觉节点](./docs/codex/30-windows-detector.md) |
| 模型与类别映射 | [模型资源](./docs/codex/35-model-assets.md) |
| 控制台、通知与 Android | [相机推流节点](./docs/codex/40-android-detector.md) · [控制台与通知节点](./docs/codex/50-android-receiver.md) |
| 构建、配置与运行 | [运维](./docs/codex/60-operations.md) |
| 已验证范围与未覆盖项 | [验证报告](./docs/codex/90-verification-report.md) |
| 界面规范 | [设计索引](./docs/design/README.md) |

本项目采用 [MIT License](./LICENSE)。第三方依赖、模型和素材遵循各自许可证。
