# Android 视觉推理节点

负责：Android 视觉推理节点。不负责：其他模块事实、任务状态或重复验证记录。更新时机：该主题源码或约束变化时；历史发行说明只纠正事实，不随当前版本改写。

`detector/android-inference/` 是**视觉推理节点**的 Android 实现：在手机或平板上采集画面、本机执行目标推理、产生检测事件并上报统一服务。它与 Windows 视觉推理节点是同一个组件类、同一套协议身份语义，只是平台与采集方式不同；界面结构、来源管理、检测参数、遮罩、报警与远控口径与 Windows 对齐。组件地图见[项目概览](10-当前架构.md)，命名与身份见[命名规范](15-命名规范.md)，服务契约见[统一服务](20-统一服务.md)，本轮证据见[验证记录](90-验证记录.md)。

## 与相机推流节点的关系

| 关注点 | Android 视觉推理节点 | 相机推流节点 |
|---|---|---|
| 登录组件 | `android-inference` | `android-camera` |
| 安装身份 | `com.xgwnje.visionguard.inference` | `com.xgwnje.visionguard.detector` |
| 本机模型与推理 | 有，本机执行 ONNX 推理 | 无，明确不加载模型 |
| 画面去向 | 本机采集本机推理，不发布视频 | 经统一服务转发给推理节点 |
| 服务端角色 | `detector / visual / android`，`video-subscribe` | `detector / visual / android`，`video-publish` |

两个应用是独立安装、独立设备身份，可以装在同一台手机或不同设备上，也可以同时在线。推理节点**不**取代相机节点，也不发布视频；它订阅相机节点的媒体流作为远程镜头来源。后台刷新与前台服务属于推理节点自身，不是独立应用。

## 检测链

`Capture → MaskApply → Preprocess → ONNX Inference → Parse → AlertDecision → Push`

- **采集**：本机相机用 CameraX `ImageAnalysis`，直接输出 RGBA 画面、只保留最新帧；远程镜头通过统一服务媒体通道 `/media/ws`（`direction=subscribe`）收取 JPEG 帧。远程帧按 4 字节大端头长 + JSON 头 + JPEG 解析，头最长 4096 字节、JPEG 最长 2 MiB、最长边不超过 1280 且短边不超过 720、方向仅 0/90/180/270，头部声明的宽高必须与 JPEG 实际尺寸一致，否则丢弃该帧而不是拿错位画面推理。
- **遮罩**：相对坐标 `[0,1]`，推理前就地涂黑，同一张已涂黑的画面同时用于推理、报警截图与本机预览。遮罩只属于本机来源配置，不通过 `set-config` 远程同步。
- **预处理**：等比缩放到模型输入并居中补黑边，输出 CHW `float[3*size*size]`、RGB、`/255`；模型输入尺寸取自 ONNX 输入元数据 `shape[2]`，取不到时回退 320。
- **推理**：ONNX Runtime Android，默认尝试 NNAPI 加速，失败或会话创建失败时回落 CPU 并保留原因；intra 线程 2、inter 线程 1、全量图优化。
- **解析**：只按输出展平长度判形态。`300 * 6 = 1800` 视为 YOLO26 端到端 `[1,300,6]`（模型内含 NMS），`84 * 2100` 或 `84 * 8400` 视为 YOLOv5 `[1,84,N]`（解析侧逐类 NMS，IoU 0.45、候选上限 3000）。无法识别的长度直接报错，不按另一档下标硬读。两档统一最多返回 5 个框，坐标映射回原帧并裁剪黑边。
- **报警判定**：逐来源独立冷却，只统计成功帧；时间戳使用 NTP 校正时间。

## 检测参数

| 参数 | 取值 | 生效方式 |
|---|---|---|
| 置信度 | 10–95 整数百分比 | 冷改动，下次启动该来源生效 |
| 采样频率 | 1–5 整数帧/秒 | 冷改动 |
| 报警冷却 | 1–300 整数秒 | 冷改动 |
| 模型 | 本端清单内且已下载 | 冷改动 |
| 检测目标 | COCO 英文类名多选，最长 4096 字符 | 冷改动 |

运行中拒绝修改参数，远控与本机界面使用同一套校验与拒绝文案。

## 来源管理

- 来源身份 `sourceId` 稳定不可改，只允许 `^[A-Za-z0-9_-]{1,64}$`；本机相机来源固定使用 `camera`，远程来源沿用服务端下发的 `sourceId`，重连不新增来源。
- 来源名可改，最长 64 个 UTF-16 单元，禁止控制字符。
- 可添加本机相机来源，也可由控制台把相机推流节点绑定到本节点；绑定的远程镜头由 `stream-list` 自动入列，服务端移除后本地卡片同步删除。
- 来源数量上限以服务端下发的 `maxSources`（1–16）为准，超限不再新增。
- 每路来源独立采集、模型、配置、推理与错误状态，故障只影响本路，不自动减路或降帧。

## 报警与状态上报

- 报警先写入按 服务 / 账号 / 设备 分区的持久 outbox（上限 500 条、超出丢最旧、`expiresAt` 过期不补传），只有收到统一服务 `alert-ack` 才移除；`alert-id-conflict` 与 `invalid-or-expired-event` 视为确定拒收并移除，其它原因保留重试。
- 实时期限为事件发生后 30 秒；截图在 `alert-ack accepted=true` 之后异步发送，宽度不超过 960、JPEG 质量 65。
- 本机截图缓存按 7 天 / 200 条 / 100 MB 清理。
- 心跳 3 秒一次，上报逐来源的运行意图 `monitoringExpected`、最后成功处理时间 `lastProgressAt`、实际帧率、后端、错误与参数；`lastProgressAt` 只在成功处理后推进。
- 实际帧率低于目标 80% 连续 30 秒时给出性能不足提示，只统计成功帧。
- 能力集合为 `monitor-control`、`config-control`、`request-correlation`、`cache-maintenance`、`screenshot-on-demand`、`source-control`、`video-subscribe`、`visual-inference`；**不声明** Windows 专用的 `directml`。

## 远控

服务端下行的 `command` 与 `set-config` 逐条回 `command-ack`（`phase=completed`）。

- 设备级：`pause` 停止全部来源、`resume` 启动全部已配置来源；`stop-alarm` 恒回“当前无报警”。
- 来源级：带 `targetSourceId` 的 `pause` / `resume` 只作用于该来源，来源不存在时明确拒绝。
- 缓存维护：`cache-inspect` / `cache-clean` 复用共享临时缓存入口并回传统计。
- 参数：`confidence`、`cooldown`、`targets`、`targetSamplingRate`、`modelKey` 五个键，运行中拒绝；`targetSourceId` 为空时作用于第一个来源。
- 不接收 `open-detector` / `close-detector`：生命周期命令属于 Windows 驻留角色，Android 端没有驻留子进程。

## 后台运行

- 有来源在运行时保持前台服务，界面退到后台、锁屏后推理继续；界面销毁不停止推理。
- 运行意图按来源持久化（与心跳 `monitoringExpected` 同义），进程被系统回收后服务按运行意图恢复来源；用户主动停止会清除意图。
- 与 Windows 驻留的差别：Android 不注册登录自启、不提供远程开关主程序的生命周期命令，也不改系统锁屏或唤醒设置。

## 界面

- 手机与平板按[多平台 UI 组件设计](72-多平台UI组件设计.md)组合。平板（可用宽度至少 840dp，或横屏且短边至少 600dp，且字号不超过 1.3）左右分栏：左列主预览，右列上下为来源列表与来源检查，各自独立滚动；手机改为顶部分区切换（预览 / 来源 / 检查）的单栏显示，三个分区都能到达，字号放大或极窄窗口只增加滚动、不裁切内容。
- 分区职责与 Windows 一致：主画面预览、全部有效来源列表（含缩略图、类型、状态与实际帧率）、当前来源检查。检查区依次为当前来源、运行状态与启停、检测规则（模型 / 目标 / 置信度 / 采样 / 冷却）、采集范围与遮罩、来源管理。
- 全局设置在当前窗口内覆盖为分类子界面：账号与连接、推理与模型、外观、后台运行、版本与维护；返回恢复原分区与原选中来源。
- 帮助页说明检测链、来源类型、参数生效时机、远控范围、报警投递与截图缓存边界，以及尚未真机验证的项；解释性文字不挤占主界面。
- 功能入口优先使用同一份 Lucide 官方图标；当前共享集合里没有对应线稿的动作（添加来源、下载、清空等）使用文字按钮，不手绘图标、不混用其它图标库。图标与配色沿用[统一 UI 规范](70-UI设计规范.md)与 `android-shared/` 主题，不另建页面主题。

## 本机数据

| 内容 | 位置 | 说明 |
|---|---|---|
| 账号会话与记住的账号密码 | 共享 `account_session`（Keystore） | 与会话分区绑定，排除系统备份与设备转移 |
| 模型缓存 | 应用 `files/models/<模型键>.onnx` | 按需从 `/models/<键>.onnx` 下载，长度校验通过才替换 |
| 来源与参数 | 按 `服务|账号|设备` 摘要分区的 SharedPreferences | 换账号不串用配置 |
| 报警发件箱 | `files/alerts/<分区>/alert-outbox.json` | 与账号分区一致，损坏时整文件隔离并告警 |
| 报警截图 | `files/alerts/<分区>/snapshots/` | 按天数 / 条目 / 总量清理 |

## 验证边界

构建、单元测试与未覆盖项以[验证记录](90-验证记录.md)为准。以下边界必须由真机验证，构建或模拟器通过都不代表已验收：NNAPI 在各厂商芯片上的实际加速与回退行为、持续推理的发热与功耗、长时间锁屏后的相机与前台服务存活、远程镜头在弱网下的帧率与丢帧、平板横竖屏布局的目检。

发行形态尚未定：当前构建把所有 ABI 打进一个 APK（含 ONNX Runtime 的原生库，Release 约 139 MB），是按 ABI 拆分、只保留 arm64 还是继续统一包，需要 owner 决定后再改打包方式。

## 源码索引

- 入口与主题：`MainActivity.kt`、`ui/theme/Theme.kt`、`AppConstants.kt`。
- 界面：`ui/MainScreen.kt`（主界面分区）、`ui/SourceListPane.kt`、`ui/SourceInspectionPane.kt`、`ui/PreviewCanvas.kt`、`ui/ParameterEditorDialog.kt`、`ui/MaskEditorDialog.kt`、`ui/GlobalSettingsOverlay.kt`、`ui/HelpPage.kt`、`ui/UiSupport.kt`。
- 总协调与远控路由：`runtime/InferenceRuntime.kt`。
- 单路运行：`source/SourceRuntime.kt`；来源配置与持久化：`source/SourceConfig.kt`、`source/SettingsStore.kt`。
- 检测链纯逻辑：`detect/`（`ArgbImage`、`LetterboxTransform`、`YoloOutputParser`、`MaskRegion`、`DetectionParams`、`CocoClassMap`）。
- 推理引擎与模型：`model/OnnxEngine.kt`、`model/ModelStore.kt`。
- 本机相机：`capture/LocalCameraSource.kt`。
- 网络：`net/ControlClient.kt`、`net/MediaClient.kt`、`net/RemoteFrame.kt`、`net/HeartbeatPayload.kt`。
- 报警：`alert/AlertEvent.kt`、`alert/AlertOutbox.kt`、`alert/AlertPayload.kt`、`alert/AlertSnapshotRenderer.kt`、`alert/AlertSnapshotStore.kt`。
- 前台服务：`service/InferenceService.kt`。
- 时间校正：`time/NtpSync.kt`。
