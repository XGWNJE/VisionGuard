# Android Detector

`detector/android/` 是 Android 检测端，负责摄像头采集、推理、遮罩、告警和上传。

当前 UI 仍按毛坯状态看待；旧模块专属 Pencil 设计源已清理，不再作为实现依据。后续 UI 迁移应参考 `docs/design/android-ui-guidelines.md` 的颜色、状态和组件语义，但不照搬接收端信息架构。

## 当前职责

- CameraX 采集
- ONNX Runtime Mobile 推理
- 遮罩编辑与持久化
- 告警生成
- 与 Server 的 WS 通信
- 自动更新（Service 启动时发通知 + 设置页手动检查弹 AlertDialog）
- 模型按需下载（首次启动/切换时通过 OkHttp 从 Server 下载到 `filesDir/models/`）

## 关键约束

- 前台服务类型当前为 `camera`
- 当前实现不绑定 `Preview`，仅 `ImageAnalysis`
- 数码变焦是软件中心裁切逻辑，不是 CameraX API 缩放
- `SettingsRepository` 默认 `targets=person`、`selected_model=yolo26n`
- 模型文件**不打包**到 APK（`assets/models/` 为空目录），首次启动从 Server 下载
- Release 编译：`isMinifyEnabled=true` + `isShrinkResources=true` + R8/ProGuard
- NDK ABI 过滤：仅 `arm64-v8a`（节省 ~53 MB）

## 关键文件

- `detector/android/app/src/main/java/com/xgwnje/visionguard/MainActivity.kt`
- `detector/android/app/src/main/java/com/xgwnje/visionguard/service/DetectorForegroundService.kt`
- `detector/android/app/src/main/java/com/xgwnje/visionguard/service/MonitorService.kt`
- `detector/android/app/src/main/java/com/xgwnje/visionguard/data/repository/SettingsRepository.kt`
- `detector/android/app/src/main/java/com/xgwnje/visionguard/data/remote/WebSocketClient.kt`
- `detector/android/app/src/main/java/com/xgwnje/visionguard/inference/OnnxInferenceEngine.kt`
- `detector/android/app/src/main/java/com/xgwnje/visionguard/util/AutoUpdater.kt`
- `detector/android/app/build.gradle.kts`

## 已验证事实

- 包名为 `com.xgwnje.visionguard`
- 设置层使用 DataStore
- WS 心跳字段包含业务状态
- 自动更新检查通过 `/api/update` 查询，有更新弹通知（不自动下载）
- 设备能力会影响高分辨率模型可用性
- SoC 白名单逻辑单独在 `SocWhitelist.kt`
- 模型下载失败时前台通知提示"模型下载失败，请检查网络后重启"
