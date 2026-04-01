# VisionGuard

> 基于 YOLOv5 的轻量级屏幕区域人员检测与报警系统，专为低配 Windows 环境设计。

![Platform](https://img.shields.io/badge/platform-Windows%207%2B%20x64-blue)
![Framework](https://img.shields.io/badge/.NET-4.7.2-purple)
![Model](https://img.shields.io/badge/model-YOLOv5nu%20ONNX-orange)
![License](https://img.shields.io/badge/license-MIT-green)

---

## 功能概览

| 功能 | 说明 |
|------|------|
| 区域截图检测 | 拖拽选取任意屏幕区域，实时截图送入推理 |
| YOLOv5 推理 | ONNX Runtime CPU 推理，无需 GPU |
| 置信度过滤 | 滑块调节阈值（10%–90%），实时生效 |
| 循环铃声报警 | 触发后无限循环播放 WAV / 系统提示音，Space 键停止 |
| 推理自动暂停 | 报警期间暂停推理任务，降低 CPU 占用 |
| 冷却计时 | 停止铃声后重新起算冷却，防止立即重复触发 |
| 快照保存 | 报警瞬间自动截图并保存至 `%AppData%\VisionGuard\alerts\`（始终开启） |
| 托盘常驻 | 最小化后系统托盘运行，双击唤起主窗口 |
| 参数持久化 | 所有参数及窗口尺寸自动保存至 settings.ini，下次启动恢复 |

---

## 界面预览

Win11 Fluent 暗色风格，纯 GDI+ Owner-Draw，无第三方 UI 库，兼容 Windows 7。

```
┌─────────────────────────────────────────────────────────────┐
│  捕获区域   │                                               │
│  检测参数   │        实时视频预览 + 检测框叠加              │
│  性能参数   │                                               │
│  [开始][停止]│                                             │
├─────────────────────────────────────────────────────────────┤
│  ● 监控中    最后报警：14:23:07              推理 312 ms    │
└─────────────────────────────────────────────────────────────┘
```

---

## 快速开始

### 环境要求

- Windows 7 SP1 x64 或更高版本
- .NET Framework 4.7.2（Windows 10/11 已内置）
- 无需独立显卡

### 直接运行（推荐）

从 [Releases](../../releases) 页下载最新的 `VisionGuard_vX.X.X_x64.zip`，解压后直接运行 `VisionGuard.exe`，无需安装。

### 从源码构建

1. 克隆本仓库
2. 将 `yolov5nu.onnx` 模型文件放入 `Assets/` 目录（见 [Assets/ASSETS_README.md](Assets/ASSETS_README.md)）
3. 用 Visual Studio 2019+ 打开 `VisionGuard.csproj`，还原 NuGet 包后生成

### 使用流程

```
1. 点击「拖拽选区...」在屏幕上框选监控区域
2. 调整置信度阈值、冷却时间、铃声等参数（自动持久化）
3. 点击「▶ 开始」启动监控
4. 检测到人员时铃声自动循环 → 按 Space 键停止铃声并恢复推理
5. 点击「■ 停止」结束监控
```

---

## 参数说明

### 检测参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| 置信度阈值 | 0.45 | 低于此值的检测框被忽略（滑块范围 10%–90%） |
| 冷却时间 | 5 秒 | 从"用户按 Space 停止铃声"时刻起算，期间不重复报警（范围 1–300 s） |
| 警报铃声 | 开启 | 关闭后触发报警时仅记录日志，不播放声音 |
| 铃声文件 | 系统提示音 | 可选择自定义 WAV 文件；未选择则循环播放系统 Exclamation 音 |

### 性能参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| FPS | 2 | 截图推理频率（1–5），低配机器建议保持 2 |
| 线程数 | 2 | ONNX Runtime IntraOp 线程数（1–4） |

### 持久化存储

所有参数自动保存至 `%AppData%\VisionGuard\settings.ini`，包括：

- 置信度阈值、FPS、线程数、冷却时间
- 铃声路径、声音开关
- 窗口尺寸与最大化状态

---

## 项目结构

```
VisionGuard/
├── Assets/
│   ├── yolov5nu.onnx        # YOLOv5n 模型（ONNX，输入 640×640）
│   └── VisionGuard.ico      # 应用图标（多尺寸，嵌入 EXE）
├── Capture/
│   ├── ScreenCapturer.cs    # GDI BitBlt 屏幕截图
│   ├── GlobalKeyHook.cs     # 全局键盘钩子（Space 停止铃声）
│   └── NativeMethods.cs     # Win32 P/Invoke 声明
├── Inference/
│   ├── OnnxInferenceEngine.cs   # ONNX Runtime 推理封装
│   ├── ImagePreprocessor.cs     # 图像缩放 + 张量转换
│   └── YoloOutputParser.cs      # YOLOv5 输出解析 + NMS
├── Models/
│   ├── MonitorConfig.cs     # 运行时配置（阈值、铃声路径等）
│   ├── Detection.cs         # 单次检测结果
│   └── AlertEvent.cs        # 报警事件参数
├── Services/
│   ├── MonitorService.cs    # 主监控循环（Timer + ThreadPool）
│   └── AlertService.cs      # 冷却判断 + 循环铃声状态机
├── UI/
│   ├── CardPanel.cs             # 圆角卡片容器（替代 GroupBox）
│   ├── DarkSlider.cs            # 自绘滑块（替代 TrackBar）
│   ├── DarkStatusRenderer.cs    # 状态栏暗色渲染
│   ├── DetectionOverlayPanel.cs # 实时检测框叠加绘制（线程安全）
│   ├── FlatRoundButton.cs       # 圆角按钮（三态）
│   ├── OwnerDrawListBox.cs      # 日志列表（彩色行）
│   └── RegionSelectorForm.cs    # 屏幕区域拖拽选取
├── Utils/
│   ├── LogManager.cs        # ListBox 日志输出
│   └── SettingsStore.cs     # INI 持久化（%AppData%）
├── Form1.cs                 # 主界面（纯代码构建，不依赖 Designer）
└── VisionGuard.pen          # Pencil UI 设计稿
```

---

## 技术栈

| 层 | 技术 |
|----|------|
| UI 框架 | WinForms (.NET Framework 4.7.2) |
| UI 风格 | Win11 Fluent 暗色，纯 GDI+ Owner-Draw，无第三方库 |
| 推理引擎 | Microsoft.ML.OnnxRuntime 1.16.3 |
| 模型 | YOLOv5nu（ONNX 格式，输入 640×640） |
| 截图 | GDI+ BitBlt P/Invoke |
| 音频 | System.Media.SoundPlayer（WAV 循环） |
| 键盘监听 | SetWindowsHookEx WH_KEYBOARD_LL |
| 持久化 | 自定义 INI（%AppData%\VisionGuard\settings.ini） |

> **为什么是 OnnxRuntime 1.16.3？**  
> 1.17+ 起放弃 Windows 7 支持；1.16.3 是最后一个兼容 netstandard2.0 且在 Win7 x64 上验证可用的版本。

---

## 报警逻辑状态机

```
监控运行中（推理正常）
       │
       ▼ 检测到目标 & 冷却通过
  StartLoopAlarm()
       │
       ├─ SoundPlayer.PlayLooping() / 系统音循环线程
       ├─ MonitorService.Pause()  ← 推理暂停，降低 CPU 占用
       └─ 状态栏：⚠ 报警中 — 按 Space 停止
       │
       ▼ 用户按 Space 键
   StopAlarm()
       │
       ├─ 停止铃声 & 释放 SoundPlayer
       ├─ 冷却时间戳重置为当前时刻  ← 防止立即重触发
       ├─ MonitorService.Resume() ← 推理恢复
       └─ 状态栏：● 监控中
```

---

## 已知限制

- 纯 CPU 推理，单帧耗时约 300–800 ms（取决于硬件）
- 最高支持 5 FPS，不适合需要毫秒级响应的场景
- 铃声仅支持 WAV 格式（`SoundPlayer` 限制）

---

## 未来规划

- [ ] **跨端推送通知**：集成 PushDeer，报警时同步推送到手机（iOS / Android / macOS）
- [ ] **多目标类别选择**：UI 支持勾选检测车辆、动物等其他 COCO 类别
- [ ] **历史快照查看器**：内置浏览 AppData 报警截图的界面
- [ ] **HTTP Webhook**：报警时向自定义 URL 发送 POST 请求，对接自有平台

---

## License

MIT © [xgwnje](https://github.com/xgwnje)
