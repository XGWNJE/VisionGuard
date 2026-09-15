# Android Detector

`detector/android/` 是 Android 检测端，负责摄像头采集、推理、遮罩、告警和上传。

> **当前状态：暂缓（2026-09-15，路线图决策 19）**。本端不纳入当前实施顺序，也不参与验收结论；等 Server、Windows 两个检测端与 Android 接收端的整条链路完整实现并验收后，再实现本端，并一次性把三端协议与语义统一到同一套约定。本文以下内容是暂缓时的实现快照，不代表当前可用状态。恢复实施的第一批修复项：认证缺少 `channel` 字段导致当前协议下无法认证；缺少来源（source）维度与逐来源命令、配置、告警来源字段。

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
- 正式包默认不把模型打包到 APK；加载器支持先从 `assets/models/` 复制确定性模型，未提供 asset 时再从 Server 下载到 `filesDir/models/`
- Release 编译：`isMinifyEnabled=true` + `isShrinkResources=true` + R8/ProGuard
- NDK ABI 过滤：仅 `arm64-v8a`（节省 ~53 MB）
- Android 推理默认请求 ONNX Runtime `NNAPI`；API 29+ 使用 `CPU_DISABLED` + `USE_NCHW` 注册 NNAPI，不能创建时显式回退 CPU 并保留原因
- NNAPI session 完成至少 3 次实际推理后结束 profiling，解析 profile 中的 `NnapiExecutionProvider`，并在 `filesDir/inference-backend-evidence.json` 写入 provider 证据和 CPU/NNAPI 事件计数；provider 命中只能确认 NNAPI 分区，只有另有非 `reference/CPU` 执行设备证据时才能标记硬件执行确认

## 关键文件

- `detector/android/app/src/main/java/com/xgwnje/visionguard/MainActivity.kt`
- `detector/android/app/src/main/java/com/xgwnje/visionguard/service/DetectorForegroundService.kt`
- `detector/android/app/src/main/java/com/xgwnje/visionguard/service/MonitorService.kt`
- `detector/android/app/src/main/java/com/xgwnje/visionguard/data/repository/SettingsRepository.kt`
- `detector/android/app/src/main/java/com/xgwnje/visionguard/data/remote/WebSocketClient.kt`
- `detector/android/app/src/main/java/com/xgwnje/visionguard/inference/OnnxInferenceEngine.kt`
- `scripts/prepare-android-nnapi-model.py`
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
- 发布准备会将 YOLO26 模型的 NNAPI 不兼容 `Split` 形式改写为等价 `Slice` 形式，逻辑文件名不变；模型资产边界和脚本职责见[模型资产](35-model-assets.md)

## 验证边界

当前 Android 单测、Debug 构建、启动 smoke 和小米 15 上使用归一化 `yolo26n_320` 的真实相机推理/NNAPI provider 证据可以分别报告。MI 6X（SDM660/Adreno 512）实测证明原始公网模型因 `Split` 回退 CPU，约 `157–179 ms/次`；归一化模型虽进入 `NnapiExecutionProvider`，但系统编译目标是 `nnapi-reference`，约 `471–499 ms/次`，属于更慢的参考 CPU 路径而非硬件加速。QNN/NCNN、可靠的 NNAPI 设备识别、长时温升、精度矩阵、完整检测端→Server→接收端报警链和真机 UI 目检尚未验收。当前未执行正式版本发布，因此公网现有模型不因本地证据自动更新。
