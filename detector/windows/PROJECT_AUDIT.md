# VisionGuard Windows 检测端 — 项目审计报告

> 生成时间：2026-05-03
> 审计目标：评估 UI 框架升级至 .NET 9 的可行性，记录现有技术债务与隐患

---

## 一、当前技术栈快照

| 属性 | 值 |
|------|-----|
| **目标框架** | .NET Framework 4.7.2 |
| **项目格式** | 旧版 MSBuild `.csproj` (`ToolsVersion="15.0"`) |
| **语言版本** | C# 7.3 |
| **输出类型** | `WinExe` x64 |
| **UI 框架** | Windows Forms + **纯 GDI+ 自绘** |
| **包管理** | `packages.config`（传统 NuGet） |
| **DPI 感知** | Per-Monitor-v2（manifest + App.config 双声明） |
| **程序集版本** | 3.7.0.0 |

---

## 二、自定义控件清单与风险评级

| 控件文件 | 基类 | 复杂度 | 风险 | 迁移备注 |
|----------|------|--------|------|----------|
| `CardPanel.cs` | `Panel` | 中 | 中 | `GraphicsPath` 圆角，GDI+ 依赖 |
| `FlatRoundButton.cs` | `Button` | 中 | 中 | 三态色自绘，`TextRenderer.DrawText` |
| `DarkSlider.cs` | `Control` | **高** | **高** | `GraphicsPath` + 抗锯齿 + 阴影模拟 |
| `MenuButton.cs` | `Control` | 中 | 中 | `ClearTypeGridFit` + `DrawImage` |
| `HiddenScrollPanel.cs` | `Panel` | 低 | 低 | Win32 消息拦截（低危） |
| `HiddenScrollCheckedListBox.cs` | `CheckedListBox` | **高** | **高** | **深度 Win32 Hack**：`WndProc` 拦截 `WM_NCPAINT`/`WM_NCCALCSIZE`，篡改 `GWL_STYLE` |
| `DetectionOverlayPanel.cs` | `Panel` | 中 | 中 | `InterpolationMode.Bilinear` 帧绘制 |
| `CocoClassPickerControl.cs` | `UserControl` | 低 | 低 | 纯容器组合 |
| `WindowPickerForm.cs` | `Form` | 低 | 低 | 简单对话框 |
| `RegionSelectorForm.cs` | `Form` | 低 | 低 | `FormBorderStyle=None` + `Opacity` 全屏遮罩 |
| `MaskEditorForm.cs` | `Form` | **高** | **高** | `GraphicsPath` + `DashStyle.Dash` + 图像缩放 |

---

## 三、已识别的结构性缺陷

### 3.1 DPI 适配 — 半吊子实现（风险：高）

- 声明了 `PerMonitorV2` + `AutoScaleMode.Dpi`，但 `OnDpiChanged` **仅调整窗口外壳大小**，页面内所有控件的 `Left`/`Top`/`Width`/`Height` 在 `OnShown` 时一次性计算后不再更新。
- 硬编码 `Font = new Font("Segoe UI", 9f, ...)`，未随 DPI 自动调整。
- **后果**：窗口从 96 DPI 拖到 192 DPI 显示器时，控件显得过小或位置偏移。

### 3.2 绝对坐标布局（风险：中）

- 除顶层 `TableLayoutPanel` 和左侧菜单 `Dock=Left` 外，**所有页面内部控件均为绝对坐标定位**。
- 固定逻辑尺寸 960×640，窗口无法自由缩放。
- 垂直位置通过 `ref int y` 逐行硬编码计算。

### 3.3 零数据绑定（风险：中）

- 完全没有使用 `BindingSource` / `DataBindings` / `INotifyPropertyChanged`。
- 所有 UI 与数据同步均为命令式事件驱动，UI 与业务逻辑紧耦合。
- 扩展性差，新增配置项时代码量线性爆炸。

### 3.4 HiddenScrollCheckedListBox — Win32 定时炸弹（风险：高）

- 通过 `GetWindowLong`/`SetWindowLong` 实时剥除 `WS_HSCROLL`/`WS_VSCROLL`。
- 拦截 `WM_NCCALCSIZE` 和 `WM_NCPAINT` 篡改非客户区绘制。
- **风险**：不同 Windows 版本/主题/DPI 下可能行为异常；未来 .NET 版本 `CheckedListBox` 实现变更即失效。

### 3.5 GDI+ 性能瓶颈（风险：中）

- `DetectionOverlayPanel` 每帧 `Clone()` Bitmap 并在 `OnPaint` 中绘制。
- `MaskEditorForm` 图像缩放依赖 `InterpolationMode`。
- GDI+ 软件渲染，无硬件加速。

### 3.6 项目格式老旧（风险：低）

- 旧版 `.csproj` + `packages.config`，依赖路径硬编码在 `.csproj` 中。
- 不利于现代 CI/CD 和跨环境构建。

---

## 四、依赖库兼容性评估（.NET 9 迁移）

| 包名 | 当前版本 | .NET 9 兼容性 | 备注 |
|------|----------|---------------|------|
| `Microsoft.ML.OnnxRuntime` | 1.17.0 | ✅ 支持 | 官方提供 .NET 8/9 原生包 |
| `Microsoft.ML.OnnxRuntime.Managed` | 1.17.0 | ✅ 支持 | 同上 |
| `WebSocketSharp` | 1.0.0 | ⚠️ 需验证 | 社区包，可能需换 `System.Net.WebSockets.Client` |
| `System.Memory` | 4.5.5 | ✅ 内置 | .NET 9 已内置，可移除显式引用 |
| `System.Numerics.Vectors` | 4.5.0 | ✅ 内置 | .NET 9 已内置 |
| `Svg` | 3.4.7 | ✅ 支持 | 现代版本支持 .NET 6+ |
| `ExCSS` | 4.2.3 | ✅ 支持 | 现代版本支持 .NET 6+ |

---

## 五、迁移工作量估算

### 路线 A：.NET 9 WinForms + 现代 UI 库（推荐稳定优先）

| 任务 | 工作量 | 说明 |
|------|--------|------|
| 项目格式迁移（SDK 风格 `.csproj`） | 0.5d | `PackageReference` 替换 `packages.config` |
| 修复 DPI 动态重算 | 1d | `OnDpiChanged` 时重新执行页面构建或引入布局容器 |
| 替换/增强自绘控件 | 2-3d | 引入现代 WinForms UI 库替代 GDI+ 自绘 |
| 剔除 `HiddenScrollCheckedListBox` Hack | 0.5d | 用库提供的无滚动条列表或自定义 `ListBox` |
| 构建与发布验证 | 1d | 单文件发布、AOT 兼容性测试 |
| **总计** | **~5-6 天** | 功能零改动，视觉与兼容性大幅提升 |

### 路线 B：WPF + .NET 9（推荐最佳体验）

| 任务 | 工作量 | 说明 |
|------|--------|------|
| 业务逻辑层剥离与复用 | 3d | `Capture/`/`Inference/`/`Services/` 等尽量保持 |
| 自绘控件 → WPF CustomControl | 5-7d | CardPanel、FlatRoundButton、DarkSlider、MenuButton 等 |
| 绝对坐标 → XAML 声明式布局 | 3d | Grid/StackPanel 重构 4 个页面 |
| 对话框重写 | 2d | RegionSelectorForm、MaskEditorForm、WindowPickerForm |
| 命令式同步 → MVVM 绑定 | 3d | ViewModel + `INotifyPropertyChanged` |
| `BeginInvoke` → `Dispatcher` | 0.5d | 线程切换语法调整 |
| 图像渲染管线重构 | 2d | `DetectionOverlayPanel` → `Image` + `Canvas` |
| 构建与回归测试 | 3d | 全功能验证 |
| **总计** | **~1.5-2 个月** | 彻底消除所有 WinForms 缺陷，获得最佳视觉效果 |

---

## 六、关键文件索引

| 文件 | 职责 | 重构优先级 |
|------|------|-----------|
| `Form1.cs` | 主窗体字段、构造、配置构建 | 高 |
| `Form1.UI.cs` | UI 构建：主布局、4 页面、辅助方法 | **最高** |
| `Form1.Monitor.cs` | 监控控制逻辑 | 中（逻辑保留） |
| `Form1.Server.cs` | 服务器连接、设置持久化 | 中（逻辑保留） |
| `UI/CardPanel.cs` | 圆角卡片容器 | 高 |
| `UI/FlatRoundButton.cs` | 扁平圆角按钮 | 高 |
| `UI/DarkSlider.cs` | 暗色滑块 | 高 |
| `UI/MenuButton.cs` | 左侧导航按钮 | 高 |
| `UI/HiddenScrollCheckedListBox.cs` | **Win32 Hack 列表框** | **最高（必须替换）** |
| `UI/MaskEditorForm.cs` | 遮罩编辑器 | 高 |
| `UI/DetectionOverlayPanel.cs` | 检测框叠加层 | 高 |
| `Program.cs` | 入口、DPI 兜底 | 中 |
| `VisionGuard.csproj` | 项目文件 | **最高（必须升级）** |
| `packages.config` | 包管理 | **最高（必须移除）** |

---

## 七、决策记录

| 日期 | 决策 | 上下文 |
|------|------|--------|
| 2026-05-03 | 升级到 .NET 9，彻底重构 UI | 用户要求稳定兼容前提下达到最佳视觉效果，消除所有缺陷 |
| 2026-05-03 | 保留功能不变 | 业务逻辑（AI 推理、报警、WebSocket、截图）完全保留 |

---

*本报告由 Kimi Code 基于代码扫描生成，作为 .NET 9 重构的基准参考。*
