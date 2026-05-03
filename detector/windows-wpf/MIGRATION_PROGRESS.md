# VisionGuard Windows 检测端 WPF 迁移进度

> 分支：`feat/wpf-migration-kimi`  
> 目标框架：`net9.0-windows` + WPF  
> 旧项目：`detector/windows/`（.NET Framework 4.7.2 + WinForms）已完全恢复保留  
> 最后更新：2026-05-03

---

## 一、已完成阶段

### ✅ P0 — 项目骨架
| 任务 | 状态 | 说明 |
|------|------|------|
| SDK 风格 `.csproj` | ✅ | `<UseWPF>true</UseWPF>`，x64，nullable=enable |
| 依赖升级 | ✅ | `Microsoft.ML.OnnxRuntime` 1.19.0 + Managed，`Hardcodet.NotifyIcon.Wpf` |
| `System.Drawing.Common` | ✅ | 用于 Bitmap/GDI 截屏（.NET 9 需显式兼容） |
| app.manifest | ✅ | PerMonitorV2 DPI 感知 |

### ✅ P1 — 核心模块移植（业务逻辑零改动）
| 模块 | 文件 | 状态 |
|------|------|------|
| 捕获 | `Capture/ScreenCapturer.cs`, `WindowCapturer.cs`, `WindowEnumerator.cs` | ✅ |
| 推理 | `Inference/OnnxInferenceEngine.cs`, `YoloOutputParser.cs`, `ImagePreprocessor.cs`, `MaskApplier.cs` | ✅ |
| 服务 | `Services/MonitorService.cs`, `AlertService.cs`, `ServerPushService.cs` | ✅ |
| 模型 | `Models/MonitorConfig.cs`, `Detection.cs`, `AlertEvent.cs` | ✅ |
| 工具 | `Utils/SettingsStore.cs`, `SimpleJson.cs`, `NtpSync.cs`, `SnapshotRenderer.cs`, `LogManager.cs` | ✅ |
| 数据 | `Data/CocoClassMap.cs` | ✅ |

### ✅ P2 — UI 框架搭建
| 任务 | 状态 | 说明 |
|------|------|------|
| 暗色主题 | ✅ | `Themes/DarkTheme.xaml`，统一 Background/Foreground/Brush |
| 主窗口 | ✅ | `MainWindow.xaml` 三栏布局：导航 200px + 内容区 + 预览区占位 |
| 导航按钮 | ✅ | 4 页切换（监控/目标/参数/服务器） |
| ViewModel 基类 | ✅ | `ViewModelBase` + `RelayCommand` + `INotifyPropertyChanged` |

### ✅ P3 — 监控页交互
| 任务 | 状态 | 说明 |
|------|------|------|
| 窗口选择器 | ✅ | `WindowPickerWindow.xaml`，DWM 真实边界 + 过滤不可见窗口 |
| 区域选择器 | ✅ | `RegionSelectorWindow.xaml`，双模式（窗口子区域 / 全屏区域） |
| 遮罩编辑器 | ✅ | `MaskEditorWindow.xaml`，多矩形拖拽绘制，撤销/清空/确定 |
| 高 DPI 安全 | ✅ | DIP 归一化 → 物理像素映射，画布 `Uniform` + 居中 |
| 边界保护 | ✅ | 鼠标释放时 Clamp 到画布内 |

### ✅ P4 — 监控服务链
| 任务 | 状态 | 说明 |
|------|------|------|
| MonitorService 循环 | ✅ | ThreadPool 定时器，推理 → 报警 → 截图 |
| MaskApplier | ✅ | 推理前 in-place 涂黑遮罩区域 |
| 自动回退 | ✅ | 未选区域时默认主屏幕全屏 |
| 递进限制 | ✅ | 未选区域可编辑遮罩（GrabFrame 回退 CapturePrimaryScreen） |

### ✅ P5 — 设置与持久化
| 任务 | 状态 | 说明 |
|------|------|------|
| SettingsStore | ✅ | 兼容旧版 `settings.ini` key 格式 100% |
| 自动保存 | ✅ | 防抖 500ms，配置变更后自动持久化 |
| 设置页 | ✅ | 阈值/采样率/冷却/模型选择 |
| 目标页 | ✅ | 6 类 CheckBox，全空视为检测全部 |
| 服务器页 | ✅ | 连接状态/设备名/手动重连 |
| 遮罩持久化 | ✅ | JSON 序列化 `MaskRegions` key |

### ✅ P6 — 命令状态管理
| 任务 | 状态 | 说明 |
|------|------|------|
| RelayCommand | ✅ | 显式 `RaiseCanExecuteChanged()` |
| IsMonitoring 联动 | ✅ | setter 内刷新全部 6 个命令状态 |
| 按钮禁用 | ✅ | 监控中禁用选择/遮罩/清除，启用停止 |

---

## 二、已修复 BUG

| BUG | 根因 | 修复 |
|-----|------|------|
| 全屏模式遮罩报错"无法抓取截图" | `BitmapSource.Create` 的 `bufferSize` 与 `stride` 不匹配 | 改用 `CreateBitmapSourceFromHBitmap` |
| 清除窗口按钮不可见 | `CanResetWindow` 缺少 `PropertyChanged` 通知 | 在 `PickWindow`/`ResetWindow`/`Load`/`IsMonitoring setter` 中补全 |
| 监控启动后无法停止 | `RelayCommand.CanExecuteChanged` 未订阅 `CommandManager.RequerySuggested` | `IsMonitoring` setter 内显式调用全部命令的 `RaiseCanExecuteChanged()` |
| 切换范围后遮罩未清空 | `PickWindow`/`SelectRegion`/`ResetWindow` 未清空 `MaskRegions` | 添加 `ClearMasks()` 辅助方法，在范围变更时调用 |
| 坐标越界导致 BitBlt 失败 | `RegionSelectorWindow` 鼠标释放时未 Clamp | 添加 `Math.Max(0, Math.Min(...))` 边界限制 |

---

## 三、待完成阶段

### ⏳ Phase 6 — 实时预览画面
| 任务 | 优先级 | 说明 |
|------|--------|------|
| 预览区 Image 绑定 | 高 | MainWindow 中间栏替换为 `Image` + `BitmapSource` 绑定 |
| 检测框 Canvas 叠加 | 高 | `Canvas` 覆盖在 `Image` 上，动态添加/移除矩形 |
| 遮罩预览 | 中 | 实时显示遮罩区域（半透明红色） |
| FPS/状态显示 | 低 | 当前推理耗时、帧率 |

### ⏳ Phase 7 — 报警通知
| 任务 | 优先级 | 说明 |
|------|--------|------|
| 系统通知托盘 | 中 | `Hardcodet.NotifyIcon.Wpf` 气泡提示 |
| 报警声 | 低 | 播放 Assets 音效 |

### ⏳ Phase 8 — 代码质量
| 任务 | 优先级 | 说明 |
|------|--------|------|
| Nullable warnings | 低 | ~40 条 CS8618/CS8622/CS8600/CS8604/CS8625 |
| WebSocket 库替换 | 低 | `WebSocketSharp` → `System.Net.WebSockets.Client`（可选） |
| AOT 兼容性检查 | 低 | 单文件发布、裁剪验证 |

### ⏳ Phase 9 — 收尾
| 任务 | 优先级 | 说明 |
|------|--------|------|
| 旧项目清理 | 低 | 确认 `detector/windows/` 可归档后移除 |
| CI/CD 脚本 | 低 | `dotnet publish -c Release -r win-x64 --self-contained` |
| 全面回归测试 | 高 | 窗口/区域/全屏三模式 × 遮罩 × 启停 × 报警 × 持久化 |

---

## 四、技术决策记录

| 日期 | 决策 | 上下文 |
|------|------|--------|
| 2026-05-03 | WPF + .NET 9（非 WinForms） | 彻底消除 WinForms GDI+ 自绘、DPI、布局等所有缺陷 |
| 2026-05-03 | 功能零改动 | AI 推理、报警、WebSocket、截图、遮罩、坐标系统全部保留 |
| 2026-05-03 | 保留 `System.Drawing.Common` | 截屏/截图渲染仍需 GDI Bitmap，WPF 无法完全替代 |
| 2026-05-03 | 遮罩按钮不前置依赖选区 | 无窗口时默认抓取主屏幕作为编辑器背景，与旧版行为一致 |
| 2026-05-03 | 切换范围清空遮罩 | 遮罩坐标是相对于当前范围的，范围变更后旧遮罩失去意义 |

---

*本文档由 Kimi Code 根据实际代码进度维护，每次会话后按需更新。*
