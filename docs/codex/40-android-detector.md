# 视觉检测（Android）

`detector/android/` 是视觉检测（Android），负责摄像头采集、推理、遮罩、告警和上传。

> **当前状态：暂缓**。整端源码保留，以下描述其当前实现，不代表已验收可用。本端认证消息缺少 `channel`，在当前视觉中继协议下无法认证；也未实现来源维度、逐来源命令和配置。本端不纳入当前交付结论。

当前界面尚未作为交付方案验收。当前 Android UI 规范见[设计说明](../design/android-ui-guidelines.md)，该检测端尚未采用接收端界面方案。

## 当前职责

- CameraX 采集
- ONNX Runtime Mobile 推理
- 遮罩编辑与持久化
- 告警生成
- 与视觉中继的 WS 通信
- 自动更新（Service 启动时发通知；控制台界面手动检查并弹 AlertDialog）
- 模型按需下载（首次启动/切换时通过 OkHttp 从视觉中继下载到 `filesDir/models/`）

## 关键约束

- 前台服务类型当前为 `camera`
- 当前实现不绑定 `Preview`，仅 `ImageAnalysis`
- 数码变焦是软件中心裁切逻辑，不是 CameraX API 缩放
- `SettingsRepository` 默认 `targets=person`、`selected_model=yolo26n`
- 正式包默认不把模型打包到 APK；加载器支持先从 `assets/models/` 复制确定性模型，未提供 asset 时再从视觉中继下载到 `filesDir/models/`
- Release 编译：`isMinifyEnabled=true` + `isShrinkResources=true` + R8/ProGuard
- NDK ABI 过滤：仅 `arm64-v8a`（节省 ~53 MB）
- Android 推理默认请求 ONNX Runtime `NNAPI`；API 29+ 使用 `CPU_DISABLED` + `USE_NCHW` 注册 NNAPI，不能创建时显式回退 CPU 并保留原因
- NNAPI session 完成至少 3 次实际推理后结束 profiling，解析 profile 中的 `NnapiExecutionProvider`，并在 `filesDir/inference-backend-evidence.json` 写入 provider 证据和 CPU/NNAPI 事件计数；provider 命中只能确认 NNAPI 分区，只有另有非 `reference/CPU` 执行设备证据时才能标记硬件执行确认

## 关键文件

- `detector/android/app/src/main/java/com/xgwnje/visionguard/detector/MainActivity.kt`
- 界面与标定：`detector/android/app/src/main/java/com/xgwnje/visionguard/detector/ui/console/`
- `detector/android/app/src/main/java/com/xgwnje/visionguard/detector/service/DetectorForegroundService.kt`
- `detector/android/app/src/main/java/com/xgwnje/visionguard/detector/service/MonitorService.kt`
- `detector/android/app/src/main/java/com/xgwnje/visionguard/detector/data/repository/SettingsRepository.kt`
- `detector/android/app/src/main/java/com/xgwnje/visionguard/detector/data/remote/WebSocketClient.kt`
- `detector/android/app/src/main/java/com/xgwnje/visionguard/detector/inference/OnnxInferenceEngine.kt`
- `scripts/prepare-android-nnapi-model.py`
- `detector/android/app/src/main/java/com/xgwnje/visionguard/detector/util/AutoUpdater.kt`
- `detector/android/app/build.gradle.kts`

## 实现事实

- 包名为 `com.xgwnje.visionguard.detector`
- 设置层使用 DataStore
- WS 心跳字段包含业务状态
- 自动更新检查通过 `/api/update` 查询，有更新弹通知（不自动下载）
- 设备能力会影响高分辨率模型可用性
- SoC 白名单逻辑单独在 `SocWhitelist.kt`
- 模型下载失败时前台通知提示"模型下载失败，请检查网络后重启"
- 发布准备会将 YOLO26 模型的 NNAPI 不兼容 `Split` 形式改写为等价 `Slice` 形式，逻辑文件名不变；模型资产边界和脚本职责见[模型资产](35-model-assets.md)

## 验证边界

本轮构建与人工/真机未覆盖项见[验证报告](90-verification-report.md)。NNAPI provider 命中不能单独证明硬件加速；本端在当前协议下无法认证。
