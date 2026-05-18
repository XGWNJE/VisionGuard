# Windows Detector

Windows 检测端分两条线：WinForms 主力线和 WPF 视觉升级线。

## WinForms

- 路径：`detector/windows-winforms/`
- 框架：`.NET Framework 4.7.2`
- UI：WinForms，事件驱动
- 模型：YOLOv5 系列
- WS：`websocket-sharp`

## WPF

- 路径：`detector/windows-wpf/`
- 框架：`.NET 9`
- UI：WPF + MVVM
- 模型：YOLO26 系列
- WS：`System.Net.WebSockets`

## 共通链路

- 截图/窗口捕获
- 遮罩涂黑
- 预处理
- ONNX 推理
- 输出解析
- 冷却判断
- 告警推送

## 关键差异

- WinForms 走 `Form1` partial class 架构
- WPF 走 `ViewModels/` + `Views/`
- WinForms 当前使用 `settings.ini`
- WPF 当前使用同语义的本地设置存储
- WinForms 与 WPF 的模型输出格式不同，不应混写说明

## WPF 当前实现重点

- `App.xaml.cs` 启动时触发 NTP 同步和全局异常处理
- `MainViewModel` 持有 `MonitorViewModel`、`SettingsViewModel`、`ServerViewModel`
- `MonitorService` 用线程池定时器驱动捕获、推理、告警和 UI 更新
- `ServerPushService` 负责认证、心跳、告警推送、命令和截图按需传输
- `SettingsStore` 保持与旧版 `settings.ini` 兼容的 key 语义

## 从旧迁移说明中保留的有效约束

- WPF 仍然依赖 `System.Drawing.Common`，因为捕获和截图标注仍走 GDI 路径
- WPF 命令可用性刷新依赖手动 `RaiseCanExecuteChanged()`
- 遮罩是相对坐标，切换捕获范围后旧遮罩需要清空
- 旧迁移文档里的“待完成事项”不能直接视为当前缺陷，必须以源码再核对

## 已验证事实

- WinForms 输出解析包含 NMS
- 两条线都保留了自动更新逻辑
- 两条线都存在 NTP 同步与心跳同步相关代码
- 两条线都维护与 Android 对齐的 6 类目标子集：`person`、`bicycle`、`car`、`motorcycle`、`bus`、`truck`

## 文档迁移说明

- 原 `detector/windows-wpf/MIGRATION_PROGRESS.md` 的有效内容已迁入本文件
- 原 `Assets/ASSETS_README.md` 与 `Assets/COCO_CLASSES.md` 的通用内容已迁入模型资源文档
