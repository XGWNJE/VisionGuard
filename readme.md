# VisionGuard

**屏幕区域人员检测 + 循环报警**，专为低配 Windows 设计，开箱即用。

![Platform](https://img.shields.io/badge/Windows%207%2B%20x64-0078D4?style=flat-square&logo=windows&logoColor=white)
![.NET](https://img.shields.io/badge/.NET%204.7.2-512BD4?style=flat-square&logo=dotnet&logoColor=white)
![Model](https://img.shields.io/badge/YOLOv5nu%20320×320-FF6F00?style=flat-square)
![License](https://img.shields.io/badge/MIT-22C55E?style=flat-square)

---

框选任意屏幕区域 → YOLOv5 CPU 推理 → 检测到人员时循环播放报警音，按 `Space` 停止。全程无需 GPU，兼容 Windows 7。

## 快速开始

从 [Releases](../../releases) 下载 `VisionGuard_vX.X.X_x64.zip`，解压直接运行 `VisionGuard.exe`。

1. 点击 **「拖拽选区」** 框选监控区域
2. 调整置信度、冷却时间等参数（自动保存）
3. 点击 **「▶ 开始」** 启动监控
4. 检测到人员 → 铃声循环响起 → 按 `Space` 停止并恢复推理

## 功能

| | |
|---|---|
| 区域检测 | 拖拽框选任意屏幕区域，实时截图推理 |
| 循环报警 | 触发后无限循环播放 WAV / 系统音，`Space` 停止 |
| 推理暂停 | 报警期间自动暂停推理，降低 CPU 占用 |
| 冷却机制 | 停止后重置冷却计时，防止立即重触发 |
| **多类别监控** | 支持选择任意 COCO 类别组合（person / car / dog 等），详见 `Assets/COCO_CLASSES.md` |
| 快照保存 | 报警瞬间自动截图至 EXE 同目录 `alerts\`（可在 `MonitorConfig.SaveAlertSnapshot` 中关闭，默认开启） |
| 托盘运行 | 最小化后系统托盘常驻，双击唤起 |
| 参数持久化 | 所有设置（含窗口尺寸）自动保存，下次启动恢复 |

## 参数

**持久化位置**：`settings.ini` 与 `alerts\` 目录均在 EXE 同级目录下。

**监控类别**：`WatchedClasses`（留空 = 检测全部；填类名 = 只检测指定类别，如 `person,car,dog`），类名参考 `Assets/COCO_CLASSES.md`

**性能**：FPS（默认 2，范围 1–5）、推理线程数（默认 2，范围 1–4）

> 低配机器推荐保持 FPS = 2，单帧推理约 300–800 ms（纯 CPU）。

## 从源码构建

```
git clone <repo>
# 将 yolov5nu.onnx 放入 Assets/
# 用 Visual Studio 2019+ 打开 VisionGuard.csproj，还原 NuGet，生成即可
```

## 技术栈

| | |
|---|---|
| UI | WinForms .NET 4.7.2，Win11 Fluent 暗色，纯 GDI+ Owner-Draw |
| 推理 | ONNX Runtime Managed 1.16.3 + native 1.1.0（Win7 兼容组合） |
| 模型 | YOLOv5nu，ONNX 格式，输入 320×320 |
| 音频 | `SoundPlayer` WAV 循环 |
| 键盘 | `SetWindowsHookEx WH_KEYBOARD_LL` 全局钩子 |

## 项目结构

```
VisionGuard/
├── Assets/          # 模型 (.onnx)、图标、COCO 类别列表
├── Capture/         # 屏幕截图 + 全局键盘钩子
├── Inference/       # ONNX 推理 + 图像预处理 + NMS
├── Models/          # 数据结构（配置、检测结果、报警事件）
├── Services/        # 监控循环 + 报警状态机
├── UI/              # 自绘控件（圆角卡片、滑块、按钮、日志列表）
├── Utils/           # 日志管理 + INI 持久化
└── Form1.cs         # 主界面（纯代码，不依赖 Designer）
```

## 路线图

- [ ] PushDeer 手机推送通知
- [x] **多目标类别选择**（车辆、动物等 COCO 类别）— 见 `Assets/COCO_CLASSES.md`
- [ ] HTTP Webhook 对接自有平台
- [ ] 内置历史快照查看器

---

MIT © [xgwnje](https://github.com/xgwnje)
