using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Windows;
using System.Windows.Media.Imaging;
using VisionGuard.Capture;
using VisionGuard.Models;
using VisionGuard.Services;
using VisionGuard.Utils;
using VisionGuard.Views;

namespace VisionGuard.ViewModels
{
    public class MonitorViewModel : ViewModelBase
    {
        private readonly AlertService _alertService;
        private readonly MonitorService _monitorService;
        private readonly ServerPushService _serverPushService;
        private readonly SettingsViewModel _settingsVm;

        private string _regionInfo = "未选择区域";
        public string RegionInfo
        {
            get => _regionInfo;
            set => SetProperty(ref _regionInfo, value);
        }

        private string _maskInfo = "当前遮罩：—";
        public string MaskInfo
        {
            get => _maskInfo;
            set => SetProperty(ref _maskInfo, value);
        }

        private bool _isMonitoring;
        public bool IsMonitoring
        {
            get => _isMonitoring;
            set
            {
                if (SetProperty(ref _isMonitoring, value))
                {
                    OnPropertyChanged(nameof(CanStart));
                    OnPropertyChanged(nameof(CanStop));
                    OnPropertyChanged(nameof(CanSelectRegion));
                    OnPropertyChanged(nameof(CanEditMasks));
                    OnPropertyChanged(nameof(CanResetWindow));
                    StartCommand.RaiseCanExecuteChanged();
                    StopCommand.RaiseCanExecuteChanged();
                    PickWindowCommand.RaiseCanExecuteChanged();
                    SelectRegionCommand.RaiseCanExecuteChanged();
                    EditMasksCommand.RaiseCanExecuteChanged();
                    ResetWindowCommand.RaiseCanExecuteChanged();
                }
            }
        }

        public bool CanStart => !IsMonitoring;
        public bool CanStop => IsMonitoring;
        public bool CanSelectRegion => !IsMonitoring;
        public bool CanEditMasks => !IsMonitoring;
        public bool CanResetWindow => !IsMonitoring && TargetWindow != null;

        /// <summary>是否已设定有效的捕获目标（窗口 或 屏幕区域）。</summary>
        private bool HasCaptureTarget => TargetWindow != null ||
            (ScreenRegion != Rectangle.Empty && ScreenRegion.Width >= 32 && ScreenRegion.Height >= 32);

        // 运行时状态
        public WindowInfo? TargetWindow { get; set; }
        public Rectangle ScreenRegion { get; set; }
        public Rectangle WindowSubRegion { get; set; }
        public List<RectangleF> MaskRegions { get; set; } = new List<RectangleF>();

        public RelayCommand StartCommand { get; }
        public RelayCommand StopCommand { get; }
        public RelayCommand PickWindowCommand { get; }
        public RelayCommand SelectRegionCommand { get; }
        public RelayCommand EditMasksCommand { get; }
        public RelayCommand ResetWindowCommand { get; }

        public MonitorViewModel(AlertService alertService,
                                ServerPushService serverPushService,
                                SettingsViewModel settingsVm)
        {
            _alertService = alertService;
            _monitorService = new MonitorService(alertService);
            _serverPushService = serverPushService;
            _settingsVm = settingsVm;

            StartCommand = new RelayCommand(StartMonitor, () => CanStart);
            StopCommand = new RelayCommand(StopMonitor, () => CanStop);
            PickWindowCommand = new RelayCommand(PickWindow, () => CanSelectRegion);
            SelectRegionCommand = new RelayCommand(SelectRegion, () => CanSelectRegion);
            EditMasksCommand = new RelayCommand(EditMasks, () => CanEditMasks);
            ResetWindowCommand = new RelayCommand(ResetWindow, () => CanResetWindow);
        }

        private void StartMonitor()
        {
            // 未选任何区域时，默认使用主屏幕全屏
            if (TargetWindow == null && !HasCaptureTarget)
            {
                using var primary = ScreenCapturer.CapturePrimaryScreen();
                if (primary != null)
                {
                    ScreenRegion = new Rectangle(0, 0, primary.Width, primary.Height);
                    RegionInfo = $"X:0  Y:0  {ScreenRegion.Width}×{ScreenRegion.Height}";
                }
            }

            var config = BuildConfig();
            var modelPath = System.IO.Path.Combine(AppContext.BaseDirectory, "Assets", _settingsVm.SelectedModelName + ".onnx");
            _monitorService.Start(modelPath, config);
            IsMonitoring = true;
        }

        private void StopMonitor()
        {
            _monitorService.Stop();
            IsMonitoring = false;
        }

        private void ClearMasks()
        {
            MaskRegions.Clear();
            MaskInfo = "当前遮罩：—";
        }

        private void ResetWindow()
        {
            TargetWindow = null;
            WindowSubRegion = Rectangle.Empty;
            if (ScreenRegion == Rectangle.Empty || ScreenRegion.Width < 32 || ScreenRegion.Height < 32)
                RegionInfo = "未选择区域";
            ClearMasks();
            OnPropertyChanged(nameof(CanResetWindow));
            ResetWindowCommand.RaiseCanExecuteChanged();
        }

        private void PickWindow()
        {
            var picker = new WindowPickerWindow { Owner = Application.Current.MainWindow };
            if (picker.ShowDialog() == true && picker.SelectedWindow != null)
            {
                TargetWindow = picker.SelectedWindow;
                ScreenRegion = Rectangle.Empty;
                WindowSubRegion = Rectangle.Empty;
                RegionInfo = $"[{TargetWindow.Title}]  全窗口";
                ClearMasks();
                OnPropertyChanged(nameof(CanResetWindow));
                ResetWindowCommand.RaiseCanExecuteChanged();
            }
        }

        private void SelectRegion()
        {
            BitmapSource? bg = null;
            bool isWindowMode = TargetWindow != null;

            try
            {
                if (isWindowMode)
                {
                    // 窗口子区域模式：抓取整个窗口作为背景
                    using var bmp = WindowCapturer.CaptureWindow(TargetWindow!.Handle, Rectangle.Empty);
                    bg = ConvertToBitmapSource(bmp);
                }
                else
                {
                    // 全屏区域模式：抓取主屏幕作为背景（用于显示）
                    using var bmp = ScreenCapturer.CapturePrimaryScreen();
                    bg = ConvertToBitmapSource(bmp);
                }
            }
            catch { /* 即使抓图失败也允许选区 */ }

            var selector = new RegionSelectorWindow(bg) { Owner = Application.Current.MainWindow };
            selector.ShowDialog();

            if (selector.IsConfirmed)
            {
                if (isWindowMode)
                {
                    WindowSubRegion = selector.SelectedRegion;
                    ScreenRegion = Rectangle.Empty;
                    RegionInfo = $"[{TargetWindow!.Title}]  子区域 {WindowSubRegion.Width}×{WindowSubRegion.Height}";
                }
                else
                {
                    ScreenRegion = selector.SelectedRegion;
                    TargetWindow = null;
                    WindowSubRegion = Rectangle.Empty;
                    RegionInfo = $"X:{ScreenRegion.X}  Y:{ScreenRegion.Y}  {ScreenRegion.Width}×{ScreenRegion.Height}";
                }
                ClearMasks();
            }
        }

        private void EditMasks()
        {
            BitmapSource? bg = null;
            try
            {
                using var bmp = GrabFrame();
                if (bmp != null)
                    bg = ConvertToBitmapSource(bmp);
            }
            catch (Exception ex)
            {
                MessageBox.Show($"抓图失败: {ex.GetType().Name}: {ex.Message}\n\n{ex.StackTrace}",
                    "VisionGuard Debug", MessageBoxButton.OK, MessageBoxImage.Error);
                return;
            }

            if (bg == null)
            {
                MessageBox.Show("无法抓取当前区域截图，请确保已选择有效的窗口或屏幕区域。",
                    "VisionGuard", MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }

            var editor = new MaskEditorWindow(bg, MaskRegions) { Owner = Application.Current.MainWindow };
            editor.ShowDialog();
            if (editor.IsConfirmed)
            {
                MaskRegions = editor.ResultMasks;
                int n = MaskRegions.Count;
                MaskInfo = n == 0 ? "当前遮罩：—" : $"当前遮罩：{n} 个";
            }
        }

        /// <summary>按当前状态抓取一帧，用于遮罩编辑器背景或预览。</summary>
        private System.Drawing.Bitmap? GrabFrame()
        {
            if (TargetWindow != null)
            {
                // WindowSubRegion 是相对于窗口的坐标；Empty 表示捕获整个窗口
                return WindowCapturer.CaptureWindow(TargetWindow.Handle, WindowSubRegion);
            }
            if (ScreenRegion != Rectangle.Empty && ScreenRegion.Width >= 32 && ScreenRegion.Height >= 32)
            {
                return ScreenCapturer.CaptureRegion(ScreenRegion);
            }
            return ScreenCapturer.CapturePrimaryScreen();
        }

        private MonitorConfig BuildConfig()
        {
            var cfg = new MonitorConfig
            {
                ConfidenceThreshold = _settingsVm.Threshold / 100f,
                TargetFps = _settingsVm.SamplingRate,
                AlertCooldownSeconds = _settingsVm.Cooldown,
                SaveAlertSnapshot = true,
                MaskRegions = new List<RectangleF>(MaskRegions),
            };

            // 监控目标：空集合视为全部
            var watched = _settingsVm.GetWatchedClasses();
            cfg.WatchedClasses = new HashSet<string>(watched, StringComparer.OrdinalIgnoreCase);
            if (cfg.WatchedClasses.Count == 0)
                cfg.WatchedClasses.Add("person");

            if (TargetWindow != null)
            {
                cfg.CaptureMode = CaptureMode.WindowHandle;
                cfg.TargetWindowTitle = TargetWindow.Title;
                cfg.TargetWindowHandle = TargetWindow.Handle;
                cfg.WindowSubRegion = WindowSubRegion;
                // 与旧代码行为一致：WindowHandle 模式下 CaptureRegion 为信息性冗余字段
                cfg.CaptureRegion = WindowSubRegion != Rectangle.Empty
                    ? WindowSubRegion
                    : TargetWindow.Bounds;
            }
            else
            {
                cfg.CaptureMode = CaptureMode.ScreenRegion;
                cfg.CaptureRegion = ScreenRegion;
            }

            return cfg;
        }

        // ── 持久化 ───────────────────────────────────────────────────

        public void Load()
        {
            string modeStr = SettingsStore.GetString("CaptureMode", CaptureMode.ScreenRegion.ToString());
            if (Enum.TryParse<CaptureMode>(modeStr, out var mode) && mode == CaptureMode.WindowHandle)
            {
                string title = SettingsStore.GetString("TargetWindowTitle", string.Empty);
                if (!string.IsNullOrEmpty(title))
                {
                    var windows = WindowEnumerator.GetWindows(System.IntPtr.Zero);
                    foreach (var w in windows)
                    {
                        if (w.Title.Equals(title, StringComparison.OrdinalIgnoreCase))
                        {
                            TargetWindow = w;
                            RegionInfo = $"[{w.Title}]  全窗口";
                            break;
                        }
                    }
                }

                string subStr = SettingsStore.GetString("WindowSubRegion", string.Empty);
                if (!string.IsNullOrEmpty(subStr))
                {
                    var parts = subStr.Split(',');
                    if (parts.Length == 4
                        && int.TryParse(parts[0], out int x)
                        && int.TryParse(parts[1], out int y)
                        && int.TryParse(parts[2], out int w)
                        && int.TryParse(parts[3], out int h))
                    {
                        WindowSubRegion = new Rectangle(x, y, w, h);
                        if (TargetWindow != null)
                            RegionInfo = $"[{TargetWindow.Title}]  子区域 {w}×{h}";
                    }
                }
            }
            else
            {
                string regStr = SettingsStore.GetString("ScreenRegion", string.Empty);
                if (!string.IsNullOrEmpty(regStr))
                {
                    var parts = regStr.Split(',');
                    if (parts.Length == 4
                        && int.TryParse(parts[0], out int x)
                        && int.TryParse(parts[1], out int y)
                        && int.TryParse(parts[2], out int w)
                        && int.TryParse(parts[3], out int h))
                    {
                        ScreenRegion = new Rectangle(x, y, w, h);
                        RegionInfo = $"X:{x}  Y:{y}  {w}×{h}";
                    }
                }
            }

            // 遮罩区域
            MaskRegions = ParseMasksJson(SettingsStore.GetString("MaskRegions", string.Empty));
            int n = MaskRegions.Count;
            MaskInfo = n == 0 ? "当前遮罩：—" : $"当前遮罩：{n} 个";

            OnPropertyChanged(nameof(CanResetWindow));
            EditMasksCommand.RaiseCanExecuteChanged();
            ResetWindowCommand.RaiseCanExecuteChanged();
        }

        public void Save()
        {
            if (TargetWindow != null)
            {
                SettingsStore.Set("CaptureMode", CaptureMode.WindowHandle.ToString());
                SettingsStore.Set("TargetWindowTitle", TargetWindow.Title);
                SettingsStore.Set("WindowSubRegion",
                    WindowSubRegion == Rectangle.Empty
                        ? string.Empty
                        : $"{WindowSubRegion.X},{WindowSubRegion.Y},{WindowSubRegion.Width},{WindowSubRegion.Height}");
            }
            else
            {
                SettingsStore.Set("CaptureMode", CaptureMode.ScreenRegion.ToString());
                SettingsStore.Set("ScreenRegion",
                    ScreenRegion == Rectangle.Empty
                        ? string.Empty
                        : $"{ScreenRegion.X},{ScreenRegion.Y},{ScreenRegion.Width},{ScreenRegion.Height}");
            }

            SettingsStore.Set("MaskRegions", MasksToJson(MaskRegions));
            SettingsStore.Save();
        }

        private static List<RectangleF> ParseMasksJson(string json)
        {
            if (string.IsNullOrWhiteSpace(json)) return new List<RectangleF>();
            var dtos = SimpleJson.Deserialize<List<MaskRegionDto>>(json);
            var list = new List<RectangleF>();
            if (dtos == null) return list;
            foreach (var d in dtos)
            {
                float x = Math.Max(0f, Math.Min(1f, d.left));
                float y = Math.Max(0f, Math.Min(1f, d.top));
                float w = Math.Max(0f, Math.Min(1f, d.right)) - x;
                float h = Math.Max(0f, Math.Min(1f, d.bottom)) - y;
                if (w <= 0f || h <= 0f) continue;
                list.Add(new RectangleF(x, y, w, h));
            }
            return list;
        }

        private static string MasksToJson(List<RectangleF> masks)
        {
            if (masks == null || masks.Count == 0) return "[]";
            var dtos = new List<MaskRegionDto>(masks.Count);
            foreach (var r in masks)
            {
                dtos.Add(new MaskRegionDto
                {
                    left   = r.X,
                    top    = r.Y,
                    right  = r.X + r.Width,
                    bottom = r.Y + r.Height,
                });
            }
            return SimpleJson.ToJson(dtos);
        }

        private static BitmapSource ConvertToBitmapSource(System.Drawing.Bitmap bmp)
        {
            // 使用 WPF 原生 API 从 HBITMAP 创建 BitmapSource，避免手动处理像素格式/stride
            var hBitmap = bmp.GetHbitmap();
            try
            {
                var src = System.Windows.Interop.Imaging.CreateBitmapSourceFromHBitmap(
                    hBitmap,
                    IntPtr.Zero,
                    System.Windows.Int32Rect.Empty,
                    System.Windows.Media.Imaging.BitmapSizeOptions.FromEmptyOptions());
                src.Freeze();
                return src;
            }
            finally
            {
                NativeMethods.DeleteObject(hBitmap);
            }
        }
    }
}
