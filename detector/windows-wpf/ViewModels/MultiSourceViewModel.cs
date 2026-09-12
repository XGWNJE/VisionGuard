using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Media.Imaging;
using VisionGuard.Capture;
using VisionGuard.Data;
using VisionGuard.Inference;
using VisionGuard.Models;
using VisionGuard.Services;
using VisionGuard.Utils;
using VisionGuard.Views;

namespace VisionGuard.ViewModels
{
    public sealed class MultiSourceViewModel : ViewModelBase, IDisposable
    {
        private readonly MultiSourceMonitorCoordinator _coordinator = new();
        private readonly ServerPushService _server;
        private readonly SettingsViewModel _settings;
        private SignalSourceViewModel? _selectedSource;

        public ObservableCollection<SignalSourceViewModel> Sources { get; } = new();
        public IReadOnlyList<MonitorSourceStatus> Statuses => _coordinator.Statuses;
        public SignalSourceViewModel? SelectedSource
        {
            get => _selectedSource;
            set
            {
                if (ReferenceEquals(_selectedSource, value)) return;
                if (_selectedSource != null) _selectedSource.IsSelected = false;
                if (SetProperty(ref _selectedSource, value) && value != null) value.IsSelected = true;
            }
        }

        public bool IsAnyMonitoring => Statuses.Any(s => s.IsMonitoring);
        public bool IsAnyReady => Statuses.Any(s => s.IsReady);
        public string RunningSummary => $"运行 {Statuses.Count(s => s.IsMonitoring)} / {Sources.Count}";
        public string ErrorSummary => Statuses.Count(s => !string.IsNullOrWhiteSpace(s.Error)) is var count && count > 0 ? $"{count} 路异常" : "无异常";
        public string ConnectionSummary => _server.IsConnected ? "服务器已连接" : "服务器未连接";
        public RelayCommand StartConfiguredCommand { get; }
        public RelayCommand StopAllCommand { get; }

        public MultiSourceViewModel(ServerPushService server, SettingsViewModel settings)
        {
            _server = server;
            _settings = settings;
            EnsureLegacyBackupAndMigration();
            for (var i = 1; i <= MultiSourceMonitorCoordinator.MaxSources; i++)
            {
                var slot = new SignalSourceViewModel(i, this);
                Sources.Add(slot);
                _coordinator.Add(slot.BuildSource());
            }
            SelectedSource = Sources[0];

            _coordinator.AlertTriggered += (_, alert) =>
            {
                _server.PushAlert(alert);
                Application.Current.Dispatcher.BeginInvoke(() =>
                    Sources.FirstOrDefault(source => source.SourceId == alert.SourceId)?.ApplyAlert(alert));
            };
            _coordinator.StatusChanged += (_, status) => Application.Current.Dispatcher.BeginInvoke(() =>
            {
                Sources.First(s => s.SourceId == status.SourceId).ApplyStatus(status);
                RefreshSummary();
            });
            _coordinator.FrameProcessed += (_, e) =>
            {
                if (e.Frame.HasError) { e.Frame.Frame?.Dispose(); return; }
                Application.Current.Dispatcher.BeginInvoke(() =>
                {
                    using (e.Frame.Frame)
                    {
                        var slot = Sources.First(s => s.SourceId == e.SourceId);
                        slot.ApplyFrame(MonitorViewModel.ConvertBitmapToSource(e.Frame.Frame), e.Frame.Detections, e.Frame.InferenceMs);
                    }
                });
            };

            StartConfiguredCommand = new RelayCommand(StartConfigured, () => Sources.Any(s => s.CanStart));
            StopAllCommand = new RelayCommand(StopAll, () => Sources.Any(s => s.IsMonitoring));
            RefreshSummary();
        }

        internal InferenceBackend PreferredBackend => _settings.PreferredBackend;
        internal void Select(SignalSourceViewModel slot) => SelectedSource = slot;

        internal void Rename(SignalSourceViewModel slot) => _coordinator.Rename(slot.SourceId, slot.SourceName);

        internal void Reconfigure(SignalSourceViewModel slot)
        {
            if (slot.IsMonitoring) throw new InvalidOperationException("请先停止该来源再修改配置。");
            _coordinator.Remove(slot.SourceId);
            _coordinator.Add(slot.BuildSource());
            slot.ApplyStatus(_coordinator.Statuses.First(s => s.SourceId == slot.SourceId));
            RefreshSummary();
        }

        internal void Start(SignalSourceViewModel slot)
        {
            if (!slot.ResolveWindowForStart()) throw new InvalidOperationException(slot.StatusText);
            Reconfigure(slot);
            var modelPath = ModelManager.GetModelPath(slot.ModelKey);
            if (!File.Exists(modelPath)) throw new FileNotFoundException("模型未下载，请先在设置页下载。", modelPath);
            slot.MarkStarting();
            _coordinator.Start(slot.SourceId, modelPath);
            RefreshSummary();
        }

        internal void Stop(SignalSourceViewModel slot) { _coordinator.Stop(slot.SourceId); RefreshSummary(); }

        public bool HandleCommand(string sourceId, string command, string requestId)
        {
            var targetId = string.IsNullOrWhiteSpace(sourceId) ? "default" : sourceId;
            var ackSourceId = string.IsNullOrWhiteSpace(sourceId) ? string.Empty : targetId;
            var slot = Sources.FirstOrDefault(s => s.SourceId == targetId);
            if (slot == null) { _server.SendCommandAck(command, false, "来源不存在", requestId, ackSourceId); return false; }
            try
            {
                if (command == "resume") Start(slot);
                else if (command == "pause") Stop(slot);
                else { _server.SendCommandAck(command, false, "来源不支持该命令", requestId, ackSourceId); return false; }
                _server.SendCommandAck(command, true, requestId: requestId, targetSourceId: ackSourceId);
                return true;
            }
            catch (Exception ex) { _server.SendCommandAck(command, false, ex.Message, requestId, ackSourceId); return false; }
        }

        public bool HandleConfig(string sourceId, string key, string value, string requestId)
        {
            var targetId = string.IsNullOrWhiteSpace(sourceId) ? "default" : sourceId;
            var ackSourceId = string.IsNullOrWhiteSpace(sourceId) ? string.Empty : targetId;
            var slot = Sources.FirstOrDefault(s => s.SourceId == targetId);
            var command = $"set-config:{key}";
            if (slot == null) { _server.SendCommandAck(command, false, "来源不存在", requestId, ackSourceId); return false; }
            try
            {
                if (slot.IsMonitoring) throw new InvalidOperationException("请先停止该来源再修改配置。");
                switch (key)
                {
                    case "cooldown" when int.TryParse(value, out var cooldown) && cooldown is >= 1 and <= 300: slot.Cooldown = cooldown; break;
                    case "confidence" when float.TryParse(value, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var confidence) && confidence is >= 0.1f and <= 0.95f: slot.ThresholdPercent = (int)Math.Round(confidence * 100); break;
                    case "targetSamplingRate" when int.TryParse(value, out var fps) && fps is >= 1 and <= 5: slot.TargetFps = fps; break;
                    case "targets": slot.Targets = value; break;
                    case "modelKey" when ModelManager.ModelKeys.Contains(value): slot.ModelKey = value; break;
                    default: throw new ArgumentException("配置值无效或不支持。");
                }
                slot.SaveDraft();
                _server.SendCommandAck(command, true, requestId: requestId, targetSourceId: ackSourceId);
                return true;
            }
            catch (Exception ex) { _server.SendCommandAck(command, false, ex.Message, requestId, ackSourceId); return false; }
        }

        public void RefreshSummary()
        {
            OnPropertyChanged(nameof(IsAnyMonitoring)); OnPropertyChanged(nameof(IsAnyReady));
            OnPropertyChanged(nameof(RunningSummary)); OnPropertyChanged(nameof(ErrorSummary)); OnPropertyChanged(nameof(ConnectionSummary));
            StartConfiguredCommand?.RaiseCanExecuteChanged(); StopAllCommand?.RaiseCanExecuteChanged();
        }

        private void StartConfigured()
        {
            foreach (var slot in Sources.Where(s => s.CanStart).ToArray())
            {
                try { Start(slot); }
                catch (Exception ex) { slot.SetError(ex.Message); break; }
            }
            RefreshSummary();
        }

        private void StopAll()
        {
            foreach (var slot in Sources.Where(s => s.IsMonitoring).ToArray()) Stop(slot);
            RefreshSummary();
        }

        public void Save() => SettingsStore.Save();

        private void EnsureLegacyBackupAndMigration()
        {
            if (SettingsStore.GetBool("Signal.LegacyMigrationCompleted", false)) return;
            var keys = new[] { "CaptureMode", "TargetWindowTitle", "WindowSubRegion", "ScreenRegion", "MaskRegions", "ConfidenceThresholdPct", "TargetFps", "AlertCooldownSeconds", "SelectedModelIndex", "WatchedClasses" };
            foreach (var key in keys) SettingsStore.Set($"Signal.LegacyBackup.{key}", SettingsStore.GetString(key, string.Empty));
            SettingsStore.Set("Signal.LegacyBackupCreated", true);
            SettingsStore.Set("Signal.1.Name", "信号 1");
            SettingsStore.Set("Signal.1.CaptureMode", SettingsStore.GetString("CaptureMode", CaptureMode.ScreenRegion.ToString()));
            SettingsStore.Set("Signal.1.TargetWindowTitle", SettingsStore.GetString("TargetWindowTitle", string.Empty));
            SettingsStore.Set("Signal.1.WindowSubRegion", SettingsStore.GetString("WindowSubRegion", string.Empty));
            SettingsStore.Set("Signal.1.ScreenRegion", SettingsStore.GetString("ScreenRegion", string.Empty));
            SettingsStore.Set("Signal.1.Masks", LegacyMasksToCompact(SettingsStore.GetString("MaskRegions", string.Empty)));
            SettingsStore.Set("Signal.1.ModelKey", ModelManager.ModelKeys[Math.Clamp(SettingsStore.GetInt("SelectedModelIndex", 0), 0, ModelManager.ModelKeys.Length - 1)]);
            SettingsStore.Set("Signal.1.Targets", SettingsStore.GetString("WatchedClasses", "person"));
            SettingsStore.Set("Signal.1.Threshold", SettingsStore.GetInt("ConfidenceThresholdPct", 45));
            SettingsStore.Set("Signal.1.Fps", Math.Clamp(SettingsStore.GetInt("TargetFps", 3), 1, 5));
            SettingsStore.Set("Signal.1.Cooldown", SettingsStore.GetInt("AlertCooldownSeconds", 5));
            SettingsStore.Set("Signal.1.Initialized", true);
            SettingsStore.Set("Signal.LegacyMigrationCompleted", true);
            SettingsStore.Save();
        }

        private static string LegacyMasksToCompact(string json)
        {
            if (string.IsNullOrWhiteSpace(json)) return string.Empty;
            var dtos = SimpleJson.Deserialize<List<MaskRegionDto>>(json);
            if (dtos == null) return string.Empty;
            return string.Join(";", dtos.Where(d => d.right > d.left && d.bottom > d.top).Select(d => string.Join(",",
                d.left.ToString("R", System.Globalization.CultureInfo.InvariantCulture), d.top.ToString("R", System.Globalization.CultureInfo.InvariantCulture),
                (d.right - d.left).ToString("R", System.Globalization.CultureInfo.InvariantCulture), (d.bottom - d.top).ToString("R", System.Globalization.CultureInfo.InvariantCulture))));
        }

        public void Dispose() => _coordinator.Dispose();
    }

    public sealed class SignalSourceViewModel : ViewModelBase
    {
        private readonly MultiSourceViewModel _owner;
        private readonly int _index;
        private string _sourceName = "", _modelKey = "", _targets = "", _statusText = "未配置", _targetWindowTitle = "";
        private string _targetWindowClassName = "", _targetWindowProcessName = "", _windowResolutionError = "";
        private int _thresholdPercent, _targetFps, _cooldown;
        private bool _isMonitoring, _isSelected, _isDirty, _syncingTargetOptions;
        private CaptureMode _captureMode;
        private Rectangle _screenRegion, _windowSubRegion;
        private WindowInfo? _targetWindow;
        private SavedState _saved = null!;
        private BitmapSource? _previewImage;
        private double _frameWidth, _frameHeight;
        private string _lastFrameText = "尚无画面", _inferenceText = "推理 — ms", _lastAlertText = "最后报警 —", _backendText = "后端 —";

        public string SourceId { get; }
        public string DisplayIndex => $"信号 {_index}";
        public string[] ModelOptions => ModelManager.ModelKeys;
        public ObservableCollection<DetectionItem> Detections { get; } = new();
        public ObservableCollection<DetectionClassOption> TargetOptions { get; } = new();
        public List<RectangleF> MaskRegions { get; private set; } = new();
        public string SourceName { get => _sourceName; set { if (SetProperty(ref _sourceName, value)) MarkDirty(); } }
        public string ModelKey { get => _modelKey; set { if (SetProperty(ref _modelKey, value)) MarkDirty(); } }
        public string Targets
        {
            get => _targets;
            set
            {
                var normalized = NormalizeTargets(value);
                if (!SetProperty(ref _targets, normalized)) return;
                SyncTargetOptions();
                OnPropertyChanged(nameof(TargetSummary));
                MarkDirty();
            }
        }
        public string TargetSummary
        {
            get
            {
                var selected = TargetOptions.Where(option => option.IsSelected).ToList();
                if (selected.Count == 0) return "请选择检测类别";
                if (selected.Count == 1) return selected[0].DisplayName;
                if (selected.Count == 2) return string.Join("、", selected.Select(option => option.ChineseName));
                return $"{string.Join("、", selected.Take(2).Select(option => option.ChineseName))}等 {selected.Count} 类";
            }
        }
        public int ThresholdPercent { get => _thresholdPercent; set { if (SetProperty(ref _thresholdPercent, Math.Clamp(value, 10, 95))) MarkDirty(); } }
        public int TargetFps { get => _targetFps; set { if (SetProperty(ref _targetFps, Math.Clamp(value, 1, 5))) MarkDirty(); } }
        public int Cooldown { get => _cooldown; set { if (SetProperty(ref _cooldown, Math.Clamp(value, 1, 300))) MarkDirty(); } }
        public bool IsMonitoring { get => _isMonitoring; private set { if (SetProperty(ref _isMonitoring, value)) RaiseCommandStates(); } }
        public bool IsSelected { get => _isSelected; internal set => SetProperty(ref _isSelected, value); }
        public bool IsDirty { get => _isDirty; private set { if (SetProperty(ref _isDirty, value)) { OnPropertyChanged(nameof(CanStart)); RaiseCommandStates(); } } }
        public bool CanEdit => !IsMonitoring;
        public bool CanStart => !IsMonitoring && IsReady && !IsDirty;
        public bool IsReady => _captureMode == CaptureMode.WindowHandle
            ? _targetWindow != null && CaptureSizeConstraints.IsValid(_targetWindow.Bounds)
            : CaptureSizeConstraints.IsValid(_screenRegion);
        public string StatusText { get => _statusText; private set => SetProperty(ref _statusText, value); }
        public string TargetInfo => _captureMode == CaptureMode.WindowHandle
            ? (string.IsNullOrWhiteSpace(_targetWindowTitle) ? "未选择窗口" : $"窗口：{_targetWindowTitle}{(_windowSubRegion == Rectangle.Empty ? "" : $" · 选区 {_windowSubRegion.Width}×{_windowSubRegion.Height}")}")
            : (CaptureSizeConstraints.IsValid(_screenRegion) ? $"屏幕选区：{_screenRegion.X},{_screenRegion.Y} {_screenRegion.Width}×{_screenRegion.Height}" : "未选择屏幕区域");
        public string MaskInfo => MaskRegions.Count == 0 ? "无遮罩" : $"{MaskRegions.Count} 个遮罩";
        public BitmapSource? PreviewImage { get => _previewImage; private set => SetProperty(ref _previewImage, value); }
        public double FrameWidth { get => _frameWidth; private set => SetProperty(ref _frameWidth, value); }
        public double FrameHeight { get => _frameHeight; private set => SetProperty(ref _frameHeight, value); }
        public string LastFrameText { get => _lastFrameText; private set => SetProperty(ref _lastFrameText, value); }
        public string InferenceText { get => _inferenceText; private set => SetProperty(ref _inferenceText, value); }
        public string LastAlertText { get => _lastAlertText; private set => SetProperty(ref _lastAlertText, value); }
        public string BackendText { get => _backendText; private set => SetProperty(ref _backendText, value); }

        public RelayCommand SelectCommand { get; }
        public RelayCommand PickWindowCommand { get; }
        public RelayCommand SelectRegionCommand { get; }
        public RelayCommand ClearTargetCommand { get; }
        public RelayCommand EditMasksCommand { get; }
        public RelayCommand SaveCommand { get; }
        public RelayCommand CancelCommand { get; }
        public RelayCommand StartCommand { get; }
        public RelayCommand StopCommand { get; }

        internal SignalSourceViewModel(int index, MultiSourceViewModel owner)
        {
            _owner = owner; _index = index; SourceId = index == 1 ? "default" : $"signal-{index}";
            Load();
            InitializeTargetOptions();
            _saved = CaptureState();
            SelectCommand = new RelayCommand(() => _owner.Select(this));
            PickWindowCommand = new RelayCommand(PickWindow, () => CanEdit);
            SelectRegionCommand = new RelayCommand(SelectRegion, () => CanEdit);
            ClearTargetCommand = new RelayCommand(ClearTarget, () => CanEdit && (!string.IsNullOrWhiteSpace(_targetWindowTitle) || _screenRegion != Rectangle.Empty));
            EditMasksCommand = new RelayCommand(EditMasks, () => CanEdit && IsReady);
            SaveCommand = new RelayCommand(SaveDraft, () => CanEdit);
            CancelCommand = new RelayCommand(CancelDraft, () => CanEdit);
            StartCommand = new RelayCommand(Start, () => CanStart);
            StopCommand = new RelayCommand(() => _owner.Stop(this), () => IsMonitoring);
            StatusText = IsReady ? "就绪" : (!string.IsNullOrWhiteSpace(_targetWindowTitle) ? (string.IsNullOrWhiteSpace(_windowResolutionError) ? "窗口未找到" : _windowResolutionError) : "未配置");
        }

        private string Prefix => $"Signal.{_index}.";

        private void Load()
        {
            _sourceName = SettingsStore.GetString(Prefix + "Name", $"信号 {_index}");
            _modelKey = SettingsStore.GetString(Prefix + "ModelKey", "yolo26n_320");
            if (!ModelManager.ModelKeys.Contains(_modelKey)) _modelKey = "yolo26n_320";
            _targets = NormalizeTargets(SettingsStore.GetString(Prefix + "Targets", "person"));
            _thresholdPercent = Math.Clamp(SettingsStore.GetInt(Prefix + "Threshold", 45), 10, 95);
            _targetFps = Math.Clamp(SettingsStore.GetInt(Prefix + "Fps", 3), 1, 5);
            _cooldown = Math.Clamp(SettingsStore.GetInt(Prefix + "Cooldown", 5), 1, 300);
            _captureMode = Enum.TryParse<CaptureMode>(SettingsStore.GetString(Prefix + "CaptureMode", CaptureMode.ScreenRegion.ToString()), out var mode) && mode == CaptureMode.WindowHandle ? CaptureMode.WindowHandle : CaptureMode.ScreenRegion;
            _targetWindowTitle = SettingsStore.GetString(Prefix + "TargetWindowTitle", string.Empty);
            _targetWindowClassName = SettingsStore.GetString(Prefix + "TargetWindowClassName", string.Empty);
            _targetWindowProcessName = SettingsStore.GetString(Prefix + "TargetWindowProcessName", string.Empty);
            _screenRegion = ParseRectangle(SettingsStore.GetString(Prefix + "ScreenRegion", string.Empty));
            _windowSubRegion = ParseRectangle(SettingsStore.GetString(Prefix + "WindowSubRegion", string.Empty));
            MaskRegions = ParseMasks(SettingsStore.GetString(Prefix + "Masks", string.Empty));
            ResolveWindow();
        }

        private void InitializeTargetOptions()
        {
            foreach (var englishName in CocoClassMap.EnglishNames)
            {
                TargetOptions.Add(new DetectionClassOption(englishName, CocoClassMap.EnZh[englishName], OnTargetOptionChanged));
            }
            SyncTargetOptions();
        }

        private void SyncTargetOptions()
        {
            if (TargetOptions.Count == 0) return;
            var selectedNames = _targets.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
                .ToHashSet(StringComparer.OrdinalIgnoreCase);
            _syncingTargetOptions = true;
            try
            {
                foreach (var option in TargetOptions) option.IsSelected = selectedNames.Contains(option.EnglishName);
            }
            finally { _syncingTargetOptions = false; }
            OnPropertyChanged(nameof(TargetSummary));
        }

        private void OnTargetOptionChanged(DetectionClassOption changedOption)
        {
            if (_syncingTargetOptions) return;
            var selected = TargetOptions.Where(option => option.IsSelected).ToList();
            if (selected.Count == 0)
            {
                _syncingTargetOptions = true;
                changedOption.IsSelected = true;
                _syncingTargetOptions = false;
                return;
            }
            Targets = string.Join(',', selected.Select(option => option.EnglishName));
        }

        private static string NormalizeTargets(string? value)
        {
            var targets = (value ?? string.Empty).Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
                .Distinct(StringComparer.OrdinalIgnoreCase);
            var normalized = string.Join(',', targets);
            return string.IsNullOrWhiteSpace(normalized) ? "person" : normalized;
        }

        internal MonitorSource BuildSource()
        {
            var config = new MonitorConfig
            {
                CaptureMode = _captureMode, CaptureRegion = _screenRegion, TargetWindowTitle = _targetWindowTitle,
                TargetWindowClassName = _targetWindowClassName, TargetWindowProcessName = _targetWindowProcessName,
                TargetWindowHandle = _targetWindow?.Handle ?? IntPtr.Zero, WindowSubRegion = _windowSubRegion,
                ConfidenceThreshold = ThresholdPercent / 100f, AlertCooldownSeconds = Cooldown, TargetFps = TargetFps,
                WatchedClasses = Targets.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).ToHashSet(StringComparer.OrdinalIgnoreCase),
                MaskRegions = new List<RectangleF>(MaskRegions), SaveAlertSnapshot = true,
            };
            if (config.WatchedClasses.Count == 0) config.WatchedClasses.Add("person");
            return new MonitorSource(SourceId, string.IsNullOrWhiteSpace(SourceName) ? DisplayIndex : SourceName.Trim(), ModelKey, config, _owner.PreferredBackend);
        }

        internal bool ResolveWindowForStart()
        {
            if (_captureMode == CaptureMode.WindowHandle) { ResolveWindow(); if (_targetWindow == null) { StatusText = _windowResolutionError; OnPropertyChanged(nameof(IsReady)); return false; } }
            if (!IsReady) { StatusText = "采集目标宽度和高度必须都大于 100 像素。"; return false; }
            return true;
        }

        private void ResolveWindow()
        {
            _targetWindow = null;
            _windowResolutionError = "";
            if (_captureMode != CaptureMode.WindowHandle || string.IsNullOrWhiteSpace(_targetWindowTitle)) return;
            var main = Application.Current?.MainWindow;
            var excluded = main == null ? IntPtr.Zero : new System.Windows.Interop.WindowInteropHelper(main).Handle;
            var match = WindowMatchResolver.Resolve(
                WindowEnumerator.GetWindows(excluded),
                _targetWindowTitle,
                _targetWindowClassName,
                _targetWindowProcessName);
            _targetWindow = match.Window;
            _windowResolutionError = match.Status == WindowMatchStatus.Ambiguous
                ? "发现多个符合配置的窗口，无法安全自动重绑，请重新选择目标窗口。"
                : match.Status == WindowMatchStatus.NotFound
                    ? "目标窗口不存在、已最小化或尺寸过小，请重新选择窗口。"
                    : "";
        }

        private void PickWindow()
        {
            var main = Application.Current.MainWindow;
            var excluded = main == null ? IntPtr.Zero : new System.Windows.Interop.WindowInteropHelper(main).Handle;
            var picker = new WindowPickerWindow(excluded) { Owner = main };
            if (picker.ShowDialog() != true || picker.SelectedWindow == null || !ConfirmTargetChange()) return;
            _captureMode = CaptureMode.WindowHandle; _targetWindow = picker.SelectedWindow; _targetWindowTitle = picker.SelectedWindow.Title;
            _targetWindowClassName = picker.SelectedWindow.ClassName; _targetWindowProcessName = picker.SelectedWindow.ProcessName; _windowResolutionError = "";
            _screenRegion = Rectangle.Empty; _windowSubRegion = Rectangle.Empty; ClearMasksInternal(); NotifyTargetChanged();
        }

        private void SelectRegion()
        {
            BitmapSource? background = null;
            var windowMode = _captureMode == CaptureMode.WindowHandle && !string.IsNullOrWhiteSpace(_targetWindowTitle);
            if (windowMode && _targetWindow == null) ResolveWindow();
            if (windowMode && _targetWindow == null) { MessageBox.Show("目标窗口当前不存在，请重新选择窗口或清除目标后选择屏幕区域。", "VisionGuard", MessageBoxButton.OK, MessageBoxImage.Warning); return; }
            try { using var bitmap = windowMode ? WindowCapturer.CaptureWindow(_targetWindow!.Handle, Rectangle.Empty) : ScreenCapturer.CapturePrimaryScreen(); background = MonitorViewModel.ConvertBitmapToSource(bitmap); } catch { }
            var selector = new RegionSelectorWindow(background) { Owner = Application.Current.MainWindow };
            selector.ShowDialog();
            if (!selector.IsConfirmed || !ConfirmTargetChange()) return;
            if (windowMode) _windowSubRegion = selector.SelectedRegion;
            else { _captureMode = CaptureMode.ScreenRegion; _screenRegion = selector.SelectedRegion; _targetWindow = null; _targetWindowTitle = string.Empty; _targetWindowClassName = string.Empty; _targetWindowProcessName = string.Empty; _windowResolutionError = ""; _windowSubRegion = Rectangle.Empty; }
            ClearMasksInternal(); NotifyTargetChanged();
        }

        private void ClearTarget()
        {
            if (!ConfirmTargetChange()) return;
            _captureMode = CaptureMode.ScreenRegion; _targetWindow = null; _targetWindowTitle = string.Empty; _targetWindowClassName = string.Empty; _targetWindowProcessName = string.Empty; _windowResolutionError = "";
            _screenRegion = Rectangle.Empty; _windowSubRegion = Rectangle.Empty; ClearMasksInternal(); NotifyTargetChanged();
        }

        private bool ConfirmTargetChange() => MaskRegions.Count == 0 || MessageBox.Show("更换捕获目标或选区会清除当前遮罩。是否继续？", "VisionGuard", MessageBoxButton.YesNo, MessageBoxImage.Warning) == MessageBoxResult.Yes;

        private void EditMasks()
        {
            try
            {
                using var bitmap = GrabFrame();
                var editor = new MaskEditorWindow(MonitorViewModel.ConvertBitmapToSource(bitmap), MaskRegions) { Owner = Application.Current.MainWindow };
                editor.ShowDialog();
                if (!editor.IsConfirmed) return;
                MaskRegions = editor.ResultMasks; OnPropertyChanged(nameof(MaskInfo)); MarkDirty();
            }
            catch (Exception ex) { MessageBox.Show($"抓图失败：{ex.Message}", "VisionGuard", MessageBoxButton.OK, MessageBoxImage.Error); }
        }

        private Bitmap GrabFrame()
        {
            if (_captureMode == CaptureMode.WindowHandle && _targetWindow != null) return WindowCapturer.CaptureWindow(_targetWindow.Handle, _windowSubRegion);
            if (_captureMode == CaptureMode.ScreenRegion && CaptureSizeConstraints.IsValid(_screenRegion)) return ScreenCapturer.CaptureRegion(_screenRegion);
            throw new InvalidOperationException("尚未配置有效捕获目标。");
        }

        internal void SaveDraft()
        {
            if (IsMonitoring) throw new InvalidOperationException("请先停止该来源再保存配置。");
            SourceName = string.IsNullOrWhiteSpace(SourceName) ? DisplayIndex : SourceName.Trim();
            PersistCurrent(); SettingsStore.Save(); _saved = CaptureState(); IsDirty = false; _owner.Reconfigure(this);
        }

        internal void CommitSourceNameEdit()
        {
            SourceName = string.IsNullOrWhiteSpace(SourceName) ? DisplayIndex : SourceName.Trim();
            SettingsStore.Set(Prefix + "Name", SourceName);
            SettingsStore.Save();
            _saved = _saved with { SourceName = SourceName };
            IsDirty = HasUnsavedChanges();
            if (!IsDirty) RefreshIdleStatus();
            _owner.Rename(this);
        }

        internal void CancelSourceNameEdit(string originalName)
        {
            SourceName = originalName;
            IsDirty = HasUnsavedChanges();
            if (!IsDirty) RefreshIdleStatus();
        }

        private void CancelDraft() { RestoreState(_saved); _owner.Reconfigure(this); }

        internal void PersistCurrent()
        {
            SettingsStore.Set(Prefix + "Initialized", true); SettingsStore.Set(Prefix + "Name", SourceName);
            SettingsStore.Set(Prefix + "CaptureMode", _captureMode.ToString()); SettingsStore.Set(Prefix + "TargetWindowTitle", _targetWindowTitle);
            SettingsStore.Set(Prefix + "TargetWindowClassName", _targetWindowClassName); SettingsStore.Set(Prefix + "TargetWindowProcessName", _targetWindowProcessName);
            SettingsStore.Set(Prefix + "WindowSubRegion", FormatRectangle(_windowSubRegion)); SettingsStore.Set(Prefix + "ScreenRegion", FormatRectangle(_screenRegion));
            SettingsStore.Set(Prefix + "ModelKey", ModelKey); SettingsStore.Set(Prefix + "Targets", Targets);
            SettingsStore.Set(Prefix + "Threshold", ThresholdPercent); SettingsStore.Set(Prefix + "Fps", TargetFps); SettingsStore.Set(Prefix + "Cooldown", Cooldown);
            SettingsStore.Set(Prefix + "Masks", FormatMasks(MaskRegions));
        }

        private SavedState CaptureState() => new(SourceName, ModelKey, Targets, ThresholdPercent, TargetFps, Cooldown, _captureMode, _targetWindowTitle, _targetWindowClassName, _targetWindowProcessName, _screenRegion, _windowSubRegion, new List<RectangleF>(MaskRegions));

        private bool HasUnsavedChanges()
            => SourceName != _saved.SourceName
                || ModelKey != _saved.ModelKey
                || Targets != _saved.Targets
                || ThresholdPercent != _saved.ThresholdPercent
                || TargetFps != _saved.TargetFps
                || Cooldown != _saved.Cooldown
                || _captureMode != _saved.CaptureMode
                || _targetWindowTitle != _saved.TargetWindowTitle
                || _targetWindowClassName != _saved.TargetWindowClassName
                || _targetWindowProcessName != _saved.TargetWindowProcessName
                || _screenRegion != _saved.ScreenRegion
                || _windowSubRegion != _saved.WindowSubRegion
                || !MaskRegions.SequenceEqual(_saved.Masks);

        private void RefreshIdleStatus()
            => StatusText = IsReady
                ? (PreviewImage == null ? "就绪" : "已停止 · 保留最后画面")
                : (!string.IsNullOrWhiteSpace(_targetWindowTitle) ? (string.IsNullOrWhiteSpace(_windowResolutionError) ? "窗口未找到" : _windowResolutionError) : "未配置");
        private void RestoreState(SavedState state)
        {
            SourceName = state.SourceName; ModelKey = state.ModelKey; Targets = state.Targets; ThresholdPercent = state.ThresholdPercent; TargetFps = state.TargetFps; Cooldown = state.Cooldown;
            _captureMode = state.CaptureMode; _targetWindowTitle = state.TargetWindowTitle; _targetWindowClassName = state.TargetWindowClassName; _targetWindowProcessName = state.TargetWindowProcessName; _screenRegion = state.ScreenRegion; _windowSubRegion = state.WindowSubRegion;
            MaskRegions = new List<RectangleF>(state.Masks); ResolveWindow(); IsDirty = false;
            OnPropertyChanged(nameof(TargetInfo)); OnPropertyChanged(nameof(MaskInfo)); OnPropertyChanged(nameof(IsReady)); RaiseCommandStates();
        }

        private void Start() { try { _owner.Start(this); } catch (Exception ex) { SetError(ex.Message); } }
        internal void MarkStarting() => StatusText = "启动中";
        internal void SetError(string message) => StatusText = string.IsNullOrWhiteSpace(message) ? "异常" : message;
        internal void ApplyStatus(MonitorSourceStatus status)
        {
            IsMonitoring = status.IsMonitoring;
            BackendText = status.ActiveBackend == "Unavailable" ? "后端 —" : $"{status.ActiveBackend} · {status.ActualFps:0.0} FPS";
            StatusText = !string.IsNullOrWhiteSpace(status.Error)
                ? $"异常：{status.Error}"
                : status.IsMonitoring
                    ? "运行中"
                    : IsDirty
                        ? "配置待保存"
                        : (IsReady ? (PreviewImage == null ? "就绪" : "已停止 · 保留最后画面") : (!string.IsNullOrWhiteSpace(_targetWindowTitle) ? (string.IsNullOrWhiteSpace(_windowResolutionError) ? "窗口未找到" : _windowResolutionError) : "未配置"));
        }

        internal void ApplyFrame(BitmapSource image, List<Detection> detections, long inferenceMs)
        {
            PreviewImage = image; FrameWidth = image.PixelWidth; FrameHeight = image.PixelHeight; Detections.Clear();
            foreach (var d in detections) Detections.Add(new DetectionItem { Left = d.BoundingBox.Left, Top = d.BoundingBox.Top, Width = d.BoundingBox.Width, Height = d.BoundingBox.Height, Label = $"{d.Label} {(int)(d.Confidence * 100)}%" });
            LastFrameText = $"更新 {DateTime.Now:HH:mm:ss}";
            InferenceText = $"推理 {inferenceMs} ms";
        }

        internal void ApplyAlert(AlertEvent alert)
        {
            var target = alert.Detections.FirstOrDefault()?.Label ?? "目标";
            LastAlertText = $"最后报警 {DateTime.Now:HH:mm:ss} · {target} ×{alert.Detections.Count}";
        }

        private void NotifyTargetChanged() { IsDirty = true; StatusText = IsReady ? "配置待保存" : "未配置"; OnPropertyChanged(nameof(TargetInfo)); OnPropertyChanged(nameof(MaskInfo)); OnPropertyChanged(nameof(IsReady)); OnPropertyChanged(nameof(CanStart)); RaiseCommandStates(); }
        private void ClearMasksInternal() { MaskRegions.Clear(); OnPropertyChanged(nameof(MaskInfo)); }
        private void MarkDirty()
        {
            if (_saved != null)
            {
                IsDirty = true;
                if (!IsMonitoring) StatusText = "配置待保存";
            }
        }
        private void RaiseCommandStates()
        {
            OnPropertyChanged(nameof(CanEdit)); OnPropertyChanged(nameof(CanStart)); PickWindowCommand?.RaiseCanExecuteChanged(); SelectRegionCommand?.RaiseCanExecuteChanged(); ClearTargetCommand?.RaiseCanExecuteChanged();
            EditMasksCommand?.RaiseCanExecuteChanged(); SaveCommand?.RaiseCanExecuteChanged(); CancelCommand?.RaiseCanExecuteChanged(); StartCommand?.RaiseCanExecuteChanged(); StopCommand?.RaiseCanExecuteChanged();
        }

        private static Rectangle ParseRectangle(string value)
        {
            var p = value.Split(',');
            return p.Length == 4 && int.TryParse(p[0], out var x) && int.TryParse(p[1], out var y) && int.TryParse(p[2], out var w) && int.TryParse(p[3], out var h) && w > 0 && h > 0 ? new Rectangle(x, y, w, h) : Rectangle.Empty;
        }
        private static string FormatRectangle(Rectangle value) => value == Rectangle.Empty ? string.Empty : $"{value.X},{value.Y},{value.Width},{value.Height}";
        private static List<RectangleF> ParseMasks(string value)
        {
            var result = new List<RectangleF>();
            foreach (var item in value.Split(';', StringSplitOptions.RemoveEmptyEntries))
            {
                var p = item.Split(',');
                if (p.Length == 4 && float.TryParse(p[0], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var x) && float.TryParse(p[1], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var y)
                    && float.TryParse(p[2], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var w) && float.TryParse(p[3], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var h)
                    && x >= 0 && y >= 0 && w > 0 && h > 0 && x + w <= 1 && y + h <= 1) result.Add(new RectangleF(x, y, w, h));
            }
            return result;
        }
        private static string FormatMasks(IEnumerable<RectangleF> masks) => string.Join(";", masks.Select(r => string.Join(",", r.X.ToString("R", System.Globalization.CultureInfo.InvariantCulture), r.Y.ToString("R", System.Globalization.CultureInfo.InvariantCulture), r.Width.ToString("R", System.Globalization.CultureInfo.InvariantCulture), r.Height.ToString("R", System.Globalization.CultureInfo.InvariantCulture))));
        private sealed record SavedState(string SourceName, string ModelKey, string Targets, int ThresholdPercent, int TargetFps, int Cooldown, CaptureMode CaptureMode, string TargetWindowTitle, string TargetWindowClassName, string TargetWindowProcessName, Rectangle ScreenRegion, Rectangle WindowSubRegion, List<RectangleF> Masks);
    }

    public sealed class DetectionClassOption : ViewModelBase
    {
        private readonly Action<DetectionClassOption> _selectionChanged;
        private bool _isSelected;

        public string EnglishName { get; }
        public string ChineseName { get; }
        public string DisplayName => $"{ChineseName} · {EnglishName}";
        public bool IsSelected
        {
            get => _isSelected;
            set
            {
                if (SetProperty(ref _isSelected, value)) _selectionChanged(this);
            }
        }

        public DetectionClassOption(string englishName, string chineseName, Action<DetectionClassOption> selectionChanged)
        {
            EnglishName = englishName;
            ChineseName = chineseName;
            _selectionChanged = selectionChanged;
        }
    }
}
