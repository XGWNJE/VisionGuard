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

## 已验证事实

- 包名为 `com.xgwnje.visionguard_android`
- 前台服务类型当前为 `remoteMessaging`
- UI 使用 Jetpack Compose
- 底部 Tab 为 `警报 / 设备`
- 警报页连接状态条显示服务器连接状态，点击后检查更新；无更新或失败也会报告当前版本号
- 设置层使用 DataStore
- WS 消息模型与检测端/Server 对齐
- 本地也缓存 `targets`，默认值为 `person`
- 2026-07-08 模拟器验证：设备页启动、手动拖拽排序、强停重启后排序保留、断网离线卡片保留、离线侧滑删除、恢复联网后实时设备并回列表均通过；该验证不等同于完整检测端到接收端真实告警链路。
