using System;
using System.Collections.Generic;
using VisionGuard.Utils;
using VisionGuard.Runtime;

namespace VisionGuard.ViewModels
{
    public class SettingsViewModel : ViewModelBase
    {
        private int _threshold = 45;
        public int Threshold
        {
            get => _threshold;
            set => SetProperty(ref _threshold, value);
        }

        private int _samplingRate = 3;
        public int SamplingRate
        {
            get => _samplingRate;
            set => SetProperty(ref _samplingRate, value);
        }

        private int _cooldown = 5;
        public int Cooldown
        {
            get => _cooldown;
            set => SetProperty(ref _cooldown, value);
        }

        private int _selectedModelIndex;
        public int SelectedModelIndex
        {
            get => _selectedModelIndex;
            set
            {
                if (SetProperty(ref _selectedModelIndex, value))
                    OnPropertyChanged(nameof(ModelStatusText));
            }
        }

        public string ModelStatusText => Utils.ModelManager.IsDownloaded(SelectedModelName) ? "✓ 已下载" : "○ 未下载（点击下方按钮下载）";

        private int _selectedBackendIndex;
        public int SelectedBackendIndex
        {
            get => _selectedBackendIndex;
            set => SetProperty(ref _selectedBackendIndex, value);
        }

        /// <summary>
        /// 可选后端清单。legacy 档（Windows 7）只有 CPU：该档的托管 ONNX Runtime 版本不含
        /// DirectML 提供程序，且 Windows 7 本身不具备 DirectML，因此不提供该选项。
        /// </summary>
        public string[] BackendOptions => Runtime.NativeLibrarySelector.SupportsDirectMl
            ? new[] { "DirectML · GPU 加速", "CPU · 兼容与诊断" }
            : new[] { "CPU · Windows 7 固定后端" };

        public Inference.InferenceBackend PreferredBackend
        {
            get
            {
                if (!Runtime.NativeLibrarySelector.SupportsDirectMl) return Inference.InferenceBackend.Cpu;
                return SelectedBackendIndex >= 1 ? Inference.InferenceBackend.Cpu : Inference.InferenceBackend.DirectML;
            }
        }

        private int _directMlCapacity = 4;
        public int DirectMlCapacity { get => _directMlCapacity; set => SetProperty(ref _directMlCapacity, Net472Compat.Clamp(value, 1, 16)); }
        private int _cpuCapacity = 1;
        public int CpuCapacity { get => _cpuCapacity; set => SetProperty(ref _cpuCapacity, Net472Compat.Clamp(value, 1, 16)); }
        public int GetCapacity(Inference.InferenceBackend backend) => backend == Inference.InferenceBackend.Cpu ? CpuCapacity : DirectMlCapacity;

        public RelayCommand DownloadModelCommand { get; }

        private string _modelDownloadProgress = "";
        public string ModelDownloadProgress
        {
            get => _modelDownloadProgress;
            set => SetProperty(ref _modelDownloadProgress, value);
        }

        // ── 监控目标（6 类，与旧代码行为对齐）────────────────────────
        private bool _watchPerson = true;
        public bool WatchPerson
        {
            get => _watchPerson;
            set => SetProperty(ref _watchPerson, value);
        }

        private bool _watchBicycle;
        public bool WatchBicycle
        {
            get => _watchBicycle;
            set => SetProperty(ref _watchBicycle, value);
        }

        private bool _watchCar;
        public bool WatchCar
        {
            get => _watchCar;
            set => SetProperty(ref _watchCar, value);
        }

        private bool _watchMotorcycle;
        public bool WatchMotorcycle
        {
            get => _watchMotorcycle;
            set => SetProperty(ref _watchMotorcycle, value);
        }

        private bool _watchBus;
        public bool WatchBus
        {
            get => _watchBus;
            set => SetProperty(ref _watchBus, value);
        }

        private bool _watchTruck;
        public bool WatchTruck
        {
            get => _watchTruck;
            set => SetProperty(ref _watchTruck, value);
        }

        /// <summary>当前勾选的所有监控目标英文类名。</summary>
        public List<string> GetWatchedClasses()
        {
            var list = new List<string>();
            if (WatchPerson) list.Add("person");
            if (WatchBicycle) list.Add("bicycle");
            if (WatchCar) list.Add("car");
            if (WatchMotorcycle) list.Add("motorcycle");
            if (WatchBus) list.Add("bus");
            if (WatchTruck) list.Add("truck");
            return list;
        }

        /// <summary>远控设置监控目标（逗号分隔的类名，空字符串 = 全部）。</summary>
        public void SetWatchedClasses(string csv)
        {
            var set = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            if (!string.IsNullOrWhiteSpace(csv))
                foreach (var cls in csv.Split(','))
                {
                    string t = cls.Trim();
                    if (!string.IsNullOrEmpty(t)) set.Add(t);
                }

            bool all = set.Count == 0;
            WatchPerson     = all || set.Contains("person");
            WatchBicycle    = all || set.Contains("bicycle");
            WatchCar        = all || set.Contains("car");
            WatchMotorcycle = all || set.Contains("motorcycle");
            WatchBus        = all || set.Contains("bus");
            WatchTruck      = all || set.Contains("truck");
        }

        /// <summary>本档位可选的模型显示名（与 ModelManager.ModelKeys 一一对应）。</summary>
        public string[] ModelDisplayNames => Utils.ModelManager.ModelDisplayNames;

        /// <summary>当前选中的模型文件名；档位清单按运行环境决定（Win7 → yolov5，Win10+ → yolo26）。</summary>
        public string SelectedModelName
        {
            get
            {
                var keys = Utils.ModelManager.ModelKeys;
                int index = Net472Compat.Clamp(SelectedModelIndex, 0, keys.Length - 1);
                return keys[index];
            }
        }

        public string ThresholdText => $"{Threshold}%";
        public string SamplingRateText => $"{SamplingRate} 次/秒";
        public string CooldownText => $"{Cooldown} 秒";

        // ── 持久化 ───────────────────────────────────────────────────

        public void Load()
        {
            Threshold        = SettingsStore.GetInt("ConfidenceThresholdPct", 45);
            SamplingRate     = SettingsStore.GetInt("TargetFps", 3);
            Cooldown         = SettingsStore.GetInt("AlertCooldownSeconds", 5);
            // 索引必须落在本档位清单范围内：模型清单与后端清单都随档位变化。
            SelectedModelIndex = Net472Compat.Clamp(SettingsStore.GetInt("SelectedModelIndex", 0), 0, Utils.ModelManager.ModelKeys.Length - 1);
            bool savedCpu = SettingsStore.GetInt("SelectedBackendIndex", 0) == 1;
            SelectedBackendIndex = Runtime.NativeLibrarySelector.SupportsDirectMl ? (savedCpu ? 1 : 0) : 0;
            DirectMlCapacity = Net472Compat.Clamp(SettingsStore.GetInt("Capacity.DirectML", 4), 1, 16);
            CpuCapacity = Net472Compat.Clamp(SettingsStore.GetInt("Capacity.Cpu", 1), 1, 16);

            var watched = SettingsStore.GetStringList("WatchedClasses");
            WatchPerson     = watched.Contains("person");
            WatchBicycle    = watched.Contains("bicycle");
            WatchCar        = watched.Contains("car");
            WatchMotorcycle = watched.Contains("motorcycle");
            WatchBus        = watched.Contains("bus");
            WatchTruck      = watched.Contains("truck");

            // 兼容旧数据：空集合时默认只选 "person"
            if (watched.Count == 0)
                WatchPerson = true;
        }

        public void Save()
        {
            SettingsStore.Set("ConfidenceThresholdPct", Threshold);
            SettingsStore.Set("TargetFps", SamplingRate);
            SettingsStore.Set("AlertCooldownSeconds", Cooldown);
            SettingsStore.Set("SelectedModelIndex", SelectedModelIndex);
            SettingsStore.Set("SelectedBackendIndex", SelectedBackendIndex);
            SettingsStore.Set("Capacity.DirectML", DirectMlCapacity);
            SettingsStore.Set("Capacity.Cpu", CpuCapacity);

            var watched = GetWatchedClasses();
            SettingsStore.Set("WatchedClasses", string.Join(",", watched));

            SettingsStore.Save();
        }

        public SettingsViewModel()
        {
            PropertyChanged += (s, e) =>
            {
                if (e.PropertyName == nameof(Threshold)) OnPropertyChanged(nameof(ThresholdText));
                if (e.PropertyName == nameof(SamplingRate)) OnPropertyChanged(nameof(SamplingRateText));
                if (e.PropertyName == nameof(Cooldown)) OnPropertyChanged(nameof(CooldownText));
            };

            DownloadModelCommand = new RelayCommand(async () =>
            {
                var key = SelectedModelName;
                if (Utils.ModelManager.IsDownloaded(key))
                {
                    ModelDownloadProgress = "已下载";
                    return;
                }

                ModelDownloadProgress = "下载中 0%...";
                var progress = new Progress<int>(p =>
                {
                    System.Windows.Application.Current.Dispatcher.Invoke(() =>
                        ModelDownloadProgress = $"下载中 {p}%...");
                });

                var ok = await Utils.ModelManager.DownloadModel(key, progress);
                ModelDownloadProgress = ok ? "下载完成" : "下载失败，点击重试";
                OnPropertyChanged(nameof(ModelStatusText));
            });
        }
    }
}
