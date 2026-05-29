# Model Assets

模型文件（.onnx）不入版本控制，不随发行包分发。客户端首次启动或切换模型时从 Server 按需下载，本地缓存复用。

## 当前模型集合

### WinForms（YOLOv5）

- `yolov5nu_320.onnx`
- `yolov5nu_640.onnx`
- `yolov5su_320.onnx`
- `yolov5su_640.onnx`
- `yolov5mu_320.onnx`
- `yolov5mu_640.onnx`

### WPF（YOLO26）

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

## 模型按需下载

### Server 端点

- 路由：`/models/{filename}.onnx`，express.static，无需鉴权
- 源文件：`server/data/models/`（由 `scripts/release.js` 步骤 3 从各端 `Assets/` 源目录收集）

### 客户端本地缓存

| 端 | 路径 | 管理类 |
|---|---|---|
| WinForms | `%APPDATA%\VisionGuard\models\{modelKey}.onnx` | `Utils\ModelManager.cs` |
| WPF | `%APPDATA%\VisionGuard\models\{modelKey}.onnx` | `Utils\ModelManager.cs` |
| Android | `filesDir/models/{modelName}_{inputSize}.onnx` | `OnnxInferenceEngine.kt` → `downloadModel()` |

### 首次安装 / 旧版升级

启动时自动将旧路径（exe 同目录 `Assets\`）的模型迁移到 `%APPDATA%` 缓存目录，避免重复下载。

### 下载行为

- 默认模型选中后自动下载（StartMonitor 前检测，缺失则下载 + 进度条）
- 设置页模型选择处可手动下载任意模型，实时显示百分比
- Android Service 启动时模型缺失则前台通知提示"下载中"，下载失败提示"模型下载失败，请检查网络后重启"

## 输出格式

- WinForms YOLOv5：`[1,84,N]`
- WPF YOLO26：`[1,300,6]`
- Android Detector：YOLO26 格式，解析逻辑在 `YoloOutputParser.kt`

## COCO 映射真相源

- WinForms：`detector/windows-winforms/Data/CocoClassMap.cs`
- WPF：`detector/windows-wpf/Data/CocoClassMap.cs`
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

- 模型文件不入 git 版本控制（`.gitignore` 排除所有 `Assets/*.onnx` 和 `assets/models/*.onnx`）
- 模型不随发行包打包（`CopyToOutputDirectory=Never`，release.js zip 排除 `Assets/`）
- 类目中英文映射引用源码静态表，不手动复制文档
- 导出脚本、模型文件名、输入尺寸只在源码已存在时写入说明
