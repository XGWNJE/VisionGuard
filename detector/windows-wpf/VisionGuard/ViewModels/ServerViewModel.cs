using VisionGuard.Services;
using VisionGuard.Utils;

namespace VisionGuard.ViewModels
{
    public class ServerViewModel : ViewModelBase
    {
        private readonly ServerPushService _serverPushService;

        private string _connectionState = "● 未连接";
        public string ConnectionState
        {
            get => _connectionState;
            set => SetProperty(ref _connectionState, value);
        }

        private string _deviceName = System.Environment.MachineName;
        public string DeviceName
        {
            get => _deviceName;
            set => SetProperty(ref _deviceName, value);
        }

        public RelayCommand RetryCommand { get; }
        public RelayCommand ApplyNameCommand { get; }

        // ── 持久化 ───────────────────────────────────────────────────

        public void Load()
        {
            DeviceName = SettingsStore.GetString("DeviceName", System.Environment.MachineName);
        }

        public void Save()
        {
            SettingsStore.Set("DeviceName", DeviceName);
            SettingsStore.Save();
        }

        public ServerViewModel(ServerPushService serverPushService)
        {
            _serverPushService = serverPushService;

            // 监听连接状态变化
            _serverPushService.ConnectionStateChanged += OnConnectionStateChanged;

            RetryCommand = new RelayCommand(() =>
            {
                _serverPushService.Reconnect();
            });

            ApplyNameCommand = new RelayCommand(() =>
            {
                _serverPushService.Configure(
                    AppConfig.ServerUrl,
                    AppConfig.ApiKey,
                    AppConfig.DeviceId,
                    DeviceName);
            });
        }

        private void OnConnectionStateChanged(object? sender, string state)
        {
            ConnectionState = state switch
            {
                "connected" => "● 已连接",
                "connecting" => "● 连接中…",
                _ => "● 未连接",
            };
        }
    }
}
