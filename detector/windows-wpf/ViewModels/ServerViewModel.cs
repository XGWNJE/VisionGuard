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

        // ── 驻留程序状态 ─────────────────────────────────────────────
        // 驻留是独立进程，检测端无法从自身推断它是否活着；Win7 实测过“驻留没起来但界面毫无提示”。
        // 这里只表达状态：运行中一律不显示技术细节；未运行时把失败原因作为状态本身显示出来，
        // 不额外附加角色说明或路径文案。

        private string _residentStatusText = "检测中…";
        public string ResidentStatusText
        {
            get => _residentStatusText;
            private set => SetProperty(ref _residentStatusText, value);
        }

        public RelayCommand RefreshResidentCommand { get; }

        /// <summary>刷新驻留状态。仅在未运行时尝试拉起，运行中只做只读刷新。</summary>
        public void RefreshResidentStatus(bool allowLaunch)
        {
            var status = Runtime.ResidentLauncher.RefreshStatus();
            ResidentStatusText = status.Describe();
            if (allowLaunch && !status.IsRunning) Runtime.ResidentLauncher.EnsureStarted();
        }

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

            RefreshResidentCommand = new RelayCommand(() =>
            {
                // 手动刷新时允许重试拉起：用户在这里按按钮，说明他知道驻留应该起来。
                RefreshResidentStatus(allowLaunch: true);
                RefreshResidentStatus(allowLaunch: false);
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
