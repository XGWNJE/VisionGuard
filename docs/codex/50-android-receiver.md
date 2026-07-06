# Android Receiver

`receiver/android/` 是 Android 接收端，负责连接 Server、展示设备与告警、查看告警详情。

## 当前职责

- 接收 WS 消息
- 维护设备列表
- 展示告警列表与详情
- 拉取/缓存截图
- 前台保活
- 自动更新（警报页连接状态条手动检查，Service 启动自动检查仅通知）

## 关键文件

- `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/MainActivity.kt`
- `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/service/AlertForegroundService.kt`
- `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/service/NetworkMonitor.kt`
- `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/data/remote/WebSocketClient.kt`
- `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/ui/screen/DeviceListScreen.kt`
- `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/ui/screen/AlertListScreen.kt`
- `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/ui/screen/AlertDetailScreen.kt`

## 已验证事实

- 包名为 `com.xgwnje.visionguard_android`
- 前台服务类型当前为 `remoteMessaging`
- UI 使用 Jetpack Compose
- 底部 Tab 为 `警报 / 设备`
- 警报页连接状态条显示服务器连接状态，点击后检查更新；无更新或失败也会报告当前版本号
- 设置层使用 DataStore
- WS 消息模型与检测端/Server 对齐
- 本地也缓存 `targets`，默认值为 `person`
