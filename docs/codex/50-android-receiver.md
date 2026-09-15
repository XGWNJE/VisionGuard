# Android Receiver

`receiver/android/` 是 Android 接收端，负责连接 Server、展示设备与告警、查看告警详情。

## 当前职责

- 接收 WS 消息
- 维护设备列表、离线状态和手动排序
- 展示告警列表与详情
- 拉取/缓存截图
- 前台保活
- 自动更新（警报页连接状态条手动检查，Service 启动自动检查仅通知）

## 关键文件

- `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/MainActivity.kt`
- `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/service/AlertForegroundService.kt`
- `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/service/NetworkMonitor.kt`
- `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/data/model/DeviceRegistryModels.kt`
- `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/data/repository/DeviceRegistryRepository.kt`
- `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/data/remote/CurrentDeviceInfoParser.kt`
- `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/data/remote/WebSocketClient.kt`
- `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/ui/screen/DeviceListScreen.kt`
- `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/ui/screen/AlertListScreen.kt`
- `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/ui/screen/AlertDetailScreen.kt`

## 设备列表策略

- 已连接过的设备会写入 `vg_device_registry` DataStore，不因实时列表暂时缺失而直接从 UI 消失。
- 本地手动排序优先于 Server 实时上报顺序；实时列表只刷新在线状态、名称、监控状态和参数能力。
- 新发现设备追加到现有手动顺序末尾。
- 缺席实时列表的历史设备显示为离线，并清除本地监控中状态。
- 在线设备不允许删除；离线设备允许在设备页侧滑删除。
- 长按设备卡片右上角拖拽手柄可以调整顺序。
- Server 实时设备记录必须符合当前协议并包含 `modelOptions`、`capabilities`、`components` 和 `sources`；旧格式或集合类型错误的记录直接丢弃并记日志，不进入设备状态列表。
- 父设备状态按全部来源聚合；只有 `isMonitoring && isReady && error` 为空的来源计为健康运行。四路中三路健康、一条未就绪或报错时显示“部分运行 3/4”，不能误报为全部运行。
- 多来源设备继续禁用旧整机启停入口；逐来源命令和参数调整携带稳定 `targetSourceId`。
- 设备声明 `sourceLimitExceeded` 时，设备卡直接说明“来源数量超过服务端上限（最多 N 路），超出部分未被上报”，因为此时列表里的是上一次成功上报的来源快照，不能当成实时状态。
- 逐来源“参数”入口在该来源运行时不可用：统一语义是先停止该来源再改配置，检测端对运行中的来源直接拒绝，因此不提供必然失败的入口。
- 冷却时间的取值范围与检测端统一为 1–300 秒。屏幕小不宜用滑块精确选值，因此交互是“常用档位 chip（5/10/30/60/120/300 秒）一点即中 + 自定义（数字键盘输入 1–300）”；设备当前值不在档位内时原样显示为“N 秒”，不吸附到档位——否则用户什么都没改，点“应用更改”也会下发一个新的冷却值。
- 状态文案与检测端统一：逐来源与设备级都叫“检测中”，没有可用来源时显示“未就绪”。
- 控制结果只接受显式 `phase=completed` 且包含 `requestId`、目标设备和命令的结构化回执；缺字段或仅转发阶段不会向用户显示成执行完成。
- Server 地址与隔离通道由 Gradle 注入 `BuildConfig.SERVER_URL` 和 `BuildConfig.CHANNEL`；测试 APK 可连接独立 Server 进程，不接收其他通道的设备、报警或控制结果。
- 显式 Gradle 属性或环境变量优先于本地默认配置，避免测试包误连线上；仅 `src/debug/AndroidManifest.xml` 允许本机模拟器使用明文 HTTP/WS，Release 清单不放宽 HTTPS/WSS。

## 已验证事实

- 包名为 `com.xgwnje.visionguard_android`
- 前台服务类型当前为 `remoteMessaging`
- UI 使用 Jetpack Compose
- 底部 Tab 为 `警报 / 设备`
- 警报页连接状态条显示服务器连接状态，点击后检查更新；无更新或失败也会报告当前版本号
- Android UI 视觉规范见 `docs/design/android-ui-guidelines.md`；旧 HTML/Pencil 探索稿不再作为实现依据
- 设置层使用 DataStore
- WS 消息模型与检测端/Server 对齐
- 本地也缓存 `targets`，默认值为 `person`
- 2026-07-08 模拟器验证：设备页启动、手动拖拽排序、强停重启后排序保留、断网离线卡片保留、离线侧滑删除、恢复联网后实时设备并回列表均通过；该验证不等同于完整检测端到接收端真实告警链路。
- 2026-09-11 MI 6X 真机验证：接收端认证成功，3 条缺少当前字段的旧协议设备记录被丢弃；应用保持运行且界面显示已连接。证据见 `artifacts/e2e/20260911-004712/`。
