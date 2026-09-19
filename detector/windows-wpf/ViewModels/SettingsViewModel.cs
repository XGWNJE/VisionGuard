using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;
using VisionGuard.Utils;
using VisionGuard.Runtime;

namespace VisionGuard.ViewModels
{
    /// <summary>
    /// 模型资源清单里的一项：本档位的一个可下载模型及其本机状态。
    /// 下载进度与状态都由这一项自己承载，界面直接按项渲染，避免“先选下拉、再看状态、再点下载”的三段式。
    /// </summary>
    public sealed class ModelOption : ViewModelBase
    {
        /// <summary>模型键，与 ModelManager.ModelKeys、服务端 /models/<key>.onnx 同名。</summary>
        public string Key { get; }

        /// <summary>展示名，含体积提示。</summary>
        public string DisplayName { get; }

        public ModelOption(string key, string displayName, bool downloaded)
        {
            Key = key;
            DisplayName = displayName;
            _isDownloaded = downloaded;
        }

        private bool _isDownloaded;
        public bool IsDownloaded
        {
            get => _isDownloaded;
            internal set
            {
                if (!SetProperty(ref _isDownloaded, value)) return;
                OnPropertyChanged(nameof(StatusText));
                OnPropertyChanged(nameof(ActionText));
                OnPropertyChanged(nameof(CanDownload));
            }
        }

        private bool _isDownloading;
        public bool IsDownloading
        {
            get => _isDownloading;
            internal set
            {
                if (!SetProperty(ref _isDownloading, value)) return;
                OnPropertyChanged(nameof(StatusText));
                OnPropertyChanged(nameof(ActionText));
                OnPropertyChanged(nameof(CanDownload));
            }
        }

        private int _progress;
        public int Progress
        {
            get => _progress;
            internal set => SetProperty(ref _progress, value);
        }

        /// <summary>右侧状态文案：已下载 / 下载中 42% / 未下载。</summary>
        public string StatusText => IsDownloaded ? "✓ 已下载" : IsDownloading ? $"下载中 {Progress}%" : "未下载";

        /// <summary>按钮文案：已下载时不可点，下载中显示进度，未下载时提示下载。</summary>
        public string ActionText => IsDownloaded ? "已就绪" : IsDownloading ? Progress + "%" : "下载";

        /// <summary>只有未下载且不在下载中的模型可以触发下载。</summary>
        public bool CanDownload => !IsDownloaded && !IsDownloading;

        /// <summary>行内下载按钮绑定的命令。</summary>
        public RelayCommand DownloadCommand { get; internal set; }
    }

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
                {
                    OnPropertyChanged(nameof(SelectedModelName));
                    OnPropertyChanged(nameof(HasUsableModel));
                }
            }
        }

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

        // ── 模型资源清单 ─────────────────────────────────────────────

        private readonly Func<string, bool> _isModelDownloaded;
        private readonly Func<string, IProgress<int>, Task<bool>> _downloadModel;
        private readonly Action _onModelDownloaded;
        /// <summary>本档位可下载的模型清单；每项自带本机状态与下载动作。</summary>
        public ObservableCollection<ModelOption> Models { get; } = new ObservableCollection<ModelOption>();

        private string _modelDownloadProgress = "";
        /// <summary>最近一次触发的下载结果提示；下载中/已完成的状态由各模型项自己表达。</summary>
        public string ModelDownloadProgress
        {
            get => _modelDownloadProgress;
            set
            {
                if (!SetProperty(ref _modelDownloadProgress, value)) return;
                OnPropertyChanged(nameof(HasModelNotice));
            }
        }

        public bool HasModelNotice => !string.IsNullOrEmpty(_modelDownloadProgress);

        /// <summary>本机是否已有本档位的可用模型；没有时来源页的模型下拉必须不可用。</summary>
        public bool HasUsableModel
        {
            get
            {
                var keys = ModelManager.ModelKeys;
                for (int i = 0; i < keys.Length; i++)
                    if (_isModelDownloaded(keys[i])) return true;
                return false;
            }
        }

        /// <summary>
        /// 把选中索引收敛到「本机确实存在」的模型上：优先保留原本的选择，
        /// 否则落到第一个已下载模型；一个都没下载时保持 0（此时界面会提示去全局设定下载）。
        /// </summary>
        internal void EnsureSelectedModelAvailable()
        {
            var keys = ModelManager.ModelKeys;
            int saved = Net472Compat.Clamp(SelectedModelIndex, 0, keys.Length - 1);
            int firstDownloaded = -1;
            for (int i = 0; i < keys.Length; i++)
            {
                if (!_isModelDownloaded(keys[i])) continue;
                if (firstDownloaded < 0) firstDownloaded = i;
                if (i == saved) return;
            }
            if (firstDownloaded >= 0) SelectedModelIndex = firstDownloaded;
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

            // 选中项必须落在本机已有的模型上：否则来源页会拿着一个没下载的模型去启动。
            EnsureSelectedModelAvailable();
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

        public SettingsViewModel() : this(null, null, null) { }

        /// <param name="isModelDownloaded">
        /// 本机模型存在性判定；为 null 时使用 <see cref="Utils.ModelManager.IsDownloaded"/>。
        /// 抽成委托只为一件事：让「模型清单只反映本机已下载模型」这条规则可以被测试直接调用。
        /// </param>
        /// <param name="downloadModel">
        /// 实际下载动作；为 null 时使用 <see cref="Utils.ModelManager.DownloadModel"/>。
        /// 抽成委托是为了让「界面上那个下载按钮」这条路径可被独立驱动与断言，
        /// 而不必每次都真的访问服务器。
        /// </param>
        /// <param name="onModelDownloaded">
        /// 某个模型下载完成后的回调：本机可用模型集合发生变化，需要重新评估当前选中项与来源页下拉。
        /// </param>
        public SettingsViewModel(
            Func<string, bool> isModelDownloaded,
            Func<string, IProgress<int>, Task<bool>> downloadModel,
            Action onModelDownloaded)
        {
            _isModelDownloaded = isModelDownloaded ?? Utils.ModelManager.IsDownloaded;
            _downloadModel = downloadModel ?? ((key, progress) => Utils.ModelManager.DownloadModel(key, progress));
            _onModelDownloaded = onModelDownloaded;

            PropertyChanged += (s, e) =>
            {
                if (e.PropertyName == nameof(Threshold)) OnPropertyChanged(nameof(ThresholdText));
                if (e.PropertyName == nameof(SamplingRate)) OnPropertyChanged(nameof(SamplingRateText));
                if (e.PropertyName == nameof(Cooldown)) OnPropertyChanged(nameof(CooldownText));
            };

            BuildModelList();

            DownloadModelCommand = new RelayCommand(async () => await DownloadAsync(Models.FirstOrDefault(m => m.Key == SelectedModelName)));
        }

        /// <summary>按本档位模型键构建清单；本机状态在构建时采一次，后续由下载动作自己维护。</summary>
        private void BuildModelList()
        {
            Models.Clear();
            var keys = Utils.ModelManager.ModelKeys;
            var names = Utils.ModelManager.ModelDisplayNames;
            for (int i = 0; i < keys.Length; i++)
            {
                var option = new ModelOption(keys[i], i < names.Length ? names[i] : keys[i], _isModelDownloaded(keys[i]));
                option.DownloadCommand = new RelayCommand(async () => await DownloadAsync(option), () => option.CanDownload);
                Models.Add(option);
            }
            OnPropertyChanged(nameof(HasUsableModel));
        }

        /// <summary>本机已下载模型的键列表，供来源页的模型下拉使用。</summary>
        public string[] DownloadedModelKeys()
        {
            var downloaded = new List<string>();
            foreach (var option in Models.Where(m => m.IsDownloaded)) downloaded.Add(option.Key);
            return downloaded.ToArray();
        }

        /// <summary>下载单个模型；状态与进度都写在对应模型项上。</summary>
        private async Task DownloadAsync(ModelOption option)
        {
            if (option == null || option.IsDownloading || option.IsDownloaded) return;

            option.IsDownloading = true;
            option.Progress = 0;
            ModelDownloadProgress = "";

            var progress = new Progress<int>(p =>
            {
                // 进度回调是异步的：模型切换或重复触发时不能把进度写到别的项上。
                if (option.IsDownloading) option.Progress = p;
            });

            bool ok;
            try
            {
                ok = await _downloadModel(option.Key, progress);
            }
            catch (Exception ex)
            {
                ok = false;
                ModelDownloadProgress = $"{option.Key} 下载失败：{ex.Message}";
            }

            option.IsDownloading = false;
            option.IsDownloaded = ok;
            option.Progress = ok ? 100 : 0;
            if (ok)
            {
                ModelDownloadProgress = $"{option.Key} 已下载完成，可在此页或来源页选用";
                // 可用模型集合变了：重新收敛选中项，并让来源页的模型下拉重新求值。
                EnsureSelectedModelAvailable();
                OnPropertyChanged(nameof(HasUsableModel));
                _onModelDownloaded?.Invoke();
            }
            else if (string.IsNullOrEmpty(ModelDownloadProgress))
            {
                // 带上具体原因：用户在别的机器上失败时，界面本身就是证据，不必再去翻日志。
                string reason = Utils.ModelManager.LastFailureReason;
                ModelDownloadProgress = string.IsNullOrEmpty(reason)
                    ? $"{option.Key} 下载失败，请检查网络后重试"
                    : $"{option.Key} 下载失败：{reason}";
            }
        }
    }
}
