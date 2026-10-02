# 控制台与通知节点

跨端管理由 `receiver/web/` 的 Web 控制台承担，后台收件与声音由 `notifier/android/` 的 VisionGuard 通知节点承担；`receiver/android/` 保留当前 Android 控制台实现。职责见[项目概览](10-project-overview.md)，证据及未覆盖项只在[验证报告](90-verification-report.md)维护。

## Web 控制台

- 服务同源 `/console/`，以 `console/console/web` 身份连接。默认手动输入登记凭据，外部使用 HTTPS/WSS，回环允许 HTTP/WS；显式开启的隔离测试服务自动分配临时身份，允许私有 IPv4 局域网 HTTP/WS，刷新或重新打开自动登录。凭据仅驻留页面内存，多设备测试身份互不替换；操作与历史、截图仍按控制台身份认证。
- 平板和电脑显示侧栏、节点列表与详情；手机使用顶部导航及列表/详情切换。搜索名称或 ID，按视觉、传感器、通知类型筛选；登记但未连接的节点保留为离线。
- 节点和视觉来源分别显示实际状态、当前参数及可用操作。依据能力显示启停、停止报警、打开/关闭检测程序和配置；通知节点不提供检测控制。视觉来源属于父设备，携带来源 ID 操作；来源运行时先暂停再修改参数。
- 视觉配置提供已有模型、置信度、冷却、采样率和目标；传感器提供协议允许的置信度及冷却。保存后以检测节点状态与 completed 执行回执核实，不提前把转发显示为成功。
- 事件列表包含最近 100 条历史及实时事件，区分视觉、传感器与中断类型。无图事件显示文字；截图独立到达后按事件 ID 关联，携带本次凭据从同源拉取，退出后释放临时图片。历史不会触发声音。
- 「设置 → 统一时间」修改通道内的告警显示标准，默认北京时间（UTC+8），可切为 UTC（UTC+0）；服务保存后向 Web和通知节点推送，重连重新同步。节点响应时间、告警列表与详情使用相同标准，详情显示完整日期和时区。
- 为每个已登记通知节点分配全部或指定检测节点/视觉来源；离线身份仍可配置，指定空集合表示不接收。范围由服务端保存，通知节点下次认证读取。
- 系统浅色/深色共用布局与语义色；规范见[统一界面](../design/unified-ui.md)。实现入口为 `src/main.tsx`、`src/useRelay.ts`、`src/protocol.ts`、`src/style.css`。

## VisionGuard 通知节点

源码为 `notifier/android/`，工程名 `VisionGuard.Notifier.Android`，独立包名 `com.xgwnje.visionguard.notifier`。应用专用于 VG 报警，不包含本机通知关键词监听、应用过滤、延时报警或 Vigil 的 GitHub 更新入口。原 Vigil 的代码与现有改动保留在原仓库，不合并分支、不覆盖安装或迁移数据。

- 使用 `notifier/notification/android` 登记身份；Release 只接受 WSS `/ws` 地址，Debug 明文仅限本机及模拟器宿主。保存连接后重新启用，凭据仅存应用私有 `relay_credentials`，排除备份及设备转移。
- `NotificationNodeService` 维持独立前台连接；实时事件按稳定 ID 与去重标识一起落盘后才回收件确认。队列满或写入失败不确认，允许原期限内重试；确认后重复事件不再入队。
- 普通 Android `Service` 类型的 `AlarmPlaybackService` 消费持久化 FIFO 队列，无需通知使用权。声音来自系统铃声、内置目标提醒或导入/录音库，播放 1–10 次；确认、达到次数或播放失败结束当前项，再进入下一项。持久化失败保留当前项，不能把收件回执当作声音证明。
- 最近有效服务响应后 45 秒无响应，本地产生本次中断的一条报警；连接尝试不重置计时，恢复响应后监测下一次中断。
- 统一时间标准由控制台设定、服务同步并在本机保存；最近记录、完整历史和弹窗显示同一时区。应用的系统浅深色跟随设备。
- 版本直接读取根 `VERSION`；共享 VG 的私有 Android 签名配置，缺签名材料的 Release 构建拒绝执行。正式发布流水线尚未接入此新组件，不对接 Vigil 的发行版。

## 保留的 Android 控制台

以下描述 `receiver/android/` 当前源码，Web 新界面不改变其安装身份与既有功能。

## 当前职责

- 接收 WS 消息
- 维护设备列表、离线状态和手动排序
- 展示告警列表与详情
- 按节点声明的能力提供设备/来源控制与参数配置
- 拉取/缓存截图
- 前台保活
- 自动更新（警报页连接状态条手动检查，Service 启动自动检查仅通知）

## 关键文件

- `receiver/android/app/src/main/java/com/xgwnje/visionguard/receiver/MainActivity.kt`
- `receiver/android/app/src/main/java/com/xgwnje/visionguard/receiver/service/AlertForegroundService.kt`
- `receiver/android/app/src/main/java/com/xgwnje/visionguard/receiver/service/NetworkMonitor.kt`
- `receiver/android/app/src/main/java/com/xgwnje/visionguard/receiver/data/model/DeviceRegistryModels.kt`
- `receiver/android/app/src/main/java/com/xgwnje/visionguard/receiver/data/repository/DeviceRegistryRepository.kt`
- `receiver/android/app/src/main/java/com/xgwnje/visionguard/receiver/data/remote/CurrentDeviceInfoParser.kt`
- `receiver/android/app/src/main/java/com/xgwnje/visionguard/receiver/data/remote/WebSocketClient.kt`
- `receiver/android/app/src/main/java/com/xgwnje/visionguard/receiver/ui/screen/DeviceListScreen.kt`
- `receiver/android/app/src/main/java/com/xgwnje/visionguard/receiver/ui/screen/AlertListScreen.kt`
- `receiver/android/app/src/main/java/com/xgwnje/visionguard/receiver/ui/screen/AlertDetailScreen.kt`

## 设备列表策略

- 已连接过的设备会写入 `vg_device_registry` DataStore，不因实时列表暂时缺失而直接从 UI 消失。
- 本地手动排序优先于视觉中继实时上报顺序；实时列表只刷新在线状态、名称、监控状态和参数能力。
- 新发现设备追加到现有手动顺序末尾。
- 缺席实时列表的历史设备显示为离线，并清除本地监控中状态。
- 在线设备不允许删除；离线设备允许在设备页侧滑删除。
- 长按设备卡片右上角拖拽手柄可以调整顺序。
- 视觉中继实时设备记录必须符合当前协议并包含 `modelOptions`、`capabilities`、`components` 、`sources` 和独立的 `role/nodeType/platform`；旧格式或集合类型错误的记录直接丢弃并记日志，不进入设备状态列表。
- 父设备状态按全部来源聚合；只有 `isMonitoring && isReady && error` 为空的来源计为健康运行。四路中三路健康、一条未就绪或报错时显示“部分运行 3/4”，不能误报为全部运行。
- Windows 设备及声明 `source-control` 的设备不显示旧整机启停和整机参数按钮；逐来源命令和参数调整携带稳定 `targetSourceId`。声明 `monitor-control` 且未声明逐来源能力的节点使用节点级控制入口；通知节点不显示检测控制。
- VisionGuard 视觉节点只上报已绑定窗口或屏幕选区的来源；已绑定但暂时失联的来源仍保留在列表并显示异常状态，不能用 `isReady=false` 当作未绑定。来源卡用紧凑图标操作，状态独占一行。
- 设备声明 `sourceLimitExceeded` 时，设备卡直接说明“来源数量超过服务端上限（最多 N 路），超出部分未被上报”，因为此时列表里的是上一次成功上报的来源快照，不能当成实时状态。
- 逐来源“参数”入口在该来源运行时不可用：统一语义是先停止该来源再改配置，检测端对运行中的来源直接拒绝，因此不提供必然失败的入口。
- 冷却时间的取值范围与检测端统一为 1–300 秒。屏幕小不宜用滑块精确选值，因此交互是“常用档位 chip（5/10/30/60/120/300 秒）一点即中 + 自定义（数字键盘输入 1–300）”；设备当前值不在档位内时原样显示为“N 秒”，不吸附到档位——否则用户什么都没改，点“应用更改”也会下发一个新的冷却值。
- 状态文案与检测端统一：逐来源与设备级都叫“检测中”，没有可用来源时显示“未就绪”。
- 控制结果只接受显式 `phase=completed` 且包含 `requestId`、目标设备和命令的结构化回执；缺字段或仅转发阶段不会向用户显示成执行完成。
- 视觉中继地址与隔离通道由 Gradle 注入 `BuildConfig.SERVER_URL` 和 `BuildConfig.CHANNEL`；测试 APK 可连接独立视觉中继进程，不接收其他通道的设备、报警或控制结果。
- 显式 Gradle 属性或环境变量优先于本地默认配置，避免测试包误连线上；仅 `src/debug/AndroidManifest.xml` 允许本机模拟器使用明文 HTTP/WS，Release 清单不放宽 HTTPS/WSS。

## 实时事件

- 无图传感器和中断事件显示 `summary`，详情页显示事件文字，不要求截图或检测框。
- 到期事件仍可作为历史记录查看，但不会发送新的声音通知；历史同步不触发声音。
- 控制台继续使用 `console` 身份，不能代替通知节点发送收件确认。VisionGuard 通知节点负责后台收件和声音，控制台回执展示不能替代声音证据。

## 实现事实

- 包名为 `com.xgwnje.visionguard.receiver`
- 前台服务类型当前为 `remoteMessaging`
- UI 使用 Jetpack Compose
- 底部 Tab 为 `警报 / 设备`
- 警报页连接状态条显示服务器连接状态，点击后检查更新；无更新或失败也会报告当前版本号
- Android UI 视觉规范见[设计规范](../design/android-ui-guidelines.md)
- 设置层使用 DataStore
- WS 消息模型与检测端/视觉中继对齐
- 本地也缓存 `targets`，默认值为 `person`

## 验证边界

模拟器、真机启动和协议记录统一见[验证报告](90-verification-report.md)；局部 UI 或认证通过不能替代完整检测端到接收端的真实告警链路验收。
