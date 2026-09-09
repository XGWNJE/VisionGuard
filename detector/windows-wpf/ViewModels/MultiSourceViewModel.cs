using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.IO;
using System.Linq;
using System.Windows;
using Microsoft.Win32;
using System.Drawing;
using VisionGuard.Views;
using VisionGuard.Inference;
using VisionGuard.Models;
using VisionGuard.Services;
using VisionGuard.Utils;

namespace VisionGuard.ViewModels
{
    public sealed class MultiSourceViewModel : ViewModelBase, IDisposable
    {
        private readonly MultiSourceMonitorCoordinator _coordinator = new();
        private readonly ServerPushService _server;
        private readonly MainViewModel _main;
        public ObservableCollection<ImageSourceSlotViewModel> Sources { get; } = new();
        public IReadOnlyList<MonitorSourceStatus> Statuses => _coordinator.Statuses;
        public bool IsAnyMonitoring => Statuses.Any(s => s.IsMonitoring);
        public bool IsAnyReady => Statuses.Any(s => s.IsReady);

        public MultiSourceViewModel(ServerPushService server, MainViewModel main)
        {
            _server = server;
            _main = main;
            for (var i = 1; i <= MultiSourceMonitorCoordinator.MaxSources; i++)
            {
                var slot = new ImageSourceSlotViewModel(i, this);
                Sources.Add(slot);
                _coordinator.Add(slot.BuildSource());
            }
            _coordinator.AlertTriggered += (_, alert) => _server.PushAlert(alert);
            _coordinator.StatusChanged += (_, status) => Application.Current.Dispatcher.BeginInvoke(() =>
            {
                Sources.First(s => s.SourceId == status.SourceId).ApplyStatus(status);
                OnPropertyChanged(nameof(IsAnyMonitoring));
                OnPropertyChanged(nameof(IsAnyReady));
            });
            _coordinator.FrameProcessed += (_, e) =>
            {
                if (e.Frame.HasError) { e.Frame.Frame?.Dispose(); return; }
                Application.Current.Dispatcher.BeginInvoke(() =>
                {
                    using (e.Frame.Frame)
                    {
                        var image = MonitorViewModel.ConvertBitmapToSource(e.Frame.Frame);
                        _main.UpdatePreview(image, e.Frame.Detections);
                        _main.InferMsText = $"{e.SourceId} · 推理 {e.Frame.InferenceMs} ms";
                    }
                });
            };
        }

        internal void Reconfigure(ImageSourceSlotViewModel slot)
        {
            if (slot.IsMonitoring) throw new InvalidOperationException("请先停止该来源再修改配置。");
            _coordinator.Remove(slot.SourceId);
            _coordinator.Add(slot.BuildSource());
        }

        internal void Start(ImageSourceSlotViewModel slot)
        {
            Reconfigure(slot);
            var modelPath = ModelManager.GetModelPath(slot.ModelKey);
            if (!File.Exists(modelPath)) throw new FileNotFoundException("模型未下载，请先在设置页下载。", modelPath);
            _coordinator.Start(slot.SourceId, modelPath);
        }

        internal void Stop(ImageSourceSlotViewModel slot) => _coordinator.Stop(slot.SourceId);

        public bool HandleCommand(string sourceId, string command, string requestId)
        {
            var slot = Sources.FirstOrDefault(s => s.SourceId == sourceId);
            if (slot == null) { _server.SendCommandAck(command, false, "来源不存在", requestId, sourceId); return false; }
            try
            {
                if (command == "resume") Start(slot);
                else if (command == "pause") Stop(slot);
                else { _server.SendCommandAck(command, false, "来源不支持该命令", requestId, sourceId); return false; }
                _server.SendCommandAck(command, true, requestId: requestId, targetSourceId: sourceId);
                return true;
            }
            catch (Exception ex) { _server.SendCommandAck(command, false, ex.Message, requestId, sourceId); return false; }
        }

        public bool HandleConfig(string sourceId, string key, string value, string requestId)
        {
            var slot = Sources.FirstOrDefault(s => s.SourceId == sourceId);
            var command = $"set-config:{key}";
            if (slot == null) { _server.SendCommandAck(command, false, "来源不存在", requestId, sourceId); return false; }
            try
            {
                if (slot.IsMonitoring) throw new InvalidOperationException("请先停止该来源再修改配置。");
                switch (key)
                {
                    case "cooldown" when int.TryParse(value, out var cooldown) && cooldown is >= 1 and <= 300: slot.Cooldown = cooldown; break;
                    case "confidence" when float.TryParse(value, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var confidence) && confidence is >= 0.1f and <= 0.95f: slot.ThresholdPercent = (int)Math.Round(confidence * 100); break;
                    case "targetSamplingRate" when int.TryParse(value, out var fps) && fps is >= 1 and <= 30: slot.TargetFps = fps; break;
                    case "targets": slot.Targets = value; break;
                    case "modelKey" when ModelManager.ModelKeys.Contains(value): slot.ModelKey = value; break;
                    default: throw new ArgumentException("配置值无效或不支持。");
                }
                slot.Save(); Reconfigure(slot);
                _server.SendCommandAck(command, true, requestId: requestId, targetSourceId: sourceId);
                return true;
            }
            catch (Exception ex) { _server.SendCommandAck(command, false, ex.Message, requestId, sourceId); return false; }
        }

        public void Save() { foreach (var source in Sources) source.Save(); SettingsStore.Save(); }
        public void Dispose() => _coordinator.Dispose();
    }

    public sealed class ImageSourceSlotViewModel : ViewModelBase
    {
        private readonly MultiSourceViewModel _owner;
        private string _sourceName, _imagePath, _modelKey, _targets, _statusText = "未配置图片";
        private int _thresholdPercent, _targetFps, _cooldown;
        private bool _isMonitoring;
        public List<RectangleF> MaskRegions { get; private set; }
        public string SourceId { get; }
        public string[] ModelOptions => ModelManager.ModelKeys;
        public string SourceName { get => _sourceName; set => SetProperty(ref _sourceName, value); }
        public string ImagePath { get => _imagePath; set { if (SetProperty(ref _imagePath, value)) OnPropertyChanged(nameof(ImageFileName)); } }
        public string ImageFileName => string.IsNullOrWhiteSpace(ImagePath) ? "未选择图片" : Path.GetFileName(ImagePath);
        public string ModelKey { get => _modelKey; set => SetProperty(ref _modelKey, value); }
        public string Targets { get => _targets; set => SetProperty(ref _targets, value); }
        public int ThresholdPercent { get => _thresholdPercent; set => SetProperty(ref _thresholdPercent, Math.Clamp(value, 10, 95)); }
        public int TargetFps { get => _targetFps; set => SetProperty(ref _targetFps, Math.Clamp(value, 1, 30)); }
        public int Cooldown { get => _cooldown; set => SetProperty(ref _cooldown, Math.Clamp(value, 1, 300)); }
        public bool IsMonitoring { get => _isMonitoring; private set { if (SetProperty(ref _isMonitoring, value)) { StartCommand.RaiseCanExecuteChanged(); StopCommand.RaiseCanExecuteChanged(); PickImageCommand.RaiseCanExecuteChanged(); } } }
        public string StatusText { get => _statusText; private set => SetProperty(ref _statusText, value); }
        public string MaskInfo => MaskRegions.Count == 0 ? "无遮罩" : $"{MaskRegions.Count} 个遮罩";
        public RelayCommand PickImageCommand { get; }
        public RelayCommand EditMasksCommand { get; }
        public RelayCommand StartCommand { get; }
        public RelayCommand StopCommand { get; }

        internal ImageSourceSlotViewModel(int index, MultiSourceViewModel owner)
        {
            _owner = owner; SourceId = $"image-{index}";
            var p = $"MultiSource.{index}.";
            _sourceName = SettingsStore.GetString(p + "Name", $"图片来源 {index}");
            _imagePath = SettingsStore.GetString(p + "ImagePath", "");
            _modelKey = SettingsStore.GetString(p + "ModelKey", "yolo26n_320");
            _targets = SettingsStore.GetString(p + "Targets", "person");
            _thresholdPercent = SettingsStore.GetInt(p + "Threshold", 45);
            _targetFps = SettingsStore.GetInt(p + "Fps", 3);
            _cooldown = SettingsStore.GetInt(p + "Cooldown", 5);
            MaskRegions = ParseMasks(SettingsStore.GetString(p + "Masks", ""));
            PickImageCommand = new RelayCommand(PickImage, () => !IsMonitoring);
            EditMasksCommand = new RelayCommand(EditMasks, () => !IsMonitoring && File.Exists(ImagePath));
            StartCommand = new RelayCommand(Start, () => !IsMonitoring && File.Exists(ImagePath));
            StopCommand = new RelayCommand(() => _owner.Stop(this), () => IsMonitoring);
        }

        private void PickImage()
        {
            var dialog = new OpenFileDialog { Filter = "图片|*.png;*.jpg;*.jpeg;*.bmp", CheckFileExists = true };
            if (dialog.ShowDialog() == true) { ImagePath = dialog.FileName; StatusText = "就绪"; Save(); StartCommand.RaiseCanExecuteChanged(); EditMasksCommand.RaiseCanExecuteChanged(); _owner.Reconfigure(this); }
        }
        private void EditMasks()
        {
            using var bmp = new Bitmap(ImagePath);
            var editor = new MaskEditorWindow(MonitorViewModel.ConvertBitmapToSource(bmp), MaskRegions) { Owner = Application.Current.MainWindow };
            editor.ShowDialog();
            if (!editor.IsConfirmed) return;
            MaskRegions = editor.ResultMasks;
            OnPropertyChanged(nameof(MaskInfo)); Save(); _owner.Reconfigure(this);
        }
        private void Start() { try { _owner.Start(this); } catch (Exception ex) { StatusText = ex.Message; } }
        internal MonitorSource BuildSource() => new(SourceId, SourceName, ModelKey, new MonitorConfig
        {
            CaptureMode = CaptureMode.ImageFile, ImageFilePath = ImagePath, TargetFps = TargetFps,
            ConfidenceThreshold = ThresholdPercent / 100f, AlertCooldownSeconds = Cooldown,
            WatchedClasses = Targets.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).ToHashSet(StringComparer.OrdinalIgnoreCase),
            MaskRegions = new List<RectangleF>(MaskRegions),
            SaveAlertSnapshot = true,
        }, InferenceBackend.DirectML);
        internal void ApplyStatus(MonitorSourceStatus status)
        {
            IsMonitoring = status.IsMonitoring;
            StatusText = !string.IsNullOrWhiteSpace(status.Error) ? status.Error : status.IsMonitoring
                ? $"运行中 · {status.ActiveBackend} · {status.ActualFps:0.0} FPS" : (status.IsReady ? "就绪" : "未配置图片");
        }
        internal void Save()
        {
            var i = SourceId[^1]; var p = $"MultiSource.{i}.";
            SettingsStore.Set(p + "Name", SourceName); SettingsStore.Set(p + "ImagePath", ImagePath);
            SettingsStore.Set(p + "ModelKey", ModelKey); SettingsStore.Set(p + "Targets", Targets);
            SettingsStore.Set(p + "Threshold", ThresholdPercent); SettingsStore.Set(p + "Fps", TargetFps); SettingsStore.Set(p + "Cooldown", Cooldown);
            SettingsStore.Set(p + "Masks", string.Join(";", MaskRegions.Select(r => string.Join(",",
                r.X.ToString("R", System.Globalization.CultureInfo.InvariantCulture),
                r.Y.ToString("R", System.Globalization.CultureInfo.InvariantCulture),
                r.Width.ToString("R", System.Globalization.CultureInfo.InvariantCulture),
                r.Height.ToString("R", System.Globalization.CultureInfo.InvariantCulture)))));
        }

        private static List<RectangleF> ParseMasks(string value)
        {
            var result = new List<RectangleF>();
            foreach (var item in value.Split(';', StringSplitOptions.RemoveEmptyEntries))
            {
                var p = item.Split(',');
                if (p.Length == 4 && float.TryParse(p[0], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var x)
                    && float.TryParse(p[1], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var y)
                    && float.TryParse(p[2], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var w)
                    && float.TryParse(p[3], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var h)
                    && x >= 0 && y >= 0 && w > 0 && h > 0 && x + w <= 1 && y + h <= 1)
                    result.Add(new RectangleF(x, y, w, h));
            }
            return result;
        }
    }
}
