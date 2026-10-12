# Windows 推理节点推流

负责：Windows 视觉推理节点的本地推理 / 推流两态与推流支路。不负责：其他模块事实、任务状态或重复验证记录。更新时机：该主题源码或约束变化时。

Windows 视觉推理节点有两种互斥状态：**本地推理**（采集本机画面做推理，现状）与**推流到其他推理节点**（把同一份画面推给别的推理节点，由对方推理）。节点级二选一，不能同时跑。Windows 推流节点没有镜头可用，推流源只能是窗口 / 屏幕采集。组件与能力全貌见[视觉推理节点](30-Windows视觉节点.md)，媒体契约见[统一服务](20-统一服务.md#实时媒体)，任务登记见[任务清单](06-任务清单.md)。

## 两种状态

- 状态 A「本地推理」即现有链路：采集窗口 / 屏幕 → 遮罩 → 预处理 → ONNX → 解析 → 报警 → 上报。遮罩、检测参数、模型与报警语义都不变。
- 状态 B「推流到其他推理节点」只有采集、缩放编码与发布，不加载模型、不做预处理、不产生任何本机报警与事件。
- 两态在节点级硬互斥：推流态不建立订阅连接，推理态不建立发布连接；切换时先停稳一态再起另一态，切态期间正在处理的帧等结束，不释放仍在使用的推理引擎。
- 推流源只允许本机采集。已经接收到的远程镜头画面不能二次推流，避免 A→B→A 环路。
- 接收端不感知发布端是相机还是推理节点：它照现有远程镜头来源建卡、复用遮罩 / 模型 / 参数 / 截图 / 报警。

维护入口：`detector/windows-wpf/Runtime/DetectorCapabilities.cs`、`Services/MultiSourceMonitorCoordinator.cs`、`ViewModels/MultiSourceViewModel.cs`、`Views/MonitorPage.xaml`、`Views/MainWindow.xaml`。

## 推流支路

- 采集直接复用 `Capture/WindowCapturer.cs`、`Capture/ScreenCapturer.cs` 的位图，跳过遮罩、预处理与推理；采集目标的最小尺寸沿用现有语义（宽高都须严格大于 100 像素）。
- 发布前等比缩放到最长边不超过 1280、短边不超过 720，超过才缩放、不做放大；JPEG 质量 55，与两个相机实现一致。
- 新增 `Services/MediaPublishService.cs` 连接 `/media/ws`，首条消息 `direction:'publish'`，复用 `Net/MinimalWebSocketClient.cs` 的证书校验与 `Utils/SimpleJson.cs` 的消息编解码，沿用现有用户会话与 `/media/ws` 拆分，不新增通道。
- 帧为 4 字节大端 JSON 头长度、UTF-8 头加 JPEG，头含 `streamId/sessionId/sequence/capturedAt/width/height/rotation`；采集时间取本机单调时钟并取整，旋转固定为 0、方向由宽高表达。
- 等待 `media-ready` 取 `streamId`、`sessionId` 与 `targetSamplingRate` 后按频率发送；最多 8 帧在途、合计 2 MiB 加帧头，`frame-ack` 累计释放已确认帧。目标来源的运行状态决定频率，同会话调整走 `media-rate`，范围 1–5 FPS。
- 正常停止发送 `stream-stop` 与原因 `user` 后关闭连接；断网、服务重启、连接被替换按 2 秒退避重连并重新走 `media-auth`。发布 2.5 秒无新帧由服务标记 `frame-stalled`，异常断开标记 `connection-lost`。
- 界面在 `Views/MonitorPage.xaml` 的运行状态组提供两态开关，并显示推流状态、实际帧率、目标采样率与最后确认序号；窗口关闭、采集失败、未绑定目标分别给出可读原因。

## 服务端放行

- 发布方向按能力放行：相机组件（`android-camera`、`web-camera`）与声明 `video-publish` 的 Windows 推理节点都可发布；订阅方向仍按 `isInferenceComponent`。同一节点不能既发布又订阅。
- `NodeProtocol.allowedCapabilities` 给 Windows 推理节点增加 `video-publish` 与 `stream-control`；`camera-config` 仍只给相机，Windows 推流节点不提供规格、亮度与预览开关。
- 流记录与源身份沿用现有 `streams.json` 形状（`publisherDeviceId` / `targetDeviceId` / `sourceId` / `sourceName`）；`ensureAccount` 的来源枚举按可发布能力收集发布端。
- 发布端仍需绑定目标：同账号只有一个推理设备时自动绑定、多个时由控制台显式绑定，绑定上限沿用每目标 16 路；自动绑定只对相机组件生效，避免多个 Windows 推理节点互相推流成环；重连使用新的媒体 `sessionId`，`sourceId` 与绑定继续保存。
- 服务端拒绝把发布端自己或不可订阅的设备作为绑定目标，控制台级联绑定 A→B 允许、B 再绑回 A 拒绝。

维护入口：`server/src/services/NodeComponents.ts`、`server/src/services/MediaRelay.ts`、`server/src/services/NodeProtocol.ts`、`server/src/services/ConnectionManager.ts`、`server/src/routes/streams.ts`。

## 控制与回执

- `start-stream` / `stop-stream` 的放行条件从「必须是相机组件」改成能力判断（`stream-control`），控制台无需为推流节点新增命令。
- 推流态上报 `isMonitoring=false` 且不报来源，服务不会产生 `detection-interrupted` 误报；推流状态由流记录与 `video-publish` 能力共同表达。
- 控制台按能力显示推流关联、推流状态与启停，并给出未绑定目标、采集源不可用等失败原因；不把相机的前台、摄像头权限与解锁条件套到 Windows 节点上。
- 节点本机改态以本机意图为准，服务回报实际结果；推流期间改动采集目标或遮罩先停止再改。

维护入口：`receiver/web/src/main.tsx`、`receiver/web/src/protocol.ts`、`receiver/web/src/RemoteSettings.tsx`。

## 边界与失败模式

- 采集目标消失：窗口关闭或最小化、屏幕选区失效，按采集失败停推并报可读原因，不静默续发旧帧。
- 本机环境变化：锁屏、切换用户、离开用户会话、系统休眠恢复后按连接断开处理，恢复后不自动抢推流，由本机状态决定。
- 网络与服务：断网、服务重启、发布连接被新连接替换按 2 秒退避重连；重连后重新绑定目标、重新取频率。
- 多发端：同账号多路推流各自独立限额；同一设备重复推流替换旧连接，旧连接的回调不能停止新连接。
- 切态：先停止本机推理（或推流）再起另一态；推理进行中的帧等结束，不泄漏推理引擎、不补发暂停期间的报警。
- 能力与协议：不声明 `video-publish` 的推理节点（Android 推理节点、旧版本端点）不能发布；协议版本闸门按现有机制，不做兼容协商。

## 不做与假设

- 不新增虚拟摄像头或摄像头采集；推流源只有窗口 / 屏幕两类。
- 不做视频编码（H.264 / H.265）与音频推流，沿用 JPEG 帧契约。
- 不改采集最小尺寸与现有媒体限额；采样 1–5 FPS 整数、JPEG 最大 2 MiB、最长边 1280、短边 720 继续适用。
- 不改控制台既有自动绑定行为，不改相机推流节点与 Android 推理节点的现有能力。
- 不承诺旧内测协议兼容；协议版本闸门按现有机制，不额外增加协商、双格式或迁移入口。

## 验收与取证

- 本地快速验证：`node scripts/check-docs.js`、`npm --prefix server test`、`npm --prefix receiver/web test`，以及 `visionguard-build` 编 Windows 与 Server。
- 链路验证：在 `.agents/skills/visionguard-e2e/scripts/e2e-smoke.ps1` 增加 `WindowsPushChain` 模式（对照现有 `WebCameraChain`），起隔离统一服务与两个 Windows 实例，断言推流端只采只推、接收端建成远程镜头来源并推理出 `person` 报警入库、推流端不产生本地报警；`scripts/check-docs.js` 的技能模式清单同步登记该模式。
- 真窗口画面、采集目标切换与观感由 owner 在真实设备目检，本地断言与中继收帧不能替代它。
