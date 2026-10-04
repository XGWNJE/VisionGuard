using System;
using System.Collections.ObjectModel;
using System.Threading.Tasks;
using VisionGuard.Detector.Windows.Utils;
using VisionGuard.Detector.Windows.Runtime;

namespace VisionGuard.Detector.Windows.ViewModels
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
        public string StatusText => IsDownloaded ? "已下载" : IsDownloading ? $"下载中 {Progress}%" : "未下载";

        /// <summary>按钮文案：已下载时不可点，下载中显示进度，未下载时提示下载。</summary>
        public string ActionText => IsDownloaded ? "已就绪" : IsDownloading ? Progress + "%" : "下载";

        /// <summary>只有未下载且不在下载中的模型可以触发下载。</summary>
        public bool CanDownload => !IsDownloaded && !IsDownloading;

        /// <summary>行内下载按钮绑定的命令。</summary>
        public RelayCommand DownloadCommand { get; internal set; }
    }

    public class SettingsViewModel : ViewModelBase
    {
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

        // ── 持久化 ───────────────────────────────────────────────────

        public void Load()
        {
            bool savedCpu = SettingsStore.GetInt("SelectedBackendIndex", 0) == 1;
            SelectedBackendIndex = Runtime.NativeLibrarySelector.SupportsDirectMl ? (savedCpu ? 1 : 0) : 0;
        }

        public void Save()
        {
            SettingsStore.Set("SelectedBackendIndex", SelectedBackendIndex);

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
        /// 某个模型下载完成后刷新来源页的可用模型集合。
        /// </param>
        public SettingsViewModel(
            Func<string, bool> isModelDownloaded,
            Func<string, IProgress<int>, Task<bool>> downloadModel,
            Action onModelDownloaded)
        {
            _isModelDownloaded = isModelDownloaded ?? Utils.ModelManager.IsDownloaded;
            _downloadModel = downloadModel ?? ((key, progress) => Utils.ModelManager.DownloadModel(key, progress));
            _onModelDownloaded = onModelDownloaded;

            BuildModelList();
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
                // 进度只更新本次下载的模型项。
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
                // 可用模型集合变化后刷新来源页的模型下拉。
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
