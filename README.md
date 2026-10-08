<div align="center">

<img src="./icon/visionguard-windows.png" alt="VisionGuard 图标" height="64">

# VisionGuard

VisionGuard 由检测节点、统一服务和控制台协作。视觉推理节点在 Windows 电脑上检测画面中的目标，经服务转发告警和截图；控制台管理节点，通知节点在 Android 后台接警与响铃。

[使用方式](#使用方式) · [快速开始](#快速开始) · [当前组件](#当前组件) · [路线规划](#路线规划) · [文档与许可](#文档与许可)

[![Version](https://img.shields.io/badge/version-0.6.10-1f6feb)](./VERSION) [![License](https://img.shields.io/badge/license-MIT-7c3aed)](./LICENSE)

</div>

## 使用方式

项目目前处于**0.x 内测开发**，尚未达到正式版要求；此前分发的 4.x 包也属于测试版本。迭代允许破坏性更新，不承诺旧版本的协议、配置或数据兼容；测试环境可能需要重新配置或重建。当前定位见[项目概览](./docs/10-当前架构.md#产品定位)。

**适合**需要在自己的设备上运行视觉检测，并在手机、平板或电脑上管理节点，在 Android 通知节点中接警的个人或团队。

**使用边界**：连接状态不等于通知收件确认，通知收件确认不等于声音播放。漏报风险是检测效果与故障处置的最高优先级，人员检测以 `person` 类验证。实际覆盖与未覆盖项见[验证记录](./docs/90-验证记录.md)。

1. 各组件登录同一账号后自动登记和匹配；相机推流节点以前台摄像头采集画面，经服务交给 Windows 视觉推理节点推理，也可继续使用本地屏幕或窗口来源。
2. 视觉推理节点把告警、截图和状态发往统一服务；所有公网业务数据统一通过统一服务转发，不使用 P2P、ICE、STUN 或 TURN。正式服务地址为 `https://visionguard.xgwnje.cn`。
3. Web 控制台展示事件、截图和设备状态，按能力提供节点/来源启停与参数配置、相机规格及预览、通知声音策略、音频库管理和临时缓存盘点 / 清理，并分配各通知节点的全部或指定接收范围。通知节点保存实时报警后确认收件，再进入本地声音队列。新增能力需服务、Web 与节点使用同一契约。

Windows 发行包使用统一目录：从目录根启动 `VisionGuard.Detector.Windows.exe`，Win7 SP1 x64 选择 legacy 内部运行时，Windows 10/11 选择 modern。

## 快速开始

[客户端发行包](https://github.com/XGWNJE/VisionGuard/releases/latest) · [线上控制台](https://visionguard.xgwnje.cn/console/) · [发行说明](./docs/releases/README.md)

各组件的发行版内置正式服务地址，只需登录同一账号；进入程序后可修改自动生成的本机名称。初始管理员为 `xgwnje`，已有账号沿用密码；全新服务的随机初始密码仅存私有数据目录，见[账号管理](./docs/60-构建验证与发布.md#基础账号与客户端登录)。管理员可在控制台创建账号、禁用账号、重置密码及设置权限。开发验收使用[隔离测试入口](./docs/60-构建验证与发布.md#本机隔离测试)。

以下命令从仓库根目录运行，用于验证源码和构建产物。需要 Windows、PowerShell、Node.js 22.18 或以上及 npm；构建 Windows 端还需支持 C# 12 的 .NET SDK。客户端服务配置见[运维文档](./docs/60-构建验证与发布.md#配置与服务边界)。

首次开发时运行 `node scripts/install-git-hooks.js` 安装版本与推送保护，授权使用方式见[运维文档](./docs/60-构建验证与发布.md#授权保护)。

验证统一服务的测试与编译：

```powershell
npm --prefix receiver/web ci
cd server
npm ci
npm test
npm run build
cd ..
```

成功后应生成 `server/dist/index.js` 和 `server/dist/console/index.html`；启动服务后从同一服务的 `/console/` 打开控制台，外部访问须使用 HTTPS。构建视觉推理节点、驻留程序与统一包：

```powershell
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-build\scripts\build-all.ps1 -Target Windows
```

成功后应生成 `detector/windows-package/bin/Release/VisionGuard.Detector.Windows.exe`；构建通过不代表真机画面、告警送达或 Win7 实机已验收。要运行或验证客户端，请按[运维文档](./docs/60-构建验证与发布.md)选择对应流程。

## 当前组件

| 组件 | 平台 | 当前范围 | 源码入口 |
|---|---|---|---|
| 视觉推理节点 | Windows | 本地画面及远程镜头推理；统一入口、modern / legacy 运行时与内部后台驻留 | [启动器](./detector/windows-launcher/) · [WPF 运行时](./detector/windows-wpf/) · [内部驻留](./detector/windows-resident/) |
| 相机推流节点 | Android | 前台摄像头推流，最高 720P；通过统一服务接入 Windows 推理来源 | [`detector/android/`](./detector/android) |
| 控制台 | Web | 唯一控制台，左导航、右内容；节点/来源、事件、账号与通知范围；三种外观 | [`receiver/web/`](./receiver/web) |
| 通知节点 | Android | VG 后台接警、声音队列、收件确认与告警记录 | [`notifier/android/`](./notifier/android) |
| 统一服务 | 服务端 | 基础账号、设备隔离、实时画面转发、状态、告警和控制 | [`server/`](./server) |

组件的中文显示名与工程、包名对应关系见[命名规范](./docs/15-命名规范.md)，实现状态见[项目概览](./docs/10-当前架构.md)，自动化与真机证据只在[验证报告](./docs/90-验证记录.md)维护。

统一接入基础已实现：角色、节点类型与平台分开登记，复用能力、配置、控制和事件链；支持无图传感器事件、实时重试、通知节点收件确认与本地服务中断监测。硬件驱动待后续实施。通知节点使用独立包名与数据目录，可和原 Vigil 分开安装。契约见[统一服务](./docs/20-统一服务.md#统一接入契约)。

各端共用扫描人应用图标：实心头部、三条等厚圆弧横纹与橙色扫描线；物料见 [`icon/`](./icon/README.md)，功能图标采用 Lucide；显示名全部使用中文。

## 路线规划

> [!NOTE]
> **接下来，值得期待**
>
> 后续方向见[路线图](./docs/05-路线图.md)，本次实施进度见[任务清单](./docs/06-任务清单.md)。

## 文档与许可

从[文档索引](./docs/00-文档索引.md)查找各模块说明；构建、配置、运行和发布边界见[运维文档](./docs/60-构建验证与发布.md)。提交问题或建议前请阅读[贡献说明](./CONTRIBUTING.md)。

本项目采用 [MIT License](./LICENSE)。第三方依赖、模型和素材遵循各自许可证。
