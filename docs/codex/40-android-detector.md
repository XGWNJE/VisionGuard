# Android Detector

`detector/android/` 是 Android 检测端，负责摄像头采集、推理、遮罩、告警和上传。

## 当前职责

- CameraX 采集
- ONNX Runtime Mobile 推理
- 遮罩编辑与持久化
- 告警生成
- 与 Server 的 WS 通信
- 自动更新

## 关键约束

- 前台服务类型当前为 `camera`
- 当前实现不绑定 `Preview`
- 仅 `ImageAnalysis`
- 数码变焦是软件中心裁切逻辑，不是 CameraX API 缩放
- `SettingsRepository` 默认 `targets=person`、`selected_model=yolo26n`

## 关键文件

- `detector/android/app/src/main/java/com/xgwnje/visionguard/MainActivity.kt`
- `detector/android/app/src/main/java/com/xgwnje/visionguard/service/DetectorForegroundService.kt`
- `detector/android/app/src/main/java/com/xgwnje/visionguard/service/MonitorService.kt`
- `detector/android/app/src/main/java/com/xgwnje/visionguard/data/repository/SettingsRepository.kt`
- `detector/android/app/src/main/java/com/xgwnje/visionguard/data/remote/WebSocketClient.kt`
- `detector/android/app/src/main/java/com/xgwnje/visionguard/inference/OnnxInferenceEngine.kt`

## 已验证事实

- 包名为 `com.xgwnje.visionguard`
- 设置层使用 DataStore
- WS 心跳字段包含业务状态
- 低版本会触发更新逻辑
- 设备能力会影响高分辨率模型可用性
- SoC 白名单逻辑单独在 `SocWhitelist.kt`
