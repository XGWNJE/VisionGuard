# 组件命名与技术标识

本文是当前组件名称、英文名、简称和技术标识的唯一对照。实现范围见[项目概览](10-project-overview.md)，安装和构建操作见[运维](60-operations.md)，验收状态见[验证报告](90-verification-report.md)。

## 规范名称

品牌固定写作 **VisionGuard**，品牌简称 **VG**。首次介绍、应用标题和组件清单使用规范名称；明确上下文后可用下表简称。英文名称用于英文文档和产品介绍，当前界面仍使用中文。

| 规范名称 | 英文名称 | 允许简称（中文 / 英文） | 平台 |
|---|---|---|---|
| VisionGuard 视觉节点 | VisionGuard Visual Node | 视觉节点 / Visual Node | Windows |
| VisionGuard 镜头推流 | VisionGuard Camera Streamer | 镜头推流 / Camera Streamer | Android |
| VisionGuard 控制台 | VisionGuard Console | 控制台 / Console | Android |
| VisionGuard 控制台 | VisionGuard Console | 控制台 / Console | Web |
| VisionGuard 通知节点 | VisionGuard Notifier | 通知节点 / Notifier | Android |
| VisionGuard 统一服务 | VisionGuard Server | 统一服务 / Server | 服务端 |

共 5 类组件、6 个平台实现。Android 和 Web 控制台是同一产品的两个实现，区分时写“VisionGuard 控制台（Android）”和“VisionGuard 控制台（Web）”。视觉节点包含启动器、modern / legacy 推理运行时和后台驻留程序，统一安装、更新和管理；驻留不单独命名为产品或计入组件清单。共享库、测试探针和原 Vigil 不计为新的 VisionGuard 应用。不另设 VGD、VGR 等组件缩写。

## 工程与安装身份

下列标识按当前源码固定使用，大小写和拼写必须准确。`Detector`、`Receiver`、`Relay` 是技术标识中的既有用词，不作为当前产品英文名称；目录、代码命名空间、安装包身份和协议字段不能按显示名机械替换。

| 组件与平台 | 源码目录 | 工程或 npm 标识 | 安装或运行入口 |
|---|---|---|---|
| 视觉节点（Windows） | `detector/windows-wpf/`、`detector/windows-launcher/`、`detector/windows-resident/` | `VisionGuard.Detector.Windows`；启动器 `VisionGuard.Detector.Windows.Launcher`；内部驻留 `VisionGuard.Resident.Windows` | `VisionGuard.Detector.Windows.exe`；内部驻留 `VisionGuard.Resident.Windows.exe` |
| 镜头推流（Android） | `detector/android/` | `VisionGuard.Detector.Android` | `com.xgwnje.visionguard.detector` |
| 控制台（Android） | `receiver/android/` | `VisionGuard.Receiver.Android` | `com.xgwnje.visionguard.receiver` |
| 控制台（Web） | `receiver/web/` | `visionguard-web-console` | 同源 `/console/` |
| 通知节点（Android） | `notifier/android/` | `VisionGuard.Notifier.Android` | `com.xgwnje.visionguard.notifier` |
| 统一服务（服务端） | `server/` | 内部工程标识 `VisionGuard.Relay`；npm `visionguard-relay` | `server/dist/index.js` |

Android 工程标识对应 `rootProject.name`，包名同时对应 `namespace` 和 `applicationId`。Windows 主程序与内部驻留的 AssemblyName、RootNamespace 按各自工程标识；启动器的 RootNamespace 为 `VisionGuard.Detector.Windows.Launcher`，AssemblyName 为 `VisionGuard.Detector.Windows`，生成统一入口，与内部运行时分别位于不同目录。

内部驻留文件的说明为“VisionGuard 视觉节点驻留程序”，Product 为“VisionGuard 视觉节点”；功能简称“后台驻留”，英文说明使用“Visual Node resident process”，不另设产品英文名。

## 登录组件与协议身份

`component` 表示客户端实现，`role` 表示协议职责，`nodeType` 表示节点类型，`platform` 表示平台；它们不是应用显示名。服务签发身份，权限按实际能力判断；镜头推流属于视觉节点类型，但不执行推理。

| 组件与平台 | `component` | `role` | `nodeType` | `platform` |
|---|---|---|---|---|
| 视觉节点（Windows） | `windows-inference` | `detector` | `visual` | `windows` |
| 视觉节点（驻留子进程） | `windows-resident` | `lifecycle` | `resident` | `windows` |
| 镜头推流（Android） | `android-camera` | `detector` | `visual` | `android` |
| 控制台（Android） | `android-console` | `console` | `console` | `android` |
| 控制台（Web） | `web-console` | `console` | `console` | `web` |
| 通知节点（Android） | `android-notifier` | `notifier` | `notification` | `android` |

表中驻留子进程是视觉节点内部身份，使用主视觉节点签发的子会话，与主程序共用设备 ID 和设备名称。它随主程序启动，主界面关闭后继续运行，以支持远程重新打开；产品归属不改变进程与协议职责。统一服务本身不是客户端登录组件。`sensor` 类型已有统一协议契约，当前没有已交付的硬件应用名称。

## 构建与更新标识

构建目标与更新键属于脚本/API 契约，名称和产品名可以不同；调用时使用原值。

| 组件与平台 | 构建目标 | 更新平台键 | 当前构建产物或入口 |
|---|---|---|---|
| 视觉节点（Windows） | `Windows` / `WPF`（均构建整套 Windows 目录） | `wpf` | `detector/windows-package/bin/Release/VisionGuard.Detector.Windows.exe` |
| 视觉节点（驻留子进程） | `WindowsResident`（内部程序单独编译） | 随 `wpf` 整包分发 | `detector/windows-resident/bin/Release/net472/VisionGuard.Resident.Windows.exe` |
| 镜头推流（Android） | `AndroidDetector` | `android-detector` | `detector/android/app/build/outputs/apk/release/app-release.apk` |
| 控制台（Android） | `AndroidReceiver` | `android-receiver` | `receiver/android/app/build/outputs/apk/release/app-release.apk` |
| 控制台（Web） | 随 `Server` 构建 | 随服务部署 | `server/dist/console/index.html` |
| 通知节点（Android） | `AndroidNotifier` | 当前未接入正式发布/自动更新流程 | `notifier/android/app/build/outputs/apk/release/app-release.apk` |
| 统一服务（服务端） | `Server` | 无客户端更新键 | `server/dist/index.js` |

Windows 与既有 Android 发布文件仍分别使用 `VisionGuard-WPF-v{version}.zip`、`VisionGuard-Detector-v{version}.apk`、`VisionGuard-Receiver-v{version}.apk` 技术文件名；这不表示镜头仍有手机推理能力。通知节点当前只有构建 APK，不能把构建目标写成已接入正式发布。生产服务名、部署目录和已发布文件名以运维与发布契约为准。

## 表达规则

- “VisionGuard 视觉节点”是 Windows 应用名；“视觉节点类型（`visual`）”包含 Windows 推理与 Android 镜头推流。讲推理能力时写“Windows 视觉节点”或“推理节点”，不能仅凭 `visual` 推断设备可推理。
- 当前叙述不再用“视觉检测（Windows/Android）”“视觉告警”“接收端”作为应用名。用“控制台”“通知节点”或“媒体订阅端”指明实际对象；`detector` 协议角色需要覆盖采集与推理时直接注明角色。
- 服务当前名称统一为“VisionGuard 统一服务”或简称“统一服务”；“视觉中继”仅用于指认旧版本名称，`Relay` 仅用于准确引用技术标识。
- 用户自定义设备名称不是应用名称；`deviceId` 是设备身份，`sourceId` 是推理来源身份，`streamId` 是媒体流身份，不相互替代。“镜头”指采集设备，“视频流”指传输数据，“来源”指视觉节点的一路输入。
- 已发布版本说明、旧包实际标签与历史证据保持该版本事实；当前命名不表示生产或旧安装包已改名。命名变化须同步本表、相关源码显示文案及引用，并运行 `node scripts/check-docs.js`。
