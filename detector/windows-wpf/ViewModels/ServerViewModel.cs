using System;
using System.Windows;
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

        public string VersionText => $"当前版本 {AppConfig.Version}";

        private string _updateStatusText = "";
        public string UpdateStatusText
        {
            get => _updateStatusText;
            set => SetProperty(ref _updateStatusText, value);
        }

        private bool _isCheckingUpdate;
        public bool IsCheckingUpdate
        {
            get => _isCheckingUpdate;
            set
            {
                if (SetProperty(ref _isCheckingUpdate, value))
                {
                    OnPropertyChanged(nameof(IsUpdateButtonEnabled));
                    OnPropertyChanged(nameof(UpdateButtonText));
                }
            }
        }

        public bool IsUpdateButtonEnabled => !_isCheckingUpdate;

        public string UpdateButtonText => _isCheckingUpdate ? "检查中…" : "检查更新";

        public RelayCommand RetryCommand { get; }
        public RelayCommand ApplyNameCommand { get; }
        public RelayCommand CheckUpdateCommand { get; }

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

            CheckUpdateCommand = new RelayCommand(async () =>
            {
                if (_isCheckingUpdate) return;
                IsCheckingUpdate = true;
                UpdateStatusText = "正在检查更新…";

                try
                {
                    await AutoUpdater.CheckUpdateAsync();
                }
                finally
                {
                    UpdateStatusText = "";
                    IsCheckingUpdate = false;
                }
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
