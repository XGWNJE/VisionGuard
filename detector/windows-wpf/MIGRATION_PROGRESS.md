# VisionGuard WPF 检测端 — 开发者速查

> 分支：`feat/wpf-migration-kimi`  
> 框架：`net9.0-windows` + WPF  
> 旧项目：`detector/windows/`（.NET Framework 4.7.2 + WinForms，保留为参考）  
> 最后更新：2026-05-04

---

## 一、模块地图

```
VisionGuard/
├── App.xaml(.cs)              ← 启动入口、NTP 同步、全局异常处理
├── VisionGuard.csproj         ← net9.0-windows, x64, UseWPF, 3 个 NuGet 依赖
├── Themes/DarkTheme.xaml      ← 唯一主题文件（颜色令牌、控件样式、BoolToVis 转换器）
│
├── Capture/                   ← GDI 截屏层（System.Drawing 依赖）
│   ├── ScreenCapturer.cs      ← BitBlt 屏幕区域捕获
│   ├── WindowCapturer.cs      ← PrintWindow 窗口捕获 + 子区域裁剪
│   ├── WindowEnumerator.cs    ← EnumWindows 枚举 + DWM 边界 + 黑名单过滤
│   ├── WindowInfo.cs          ← 窗口信息 DTO (Handle/Title/ClassName/Bounds)
│   └── NativeMethods.cs       ← user32/gdi32/dwmapi P/Invoke
│
├── Inference/                 ← ONNX 推理链（ThreadPool 执行）
│   ├── OnnxInferenceEngine.cs ← InferenceSession 封装，线程数 2
│   ├── ImagePreprocessor.cs   ← Bitmap → 320×320 CHW float[] 张量
│   ├── YoloOutputParser.cs    ← [1,300,6] 输出 → Detection[]，前5按置信度排序
│   └── MaskApplier.cs         ← 相对坐标遮罩 in-place 涂黑（推理前调用）
│
├── Services/                  ← 业务服务层
│   ├── MonitorService.cs      ← 主循环：ThreadPool Timer → 截图→遮罩→推理→报警→UI事件
│   ├── AlertService.cs        ← 冷却锁判定 + 截图本地缓存(LRU 1GB/7天/5000条) + 报警事件
│   └── ServerPushService.cs   ← WS 单状态源事件循环：认证/心跳/报警推送/命令/截图按需
│
├── Models/                    ← 数据对象
│   ├── MonitorConfig.cs       ← 捕获模式/阈值/FPS/遮罩/目标类别 配置 DTO
│   ├── Detection.cs           ← 单检测结果 (ClassId/Label/Confidence/BoundingBox)
│   ├── AlertEvent.cs          ← 报警事件 (AlertId/Snapshot/Detections/Timings)
│   └── DetectionItem.cs       ← UI 检测框绑定模型 (Canvas Left/Top/Width/Height/Label)
│
├── ViewModels/                ← MVVM 层
│   ├── ViewModelBase.cs       ← INotifyPropertyChanged + SetProperty<T>
│   ├── RelayCommand.cs        ← ICommand 实现，需手动调用 RaiseCanExecuteChanged()
│   ├── MainViewModel.cs       ← 根 VM：拥有所有服务 + 子 VM + 预览/状态栏属性
│   ├── MonitorViewModel.cs    ← 监控页：选区/遮罩/启停 + FrameProcessed→UI线程预览更新
│   ├── SettingsViewModel.cs   ← 设置页：置信度/采样率/冷却/模型/6类目标 + 持久化
│   ├── ServerViewModel.cs     ← 服务器页：连接状态/设备名/重连
│   └── MaskEditorViewModel.cs ← 遮罩编辑器：MaskRect 集合 + 撤销/清空/删除命令
│
├── Views/                     ← XAML 视图
│   ├── MainWindow.xaml(.cs)   ← 三栏布局：导航72px | 预览 Viewbox+Canvas | 右侧页面
│   ├── MonitorPage.xaml(.cs)  ← 捕获区域选择 + 遮罩入口 + 启停按钮
│   ├── SettingsPage.xaml(.cs) ← Slider+CheckBox+ComboBox 参数设置
│   ├── ServerPage.xaml(.cs)   ← 连接状态 + 设备名 + 重试
│   ├── WindowPickerWindow.*   ← 窗口列表弹窗（双击选中）
│   ├── RegionSelectorWindow.* ← 拖拽选区弹窗（全屏半透明 + 窗口子区域两种模式）
│   └── MaskEditorWindow.*     ← 遮罩拖拽编辑弹窗（LimeGreen 已有 + Yellow 进行中）
│
├── Utils/
│   ├── SettingsStore.cs       ← settings.ini 读写（%AppData%/VisionGuard/）
│   ├── SimpleJson.cs          ← System.Text.Json 轻量封装
│   ├── SnapshotRenderer.cs    ← Bitmap 上绘制检测框（报警截图标注）
│   ├── NtpSync.cs             ← NTP 时钟同步（阿里云/腾讯/ntp.org 三服务器回退）
│   ├── LogManager.cs          ← Debug.WriteLine 线程安全日志
│   ├── AppConfig.cs           ← ServerUrl/ApiKey 常量 + DeviceId 运行时属性
│   └── MaskRegionDto.cs       ← 遮罩持久化 DTO (left/top/right/bottom)
│
└── Data/
    └── CocoClassMap.cs        ← COCO 80 类中英文映射 + TargetClassNames 6 类子集
```

---

## 二、架构速览

### 启动顺序

```
App.OnStartup
  ├── NtpSync.SyncAsync()           // fire-and-forget
  └── MainWindow 加载
        └── MainViewModel 构造
              ├── SettingsStore.Load()       // 读 settings.ini
              ├── new AlertService()
              ├── new ServerPushService()    // 启动 WS 事件循环线程
              ├── new SettingsViewModel() → .Load()
              ├── new MonitorViewModel(alert, ws, settings, this) → .Load()
              ├── new ServerViewModel(ws) → .Load()
              ├── 注册自动保存（防抖 500ms）
              └── serverPushService.Configure(url, key, deviceId, deviceName)
```

### 监控数据流（每帧）

```
MonitorService.OnTick (ThreadPool)
  │
  ├─ 1. ScreenCapturer / WindowCapturer  → Bitmap frame
  ├─ 2. MaskApplier.ApplyMasks(frame, masks)  // in-place 涂黑（相对坐标→像素）
  ├─ 3. ImagePreprocessor.ToTensor(frame)     // → 320×320 CHW float[]
  ├─ 4. OnnxInferenceEngine.Run(tensor)       // → float[1800]
  ├─ 5. YoloOutputParser.Parse(output, ...)   // → List<Detection>（前5）
  ├─ 6. AlertService.Evaluate(dets, config, timings, frame)
  │     ├─ 冷却判定 → Clone 截图 + SnapshotRenderer 标注
  │     ├─ 保存本地 PNG（alerts/ 目录）
  │     └─ AlertTriggered 事件
  │           ├─ ServerPushService.PushAlert()  // WS 推送元数据
  │           └─ Dispatcher.BeginInvoke → 更新状态栏
  └─ 7. FrameProcessed 事件
        └─ Dispatcher.BeginInvoke → UpdatePreview(bitmap, detections)
              ├─ PreviewImage = BitmapSource
              ├─ Detections.Clear() + 重新填充
              └─ InferMsText 更新
```

### ViewModel 依赖关系

```
MainViewModel（根，持有所有共享服务）
  ├── MonitorVm  ← AlertService, ServerPushService, SettingsVm, MainViewModel
  ├── SettingsVm ← 独立，读写 SettingsStore
  └── ServerVm   ← ServerPushService（订阅 ConnectionStateChanged）
```

---

## 三、约束与易错点

### 线程安全

| 规则 | 说明 |
|------|------|
| Bitmap 在 ThreadPool 创建/Dispose | `MonitorService.OnTick` 线程内完成 |
| BitmapSource 必须在 UI 线程创建 | `Dispatcher.BeginInvoke` 内调用 `CreateBitmapSourceFromHBitmap` |
| ObservableCollection 只能在 UI 线程修改 | `Detections.Clear()/Add()` 均在 Dispatcher 回调内 |
| ServerPushService 单事件循环 | 所有操作通过 `Post(Action)` 排队，Session 内部有 `sendLock` |

### 命令状态刷新

`RelayCommand` 未挂钩 `CommandManager.RequerySuggested`（WPF 标准做法）。当前通过 `IsMonitoring` setter 手动批量调用 `RaiseCanExecuteChanged()`。**新增命令时需手动在状态变更点追加刷新调用**，否则按钮 IsEnabled 不会更新。

### 遮罩坐标系统

- **存储**：相对坐标 `RectangleF(X,Y,Width,Height)`，各分量 ∈ [0,1]
- **序列化**：`MaskRegionDto { left, top, right, bottom }` JSON 数组
- **应用**：`MaskApplier` 在推理前将相对坐标 × 帧尺寸 → 像素 → `FillRectangle(Brush=Black)`
- **副作用**：涂黑区域同时影响推理、报警截图、UI 预览（三处同源）
- **范围变更**：选中新窗口/区域时自动清空旧遮罩（`ClearMasks()`）

### 捕获模式的隐式回退

```
TargetWindow != null  →  WindowCapturer.CaptureWindow(hwnd, subRegion)
ScreenRegion 有效     →  ScreenCapturer.CaptureRegion(region)
以上皆无             →  ScreenCapturer.CapturePrimaryScreen()（自动回退）
```

编辑遮罩时的 `GrabFrame()` 也遵循同样回退链。

### SettingsStore 兼容性

Key 命名保持旧版 `settings.ini` 格式（如 `ConfidenceThresholdPct`、`AlertCooldownSeconds`），确保从 .NET Framework 版升级的用户设置不丢失。

### .NET 9 兼容注意

- `System.Drawing.Bitmap` 需要显式 NuGet 引用 `System.Drawing.Common`（类型已从 Windows SDK 转发出去）
- `ClientWebSocket` 内置于 `System.Net.WebSockets`，无需第三方库
- `app.manifest` 声明 PerMonitorV2 DPI 感知，代码无需额外调用

---

## 四、待完成

| 优先级 | 任务 | 说明 |
|--------|------|------|
| **高** | 全面回归测试 | 窗口/区域/全屏 × 遮罩 × 启停 × 报警 × 持久化 |
| **中** | 服务端命令路由 | `CommandReceived` / `SetConfigReceived` 无人订阅，远程 pause/resume/set-config 不可用 |
| 低 | 遮罩预览叠加 | 在实时预览画面显示遮罩区域（半透明红色） |
| 低 | Nullable warnings | ~84 条 CS8618/CS8622/CS8600/CS8604/CS8625 |
| 低 | CI/CD 脚本 | `dotnet publish -c Release -r win-x64 --self-contained` |
| 低 | AOT 兼容性检查 | 单文件发布、裁剪验证 |

> **已削减**：本地托盘通知由 Android 端负责，`Hardcodet.NotifyIcon.Wpf` 已移除。  
> **已清理**：GlobalKeyHook → Pause/Resume 键盘钩子链路（早期遗弃）。  
> **WebSocket**：已改用 .NET 内置 `System.Net.WebSockets.Client`。

---

## 五、关键决策

| 决策 | 原因 |
|------|------|
| WPF + .NET 9 替代 WinForms | 彻底消除 GDI+ 自绘、DPI 缩放、布局计算缺陷 |
| 保留 `System.Drawing.Common` | 截屏、GDI BitBlt、报警截图标注仍需 GDI |
| 遮罩按钮不前置依赖选区 | 无选区时自动抓取主屏幕作为编辑器背景 |
| 切换范围清空遮罩 | 遮罩相对坐标失去参照系 |
| 预览用 Viewbox 缩放 | 检测框与画面共用原始像素坐标系，避免手动换算 |
| 报警推送纯 WS + 按需截图 | 与 Server `ENABLE_HTTP_SCREENSHOT_UPLOAD=false` 对齐 |
| DeviceId = MachineName | 多机部署需运行时唯一标识 |
| 3 页导航 | 参数页与目标页合并，减少导航层级 |
