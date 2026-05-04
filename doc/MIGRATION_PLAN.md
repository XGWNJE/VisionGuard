# VisionGuard Windows 检测端 — .NET 9 WPF 迁移计划

> 版本：v1.0  
> 创建：2026-05-03  
> 状态：已确认执行  
> 预计总工期：12–16 天  

---

## 一、决策确认（不可变更）

| 决策项 | 选择 | 说明 |
|--------|------|------|
| UI 框架 | **WPF + .NET 9** | 全面重写 UI 层，彻底消除 WinForms 技术债务 |
| 目标框架 | `net9.0-windows` | SDK 风格项目，x64，`<UseWPF>true</UseWPF>` |
| WebSocket 库 | **替换为 `System.Net.WebSockets.ClientWebSocket`** | 移除 `WebSocketSharp`，协议格式零改动 |
| 页面策略 | **3 页结构** | 监控页 / 设置页（参数+目标合并）/ 服务器页 |
| 预览策略 | **全局常驻** | 左侧导航 + 中间预览（~58%）+ 右侧页面面板（~42%） |
| 自绘控件 | **全部移除** | 用 WPF 原生控件 + ControlTemplate + Style 替代 |
|  CocoClassPickerControl | **整组移除** | 当前 Form1 未引用，HiddenScrollCheckedListBox 一并删除 |
| 深色主题 | **WPF 暗色资源字典** | 全局 Brush/Color 资源，统一暗色视觉 |

---

## 二、旧 → 新 文件映射总表

### 2.1 业务逻辑层（保留，少量适配）

| 原文件 | 新文件 | 处理方式 |
|--------|--------|----------|
| `Capture/*.cs` | `Capture/*.cs` | **完全保留**。P/Invoke 与 GDI32 在 WPF 下仍可用 |
| `Data/CocoClassMap.cs` | `Data/CocoClassMap.cs` | **完全保留** |
| `Inference/*.cs` | `Inference/*.cs` | **完全保留** |
| `Models/*.cs` | `Models/*.cs` | **完全保留** |
| `Services/AlertService.cs` | `Services/AlertService.cs` | **完全保留** |
| `Services/MonitorService.cs` | `Services/MonitorService.cs` | **完全保留** |
| `Services/ServerPushService.cs` | `Services/ServerPushService.cs` | **重写 Session 内部类**，替换 `WebSocketSharp` 为 `ClientWebSocket`，外层事件循环保留 |
| `Utils/LogManager.cs` | `Utils/LogManager.cs` | **完全保留** |
| `Utils/NtpSync.cs` | `Utils/NtpSync.cs` | **完全保留** |
| `Utils/SettingsStore.cs` | `Utils/SettingsStore.cs` | **完全保留** |
| `Utils/SimpleJson.cs` | `Utils/SimpleJson.cs` | **完全保留** |
| `Utils/SnapshotRenderer.cs` | `Utils/SnapshotRenderer.cs` | **完全保留** |

### 2.2 UI 层（全面重写）

| 原文件 | 新文件 | 职责映射 |
|--------|--------|----------|
| `Program.cs` | `App.xaml` + `App.xaml.cs` | 程序入口、全局异常处理、托盘初始化 |
| `Form1.cs` | `Views/MainWindow.xaml` + `.xaml.cs` | 主窗体、三栏布局、导航切换 |
| `Form1.UI.cs` | `Views/MainWindow.xaml`（布局） | 主布局、3 个页面容器、预览区、状态栏 |
| `Form1.Monitor.cs` | `ViewModels/MonitorViewModel.cs` + `Views/MonitorPage.xaml` | 监控控制逻辑、区域/窗口选择、遮罩入口 |
| `Form1.Server.cs` | `ViewModels/ServerViewModel.cs` + `Views/ServerPage.xaml` | 服务器连接、设置持久化、心跳 |
| `UI/CardPanel.cs` | `Themes/DarkTheme.xaml` 中 `Border` Style | 圆角卡片容器 |
| `UI/FlatRoundButton.cs` | `Themes/DarkTheme.xaml` 中 `Button` Style | 扁平圆角按钮 |
| `UI/DarkSlider.cs` | `Themes/DarkTheme.xaml` 中 `Slider` Style | 暗色滑块（Track/Thumb 重模板） |
| `UI/MenuButton.cs` | `Views/MainWindow.xaml` 中 `ToggleButton` / `ListBox` | 左侧导航按钮 |
| `UI/DetectionOverlayPanel.cs` | `Views/OverlayControl.xaml` + `OverlayViewModel.cs` | `Image` + `ItemsControl`(`Canvas`) 数据绑定检测框 |
| `UI/MaskEditorForm.cs` | `Views/MaskEditorWindow.xaml` + `MaskEditorViewModel.cs` | `Canvas` + `Rectangle` + 鼠标拖拽绘制遮罩 |
| `UI/RegionSelectorForm.cs` | `Views/RegionSelectorWindow.xaml` | `Window` + `Opacity` + 鼠标拖拽选区 |
| `UI/WindowPickerForm.cs` | `Views/WindowPickerWindow.xaml` | `ListView` 窗口列表选择 |
| `UI/CocoClassPickerControl.cs` | **删除** | 未被 Form1 引用，不再保留 |
| `UI/HiddenScrollCheckedListBox.cs` | **删除** | Win32 Hack，随 CocoClassPickerControl 一并移除 |
| `UI/HiddenScrollPanel.cs` | **删除** | WPF `ScrollViewer` 原生支持无滚动条样式 |
| `UI/DarkStatusRenderer.cs` | `Themes/DarkTheme.xaml` 中 `StatusBar` Style | 暗色状态栏 |

### 2.3 新增文件清单

```
detector/windows/
├── VisionGuard.csproj          (SDK 风格，已创建)
├── App.xaml
├── App.xaml.cs
├── Themes/
│   └── DarkTheme.xaml          (颜色/Brush/控件样式全集)
├── Views/
│   ├── MainWindow.xaml         (三栏布局：导航+预览+页面)
│   ├── MainWindow.xaml.cs
│   ├── MonitorPage.xaml        (区域选择、遮罩、开始/停止)
│   ├── SettingsPage.xaml       (阈值/采样率/冷却/模型/目标，ScrollViewer)
│   ├── ServerPage.xaml         (连接状态、设备名)
│   ├── OverlayControl.xaml     (预览帧 + 检测框叠加)
│   ├── MaskEditorWindow.xaml   (全屏遮罩绘制)
│   ├── RegionSelectorWindow.xaml
│   └── WindowPickerWindow.xaml
├── ViewModels/
│   ├── MainViewModel.cs        (导航、全局状态)
│   ├── MonitorViewModel.cs
│   ├── SettingsViewModel.cs
│   ├── ServerViewModel.cs
│   ├── OverlayViewModel.cs     (预览帧 BitmapSource + Detections 集合)
│   └── MaskEditorViewModel.cs
└── Converters/
    └── BoolConverters.cs       (BooleanToVisibility 等)
```

---

## 三、Phase 详细执行计划

### Phase 0：项目骨架（第 1 天）

**目标**：创建 SDK 项目文件、目录结构、App.xaml、MainWindow 空壳，确保 `dotnet build` 通过。

| # | 任务 | 输出文件 | 备注 |
|---|------|----------|------|
| 0.1 | 备份旧项目文件 | `.old` 后缀文件 | csproj / packages.config / App.config 已备份 |
| 0.2 | 确认 `VisionGuard.csproj` | `VisionGuard.csproj` | SDK 风格，`<UseWPF>true</UseWPF>`，已创建 |
| 0.3 | 创建目录结构 | `Themes/` `Views/` `ViewModels/` `Converters/` | — |
| 0.4 | 创建 `App.xaml` + `App.xaml.cs` | 入口、深色模式初始化、托盘图标初始化 | 替换旧 `Program.cs` |
| 0.5 | 创建 `Themes/DarkTheme.xaml` | 颜色常量、基础控件样式（Button/TextBox/ComboBox/Slider/CheckBox） | 先放占位，后续填充 |
| 0.6 | 创建 `Views/MainWindow.xaml` 空壳 | `Grid` 三栏布局（Left Nav / Center Preview / Right Page） | 先跑通编译 |
| 0.7 | 创建 `ViewModels/MainViewModel.cs` | `INotifyPropertyChanged` 基类、当前页面枚举 | — |
| 0.8 | `dotnet build` 验证 | 编译通过 | — |

**续接检查点**：`dotnet build` 成功且无旧 WinForms 引用错误。

---

### Phase 1：业务逻辑迁移 + WebSocket 替换（第 2 天）

**目标**：让业务逻辑层在新项目中编译通过；将 `ServerPushService` 中的 `WebSocketSharp` 替换为 `ClientWebSocket`。

| # | 任务 | 细节 |
|---|------|------|
| 1.1 | 恢复业务逻辑编译 | 在 `.csproj` 中 `<Compile Include="Capture/*.cs" />` 等（SDK 项目通配符自动包含） |
| 1.2 | 适配 `ServerPushService` | 重写内部 `Session` 类：使用 `ClientWebSocket.ConnectAsync()` / `SendAsync()` / `ReceiveAsync()` / `CloseAsync()` |
| 1.3 | 消息循环兼容 | `ServerPushService` 外层 `BlockingCollection<Action>` 事件循环完全保留，只改 `Session` 的 IO 层 |
| 1.4 | JSON 序列化兼容 | 继续使用 `Utils.SimpleJson`，不引入 System.Text.Json（避免改动消息格式细节） |
| 1.5 | 心跳/重连/幽灵检测 | 逻辑原样保留，线程模型不变 |
| 1.6 | `NetworkAddressChanged` | 原样保留 |
| 1.7 | 编译验证 | `dotnet build` 通过，无 `WebSocketSharp` 残留引用 |

**续接检查点**：`dotnet build` 通过；`ServerPushService` 内无 `WebSocketSharp` using。

---

### Phase 2：暗色主题与样式系统（第 3 天）

**目标**：构建完整的暗色资源字典，让所有原生控件呈现一致的暗色外观。

| # | 任务 | 说明 |
|---|------|------|
| 2.1 | 定义颜色令牌 | `BackgroundDark`、`SurfaceDark`、`Primary`、`TextPrimary`、`TextSecondary`、`Border` 等 |
| 2.2 | `Button` Style | 扁平、圆角（`CornerRadius=4`）、三态色（Normal/Hover/Pressed） |
| 2.3 | `Slider` Style | 暗色 Track + Thumb，重模板（`ControlTemplate`） |
| 2.4 | `ComboBox` Style | 暗色下拉框、边框、高亮项 |
| 2.5 | `TextBox` Style | 暗色背景、灰色边框、焦点高亮 |
| 2.6 | `CheckBox` Style | 暗色方块、选中色（LimeGreen 或 Primary） |
| 2.7 | `StatusBar` Style | 暗色背景、分隔线 |
| 2.8 | `Label` / `TextBlock` 统一 | 定义 `TextBlockBaseStyle`，默认 `Foreground={StaticResource TextPrimary}` |
| 2.9 | `Border` Card Style | 圆角 6、背景 `SurfaceDark`、边框 1px |

**续接检查点**：任意空窗口应用此 Theme 后，所有控件肉眼可见为暗色统一风格。

---

### Phase 3：主窗口布局与导航（第 4–5 天）

**目标**：实现三栏主布局 + 页面切换 + 左侧导航按钮。

```
MainWindow (960×640 最小值，可自由缩放)
├── Grid (3 列)
│   ├── Col0: LeftNav (Width=72, Background=SurfaceDark)
│   │   └── ItemsControl / ListBox: 3 个导航按钮（监控/设置/服务器）
│   ├── Col1: PreviewPanel (Width=*, MinWidth=480)
│   │   └── OverlayControl (Image + ItemsControl/Canvas 检测框)
│   └── Col2: PagePanel (Width=380, MinWidth=320)
│       └── ContentControl: 绑定 CurrentPage → DataTemplateSelector
└── StatusBar (Dock=Bottom)
```

| # | 任务 | 细节 |
|---|------|------|
| 3.1 | `MainWindow.xaml` 骨架 | `Grid` 三列、`StatusBar`、窗口最小尺寸 960×640 |
| 3.2 | 左侧导航 | `ListBox` / `ItemsControl`，选中项高亮（Primary 色条），图标+文字 |
| 3.3 | 页面切换机制 | `MainViewModel.CurrentPage` 枚举 → `DataTemplateSelector` 或 `ContentControl.ContentTemplate` |
| 3.4 | 3 个 Page 空壳 | `MonitorPage.xaml`、`SettingsPage.xaml`、`ServerPage.xaml` 先占位 |
| 3.5 | 预览区占位 | `OverlayControl.xaml` 空壳，`Image` 绑定 `BitmapSource` |
| 3.6 | DPI 处理 | 不固定窗口大小，依靠 WPF 矢量渲染自动适配所有 DPI |
| 3.7 | 状态栏绑定 | `Status`、`LastAlert`、`InferMs` 绑定到 `MainViewModel` |

**续接检查点**：运行后能看到暗色三栏窗口，点击左侧导航右侧内容切换，窗口可自由缩放。

---

### Phase 4：监控页 + 遮罩编辑器 + 选择器（第 6–8 天）

**目标**：实现捕获/窗口选择、遮罩编辑、开始/停止监控。

#### 4.1 MonitorPage

| 控件 | 绑定/命令 |
|------|-----------|
| 区域信息标签 | `MonitorViewModel.RegionInfoText` |
| 「选择窗口」按钮 | `PickWindowCommand` → 打开 `WindowPickerWindow` |
| 「拖拽选区」按钮 | `SelectRegionCommand` → 打开 `RegionSelectorWindow` |
| 「遮罩区域」按钮 | `EditMasksCommand` → 打开 `MaskEditorWindow` |
| 遮罩计数标签 | `MonitorViewModel.MaskCountText` |
| 「开始监控」按钮 | `StartCommand` |
| 「停止监控」按钮 | `StopCommand` |

#### 4.2 WindowPickerWindow
- `ListView` / `DataGrid` 显示窗口列表（标题 + 句柄）
- 双击或「确定」选中
- 数据来自 `WindowEnumerator`

#### 4.3 RegionSelectorWindow
- `WindowStyle=None`、`AllowsTransparency=True`、`Opacity=0.3`
- 全屏暗色遮罩 + 鼠标拖拽绘制矩形
- 返回 `Rectangle`

#### 4.4 MaskEditorWindow
- `Canvas` 铺满窗口，背景为当前捕获帧
- 鼠标拖拽创建 `Rectangle`（WPF `Rectangle` 元素）
- 已绘制矩形支持选中、拖拽调整大小、Delete 删除
- 底部工具栏：撤销 / 清空 / 取消 / 确定
- 返回 `List<RectangleF>`（相对坐标）

| # | 任务 | 说明 |
|---|------|------|
| 4.1 | `WindowPickerWindow` | 窗口枚举、列表展示、选中返回 |
| 4.2 | `RegionSelectorWindow` | 全屏透明层、鼠标拖拽、返回 Rectangle |
| 4.3 | `MaskEditorWindow` 基础 | Canvas + 背景图、鼠标拖拽创建矩形 |
| 4.4 | `MaskEditorWindow` 交互 | 选中、调整大小、移动、撤销、清空 |
| 4.5 | `MonitorPage` 布局 | 按钮纵向排列，宽松间距，顶部标题 |
| 4.6 | `MonitorViewModel` | 命令、属性、与 `MonitorService` 交互 |
| 4.7 | 状态联动 | 开始监控后禁用选择按钮，启用停止按钮 |

**续接检查点**：能完整走通「选窗口→编辑遮罩→开始监控→停止监控」流程。

---

### Phase 5：设置页（参数+目标合并）+ 服务器页（第 9–10 天）

#### 5.1 SettingsPage（合并页）

使用 `ScrollViewer` 包裹纵向布局，分组宽松排列：

```
ScrollViewer
└── StackPanel (Margin=20, 宽松间距)
    ├── Group: 置信度阈值
    │   ├── Slider (10–95)
    │   └── Label ("45%")
    ├── Group: 目标采样率
    │   ├── Slider (1–5)
    │   └── Label ("3 次/秒")
    ├── Group: 警报推送冷却时间
    │   ├── Slider (1–300)
    │   └── Label ("5 秒")
    ├── Group: 模型选择
    │   └── ComboBox (YOLO26n / YOLO26s)
    └── Group: 监控目标
        └── UniformGrid (Columns=2 或 3)
            └── 6 × CheckBox (人/自行车/汽车/摩托车/客车/卡车)
```

#### 5.2 ServerPage

```
StackPanel
├── Group: 服务器连接
│   ├── Label (状态: ● 已连接/未连接)
│   └── Button (重试连接)
├── Separator
└── Group: 设备名称
    ├── TextBox (设备名)
    └── Button (应用)
```

| # | 任务 | 说明 |
|---|------|------|
| 5.1 | `SettingsPage.xaml` | ScrollViewer + 分组 Slider + ComboBox + CheckBox |
| 5.2 | `SettingsViewModel` | 双向绑定所有参数，变更时保存到 INI |
| 5.3 | `ServerPage.xaml` | 状态指示 + 重试按钮 + 设备名输入 |
| 5.4 | `ServerViewModel` | 连接状态绑定、重试命令、设备名持久化 |
| 5.5 | 设置加载/保存 | 启动时从 `SettingsStore` 加载，变更时自动保存 |
| 5.6 | `ServerPushService` 事件绑定 | `ConnectionStateChanged` → `ServerViewModel` → UI 更新 |

**续接检查点**：参数调整实时同步到 UI 标签；服务器连接状态变色；设置重启后恢复。

---

### Phase 6：DetectionOverlay 预览与检测框绑定（第 11 天）

**目标**：用 WPF 数据绑定替代 GDI+ 自绘，实现零 allocations 的预览渲染。

#### 6.1 架构设计

```
OverlayControl (UserControl)
├── Grid
│   ├── Image (Source={Binding FrameBitmap})      ← 预览帧
│   └── ItemsControl (ItemsSource={Binding Detections})
│       └── ItemsPanel: Canvas
│       └── ItemTemplate: 检测框 (Rectangle + TextBlock)
```

#### 6.2 坐标映射

- `Image` 使用 `Stretch=Uniform`，实际渲染尺寸由 WPF 自动计算
- `OverlayViewModel` 计算 `RenderWidth` / `RenderHeight` / `OffsetX` / `OffsetY`
- 每个 `Detection` 通过 `IValueConverter` 或 VM 属性映射为 Canvas 上的 `Left`/`Top`/`Width`/`Height`
- 替代原 `FitRect` 手动计算

#### 6.3 帧更新性能

- `MonitorService.FrameProcessed` 事件触发 VM 更新
- `Bitmap` → `BitmapSource` 转换使用 `Imaging.CreateBitmapSourceFromHBitmap`（采样率 1–5 fps，完全足够）
- 若后续性能不足，可升级为 `WriteableBitmap` + `Lock()`

| # | 任务 | 说明 |
|---|------|------|
| 6.1 | `OverlayControl.xaml` | Image + ItemsControl/Canvas |
| 6.2 | `OverlayViewModel.cs` | `FrameBitmap` + `Detections` ObservableCollection |
| 6.3 | 检测框数据模板 | `Rectangle`（Stroke=LimeGreen）+ `TextBlock`（标签+置信度） |
| 6.4 | 坐标绑定 | Canvas.Left/Top/Width/Height 绑定到 Detection 的缩放后坐标 |
| 6.5 | 空帧提示 | 无帧时显示「等待捕获…」居中文字 |
| 6.6 | 线程安全 | `MonitorService` 在后台线程触发事件，VM 用 `Dispatcher.Invoke` 更新 UI 集合 |

**续接检查点**：监控运行时，预览区实时显示画面，检测框精准叠加，拖动窗口大小时检测框自动跟随。

---

### Phase 7：系统托盘 + 全局热键 + 收尾（第 12 天）

| # | 任务 | 说明 |
|---|------|------|
| 7.1 | 系统托盘 | `Hardcodet.NotifyIcon.Wpf`：托盘图标、右键菜单（显示/退出）、双击显示 |
| 7.2 | 关闭行为 | 点击 × 隐藏到托盘，不退出；托盘「退出」才真正关闭 |
| 7.3 | 全局热键 | `Capture/GlobalKeyHook.cs` 完全保留，注册热键触发暂停/恢复 |
| 7.4 | 启动时 NTP 同步 | `App.xaml.cs` 启动时 `Task.Run(NtpSync.SyncAsync)` |
| 7.5 | 全局异常处理 | `App.DispatcherUnhandledException` → 写日志 + 友好提示 |
| 7.6 | 窗口标题/图标 | 绑定版本号，托盘图标从 exe 提取 |

**续接检查点**：最小化到托盘、托盘菜单正常、热键可触发、异常不崩溃。

---

### Phase 8：构建验证与回归测试（第 13–14 天）

| # | 测试项 | 验证标准 |
|---|--------|----------|
| 8.1 | `dotnet publish` | `dotnet publish -c Release -r win-x64 --self-contained true` 成功生成单文件 exe |
| 8.2 | DPI 测试 | 96/144/192 DPI 下布局不崩、文字清晰、无模糊 |
| 8.3 | 窗口缩放 | 最大化、恢复、拖拽边缘，预览和页面比例正常 |
| 8.4 | 捕获测试 | 屏幕区域、窗口捕获、子区域选择正常 |
| 8.5 | 遮罩测试 | 编辑、保存、加载、热更新（监控运行中修改生效） |
| 8.6 | 推理测试 | YOLO26n/YOLO26s 加载成功，检测框正确 |
| 8.7 | WebSocket 测试 | 连接、认证、心跳、报警推送（含 timings）、命令接收、截图回传 |
| 8.8 | 设置持久化 | INI 读写、重启恢复 |
| 8.9 | 网络切换 | 断网/恢复后自动重连 |
| 8.10 | 长时间运行 | 连续运行 30 分钟，内存不泄漏、UI 不卡顿 |

---

## 四、关键设计决策记录

### 4.1 为什么保留 Win32 捕获层？

`Capture/ScreenCapturer.cs` 使用 `BitBlt`/`PrintWindow`，这是 GDI32 API。WPF 有自己的 `RenderTargetBitmap`，但：
- `BitBlt` 性能更优（硬件加速 BitBlt）
- 窗口捕获（`PrintWindow`）无 WPF 等价物
- 这些 API 在 .NET 9 x64 下完全可用
- **决策**：保留原样，仅将返回的 `Bitmap` 在 VM 层转换为 `BitmapSource`

### 4.2 为什么不用 System.Text.Json？

原项目使用自研 `Utils.SimpleJson`。WPF 重写中：
- `ServerPushService` 的 JSON 序列化/反序列化逻辑需完全复刻
- 引入 `System.Text.Json` 可能导致字段顺序、数字精度、布尔表示等微差异
- **决策**：继续使用 `SimpleJson`，消除协议兼容性风险

### 4.3 为什么用 `CreateBitmapSourceFromHBitmap` 而非 `WriteableBitmap`？

- 当前采样率仅 1–5 fps，内存复制开销可忽略
- `CreateBitmapSourceFromHBitmap` 代码极简，可靠
- 若后续升级到实时视频流，再迁移到 `WriteableBitmap`
- **决策**：先用 `CreateBitmapSourceFromHBitmap`，预留优化空间

### 4.4 页面合并策略

原 4 页内容均偏少，WPF 下会显得空洞。合并为 3 页：
- **监控页**：操作密集型（选区、遮罩、启停），独立成页合理
- **设置页**：合并参数+目标，用 `ScrollViewer` 纵向排列，未来扩展也方便
- **服务器页**：状态监控，独立成页便于瞥一眼连接状态

预览区在所有页面常驻，因为监控应用的核心就是「随时看到画面」。

---

## 五、风险与回退策略

| 风险 | 概率 | 应对 |
|------|------|------|
| `ClientWebSocket` 与服务器握手/行为差异 | 中 | 保留旧 `ServerPushService.cs.old` 作为参考；差异通常只在连接头或 Close 码，可快速适配 |
| ONNX Runtime 1.19 与 1.17 行为差异 | 低 | 若推理输出异常，降级回 `1.17.0`（NuGet 包版本可调） |
| WPF `BitmapSource` 在高分屏模糊 | 低 | 确保 `DpiX`/`DpiY` 正确设置；WPF 矢量渲染天然适配 DPI |
| 遮罩编辑器 `Canvas` 鼠标交互复杂 | 中 | 先实现基础拖拽创建，复杂交互（调整大小手柄）可后续迭代 |
| 工期超预期 | 中 | Phase 1–3 是核心骨架，若中断至少保留可编译项目；后续 Phase 可独立续接 |

---

## 六、中断恢复速查（最新状态快照）

> 最后更新：2026-05-03 — 验收点 ① 通过，暗色主题刚修复重复 Key 问题

### 6.1 已完成文件清单（可直接复用）

```
detector/windows/
├── VisionGuard.csproj              (SDK 风格，net9.0-windows，x64)
├── App.xaml                        (引用 DarkTheme.xaml)
├── App.xaml.cs                     (全局异常处理)
├── Themes/DarkTheme.xaml           (颜色令牌 + Button/Slider/ComboBox/ScrollBar/TextBox/CheckBox/Card 暗色样式)
├── Views/
│   ├── MainWindow.xaml             (三栏布局：导航 72px + 预览 + 页面 360px + 状态栏)
│   ├── MainWindow.xaml.cs          (导航 Click 事件兜底)
│   ├── MonitorPage.xaml            (区域信息、选窗口/选区/遮罩按钮、开始/停止)
│   ├── SettingsPage.xaml           (3 Slider + ComboBox + 6 CheckBox，ScrollViewer)
│   └── ServerPage.xaml             (连接状态、重试、设备名)
├── ViewModels/
│   ├── ViewModelBase.cs            (INotifyPropertyChanged + SetProperty)
│   ├── RelayCommand.cs             (ICommand 实现)
│   ├── MainViewModel.cs            (导航、状态栏、3 个子 VM)
│   ├── MonitorViewModel.cs
│   ├── SettingsViewModel.cs
│   └── ServerViewModel.cs
├── Converters/BoolToVisibilityConverter.cs
├── Capture/、Data/、Inference/、Models/、Services/、Utils/
│   (业务逻辑层完整保留，ServerPushService 已替换 ClientWebSocket)
└── Assets/、app.manifest、favico3n.ico
```

### 6.2 已知问题与修复记录

| 问题 | 原因 | 修复方式 | 状态 |
|------|------|----------|------|
| 导航按钮点击页面不切换 | `DataContext="{Binding MonitorVm}"` 覆盖了元素 DataContext，导致 Visibility 绑定到子 VM | Visibility 绑定改用 `RelativeSource={RelativeSource AncestorType=Window}` | ✅ 已修复 |
| 导航按钮无悬停/选中反馈 | Button 本地 `Background="Transparent"` 优先级高于 Style Trigger | 移除本地 Background，让 Style 完全控制 | ✅ 已修复 |
| 滚动条/滑块/ComboBox 为浅色 | DarkTheme.xaml 中缺少 ScrollBar/Slider/ComboBoxPopup 的暗色模板 | 补充完整 ControlTemplate（Slider 蓝色圆点、ScrollBar 暗色 Thumb、ComboBox 暗色 Popup） | ✅ 已修复 |
| `XamlParseException` 重复 Key | 替换样式时未删除旧空壳定义，导致 `DarkSlider`、`DarkComboBox` 重复 | 删除旧空壳定义 | ✅ 已修复 |

### 6.3 当前待验证

- 重新 `dotnet run` 后，确认 Slider、ScrollBar、ComboBox 下拉均为暗色
- 验收点 ① 完全通过后，进入 Phase 4

### 6.4 下一步切入点（Phase 4）

Phase 4 目标：监控页功能完整化 + 遮罩编辑器 + 区域/窗口选择器

1. `Views/WindowPickerWindow.xaml` — 窗口列表选择（调用 `WindowEnumerator`）
2. `Views/RegionSelectorWindow.xaml` — 全屏透明遮罩 + 鼠标拖拽选区
3. `Views/MaskEditorWindow.xaml` + `MaskEditorViewModel.cs` — Canvas 多矩形绘制
4. `MonitorViewModel` 中命令具体实现（连接选择器、启停监控逻辑）
5. `MonitorService` 启动/停止绑定

### 6.5 编译运行命令

```bash
cd d:\ObjectCode\VisionGuard\detector\windows
dotnet build          # 编译
dotnet run            # 运行预览
```

---

## 七、当前执行状态

| Phase | 状态 | 最后更新 |
|-------|------|----------|
| Phase 0：项目骨架 | **已完成** | 2026-05-03 |
| Phase 1：业务逻辑 + WebSocket | **已完成** | 2026-05-03 |
| Phase 2：暗色主题 | **已完成** | 2026-05-03 |
| Phase 3：主窗口布局 | **已完成** | 2026-05-03 |
| Phase 4：监控页 + 编辑器 | **待开始** | — |
| Phase 5：设置页 + 服务器页 | 未开始 | — |
| Phase 6：DetectionOverlay | 未开始 | — |
| Phase 7：托盘 + 热键 + 收尾 | 未开始 | — |
| Phase 8：回归测试 | 未开始 | — |

---

*本计划由 Kimi Code 生成，作为 VisionGuard Windows 检测端 .NET 9 WPF 迁移的唯一权威参考。*
