using VisionGuard.Services;
using VisionGuard.Utils;

namespace VisionGuard.ViewModels
{
    public enum PageType { Monitor, Settings, Server }

    public class MainViewModel : ViewModelBase
    {
        private PageType _currentPage = PageType.Monitor;
        public PageType CurrentPage
        {
            get => _currentPage;
            set
            {
                if (SetProperty(ref _currentPage, value))
                {
                    OnPropertyChanged(nameof(IsMonitorPage));
                    OnPropertyChanged(nameof(IsSettingsPage));
                    OnPropertyChanged(nameof(IsServerPage));
                }
            }
        }

        public bool IsMonitorPage => CurrentPage == PageType.Monitor;
        public bool IsSettingsPage => CurrentPage == PageType.Settings;
        public bool IsServerPage => CurrentPage == PageType.Server;

        // 状态栏
        private string _statusText = "○ 已停止";
        public string StatusText
        {
            get => _statusText;
            set => SetProperty(ref _statusText, value);
        }

        private string _lastAlertText = "最后报警：—";
        public string LastAlertText
        {
            get => _lastAlertText;
            set => SetProperty(ref _lastAlertText, value);
        }

        private string _inferMsText = "推理 — ms";
        public string InferMsText
        {
            get => _inferMsText;
            set => SetProperty(ref _inferMsText, value);
        }

        // 导航命令
        public RelayCommand NavigateToMonitorCommand { get; }
        public RelayCommand NavigateToSettingsCommand { get; }
        public RelayCommand NavigateToServerCommand { get; }

        // 子 ViewModel（共享服务）
        public MonitorViewModel MonitorVm { get; }
        public SettingsViewModel SettingsVm { get; }
        public ServerViewModel ServerVm { get; }

        public MainViewModel()
        {
            // 加载持久化设置
            SettingsStore.Load();

            // 创建共享服务
            var alertService = new AlertService();
            var serverPushService = new ServerPushService();

            // 子 ViewModel（注入共享服务）
            SettingsVm = new SettingsViewModel();
            MonitorVm = new MonitorViewModel(alertService, serverPushService, SettingsVm);
            ServerVm = new ServerViewModel(serverPushService);

            // 从磁盘恢复设置
            SettingsVm.Load();
            MonitorVm.Load();
            ServerVm.Load();

            // 属性变更自动保存（防抖 500ms，避免 Slider 拖动频繁写盘）
            var saveTimer = new System.Windows.Threading.DispatcherTimer
            {
                Interval = System.TimeSpan.FromMilliseconds(500)
            };
            saveTimer.Tick += (s, e) =>
            {
                saveTimer.Stop();
                SettingsVm.Save();
                MonitorVm.Save();
                ServerVm.Save();
            };

            void QueueSave()
            {
                saveTimer.Stop();
                saveTimer.Start();
            }

            SettingsVm.PropertyChanged += (s, e) => QueueSave();
            MonitorVm.PropertyChanged += (s, e) => QueueSave();
            ServerVm.PropertyChanged += (s, e) => QueueSave();

            // 初始配置服务器连接
            serverPushService.Configure(
                AppConfig.ServerUrl,
                AppConfig.ApiKey,
                AppConfig.DeviceId,
                ServerVm.DeviceName);

            NavigateToMonitorCommand = new RelayCommand(() => CurrentPage = PageType.Monitor);
            NavigateToSettingsCommand = new RelayCommand(() => CurrentPage = PageType.Settings);
            NavigateToServerCommand = new RelayCommand(() => CurrentPage = PageType.Server);
        }
    }
}
