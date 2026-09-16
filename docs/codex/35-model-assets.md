# Model Assets

模型文件（.onnx）不入版本控制，不随发行包分发。客户端首次启动或切换模型时从 Server 按需下载，本地缓存复用。

## 当前模型集合

### Windows 检测端 legacy 档（Win7 SP1 x64，YOLOv5）

legacy 档的 ONNX Runtime 原生库是 1.1.0，算子覆盖不足以支撑 YOLO26，因此固定使用 YOLOv5 系列：

- `yolov5nu_320.onnx`
- `yolov5nu_640.onnx`
- `yolov5su_320.onnx`
- `yolov5su_640.onnx`
- `yolov5mu_320.onnx`
- `yolov5mu_640.onnx`

### Windows 检测端 modern 档（Win10/11，YOLO26）

- `yolo26n_320.onnx`
- `yolo26n_640.onnx`
- `yolo26s_320.onnx`
- `yolo26s_640.onnx`
- `yolo26m_320.onnx`
- `yolo26m_640.onnx`

### Android Detector（YOLO26）

- `yolo26n_320.onnx`
- `yolo26n_640.onnx`
- `yolo26s_320.onnx`
- `yolo26s_640.onnx`

### Android NNAPI 兼容处理

- YOLO26 原始导出模型中的 `Split` 节点在小米 15 的 NNAPI 路径上可能能完成能力分区，却在 session 创建时失败；发布准备阶段使用 `scripts/prepare-android-nnapi-model.py` 将带常量切分输入的 `Split` 改写为等价 `Slice` 节点。
- 改写保留原有逻辑模型名和下载路由，Server 最终仍提供上述文件名；modern 档的 CPU、modern 档的 DirectML 和 Android/NNAPI 均使用同一份归一化后的 YOLO26 文件。
- 脚本要求 Python 与 `onnx`、`numpy` 包；正式发布脚本在复制 YOLO26 模型后自动执行，准备失败时中止发布，不静默提供未验证的原始模型。
- 该处理不是量化，不改变模型输入/输出契约；发布前仍须以目标模型的 ONNX 校验和对应端推理烟测确认语义。
- Android 加载器会在本地缓存缺失时先检查 `assets/models/{filename}`，再访问 Server；正式包默认不内置模型，Debug/E2E 可临时注入确定性模型，适用于禁止 `run-as` 的真机验证。

## 模型按需下载

### Server 端点

- 路由：`/models/{filename}.onnx`，express.static，无需鉴权
- 源文件：`server/data/models/`（由 `scripts/publish-release.ps1` 从各端模型源目录收集）

### 客户端本地缓存

| 端/档位 | 路径 | 管理类 |
|---|---|---|
| Windows 检测端（legacy 与 modern 两档共用同一缓存目录，清单按档位切换） | `%APPDATA%\VisionGuard\models\{modelKey}.onnx` | `Utils\ModelManager.cs` |
| Android | `filesDir/models/{modelName}_{inputSize}.onnx` | `OnnxInferenceEngine.kt` → 内置 asset（存在时）→ `downloadModel()` |

### 首次安装 / 旧版升级

启动时自动将旧路径（exe 同目录 `Assets\`）的模型迁移到 `%APPDATA%` 缓存目录，避免重复下载；这只是升级兼容路径，不是新的模型分发入口。

### 下载行为

- 默认模型选中后自动下载（StartMonitor 前检测，缺失则下载 + 进度条）
- 设置页模型选择处可手动下载任意模型，实时显示百分比
- Android Service 启动时模型缺失则前台通知提示"下载中"，下载失败提示"模型下载失败，请检查网络后重启"

## 输出格式

- Windows 检测端 legacy 档 YOLOv5：`[1,84,N]`
- Windows 检测端 modern 档 YOLO26：`[1,300,6]`
- Android Detector：YOLO26 格式，解析逻辑在 `YoloOutputParser.kt`

## COCO 映射真相源

- Windows 检测端：`detector/windows-wpf/Data/CocoClassMap.cs`
- Android Receiver：`receiver/android/.../CocoClassMap.kt`
- Android Detector：`YoloOutputParser.kt` 内维护标签数组

## 统一目标子集

当前多端对齐的 6 类监控目标：

- `person`
- `bicycle`
- `car`
- `motorcycle`
- `bus`
- `truck`

## 维护规则

- 模型文件不入 git 版本控制（`.gitignore` 排除 Windows `Assets/*.onnx`；Android 当前没有模型 assets 目录）。
- Windows 检测端项目文件显式将模型 `CopyToOutputDirectory=Never`；正式压缩阶段仍排除 `Assets/`，形成双重边界。
- Android 检测端不把模型放进 APK 的 assets；当前没有模型文件，首次启动下载到 `filesDir/models/`。
- 类目中英文映射引用源码静态表，不手动复制文档
- 导出脚本、模型文件名、输入尺寸只在源码已存在时写入说明
