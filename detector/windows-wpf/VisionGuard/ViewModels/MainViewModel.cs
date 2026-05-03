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

        public MonitorViewModel MonitorVm { get; } = new MonitorViewModel();
        public SettingsViewModel SettingsVm { get; } = new SettingsViewModel();
        public ServerViewModel ServerVm { get; } = new ServerViewModel();

        public MainViewModel()
        {
            NavigateToMonitorCommand = new RelayCommand(() => CurrentPage = PageType.Monitor);
            NavigateToSettingsCommand = new RelayCommand(() => CurrentPage = PageType.Settings);
            NavigateToServerCommand = new RelayCommand(() => CurrentPage = PageType.Server);
        }
    }
}
