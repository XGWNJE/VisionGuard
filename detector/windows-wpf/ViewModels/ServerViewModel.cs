using System;
using System.Threading.Tasks;
using System.Windows;
using VisionGuard.Detector.Windows.Services;
using VisionGuard.Detector.Windows.Utils;

namespace VisionGuard.Detector.Windows.ViewModels
{
    public class ServerViewModel : ViewModelBase
    {
        private readonly ServerPushService _serverPushService;
        public event EventHandler AccountChanging;
        public event EventHandler AccountChanged;
        private string _serviceAddress = AccountSession.ServiceUrl, _username = "", _password = "", _loginMessage = "";
        private bool _isChangingAccount, _isRefreshing, _isLoginError;
        public bool CanEditAccount => !_isChangingAccount;
        public bool AllowsTestEndpoint => AccountSession.AllowsTestEndpoint;
        public bool CanEditDeviceName => AccountSession.Current != null && !_isChangingAccount;
        public string ServiceAddress { get => _serviceAddress; set => SetProperty(ref _serviceAddress, value); }
        public string Username { get => _username; set => SetProperty(ref _username, value); }
        public string Password { get => _password; set => SetProperty(ref _password, value); }
        public string LoginMessage { get => _loginMessage; private set => SetProperty(ref _loginMessage, value); }
        public bool IsLoginError { get => _isLoginError; private set => SetProperty(ref _isLoginError, value); }
        private void SetLoginError(string message) { LoginMessage = message; IsLoginError = true; }
        public string AccountText => AccountSession.Current == null ? "未登录" : "已登录 · " + AccountSession.Current.account.username;
        public RelayCommand LoginCommand { get; }
        public RelayCommand LogoutCommand { get; }

        private string _connectionState = "未连接";
        private bool _isConnected, _isConnecting, _isResidentRunning;
        public bool IsConnected { get => _isConnected; private set => SetProperty(ref _isConnected, value); }
        public bool IsConnecting { get => _isConnecting; private set => SetProperty(ref _isConnecting, value); }
        public bool IsResidentRunning { get => _isResidentRunning; private set => SetProperty(ref _isResidentRunning, value); }
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

        private bool _isCompletingExit;
        public bool IsCompletingExit
        {
            get => _isCompletingExit;
            private set
            {
                if (SetProperty(ref _isCompletingExit, value))
                    FullExitCommand.RaiseCanExecuteChanged();
            }
        }

        public RelayCommand FullExitCommand { get; }

        /// <summary>刷新驻留状态。仅在未运行时尝试拉起，运行中只做只读刷新。</summary>
        public void RefreshResidentStatus(bool allowLaunch)
        {
            var status = Runtime.ResidentLauncher.RefreshStatus();
            IsResidentRunning = status.IsRunning;
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
            ServiceAddress = AccountSession.ServiceUrl;
            Username = AccountSession.Current?.account.username ?? "";
            if (AccountSession.Current != null) DeviceName = AccountSession.Current.device.deviceName;
            OnPropertyChanged(nameof(AccountText));
            OnPropertyChanged(nameof(CanEditDeviceName));
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
            _serverPushService.DeviceUpdated += (_, device) =>
            {
                Application.Current?.Dispatcher.BeginInvoke(new Action(() =>
                {
                    if (AccountSession.Current?.device.deviceId != device.deviceId) return;
                    DeviceName = device.deviceName; Save();
                }));
            };
            LoginCommand = new RelayCommand(async () =>
            {
                if (_isChangingAccount) return;
                _isChangingAccount = true; OnPropertyChanged(nameof(CanEditAccount)); OnPropertyChanged(nameof(CanEditDeviceName)); IsLoginError = false; LoginMessage = "正在登录…";
                try
                {
                    AccountChanging?.Invoke(this, EventArgs.Empty);
                    await Task.Run(() => AccountSession.Login(ServiceAddress, Username, Password, Environment.MachineName));
                    Password = ""; AccountChanged?.Invoke(this, EventArgs.Empty); LoginMessage = "登录成功";
                }
                catch (Exception ex) { SetLoginError(ex.Message); }
                finally { _isChangingAccount = false; OnPropertyChanged(nameof(CanEditAccount)); OnPropertyChanged(nameof(CanEditDeviceName)); OnPropertyChanged(nameof(AccountText)); }
            });
            LogoutCommand = new RelayCommand(async () =>
            {
                if (_isChangingAccount) return;
                _isChangingAccount = true; OnPropertyChanged(nameof(CanEditAccount)); OnPropertyChanged(nameof(CanEditDeviceName)); IsLoginError = false; LoginMessage = "正在退出登录…";
                try
                {
                    AccountChanging?.Invoke(this, EventArgs.Empty); _serverPushService.Disconnect();
                    await Task.Run(AccountSession.Logout); LoginMessage = "已退出登录";
                }
                catch (Exception ex) { SetLoginError("本机已退出；服务撤销未确认：" + ex.Message); }
                finally { _isChangingAccount = false; OnPropertyChanged(nameof(CanEditAccount)); OnPropertyChanged(nameof(CanEditDeviceName)); AccountChanged?.Invoke(this, EventArgs.Empty); Password = ""; OnPropertyChanged(nameof(AccountText)); }
            });

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

            FullExitCommand = new RelayCommand(async () =>
            {
                if (IsCompletingExit) return;
                IsCompletingExit = true;
                try
                {
                    var result = await Task.Run(() => Runtime.ResidentLauncher.StopForCompleteExit());
                    if (!result.Succeeded)
                    {
                        VisionGuard.Detector.Windows.Views.ThemedMessageBox.Show("主体仍在运行，未执行完整退出。\n" + result.FailureReason,
                            "视觉节点错误", MessageBoxButton.OK, MessageBoxImage.Warning);
                        return;
                    }

                    Application.Current?.Shutdown();
                }
                catch (Exception ex)
                {
                    VisionGuard.Detector.Windows.Views.ThemedMessageBox.Show("主体仍在运行，未执行完整退出。\n" + ex.Message,
                        "视觉节点错误", MessageBoxButton.OK, MessageBoxImage.Warning);
                }
                finally
                {
                    if (Application.Current != null
                        && !Application.Current.Dispatcher.HasShutdownStarted
                        && !Application.Current.Dispatcher.HasShutdownFinished)
                        IsCompletingExit = false;
                }
            }, () => !IsCompletingExit);

            ApplyNameCommand = new RelayCommand(async () =>
            {
                try
                {
                await Task.Run(() => AccountSession.RenameDevice(DeviceName));
                // 先持久化，再刷新驻留配置；主检测端和驻留始终使用同一个名称。
                Save();
                _serverPushService.Configure(
                    AppConfig.ServerUrl,
                    AppConfig.SessionToken,
                    AppConfig.DeviceId,
                    DeviceName);
                await Task.Run(() => Runtime.ResidentLauncher.EnsureStarted());
                RefreshResidentStatus(allowLaunch: false);
                }
                catch (Exception ex) { SetLoginError(ex.Message); }
            });

            CheckUpdateCommand = new RelayCommand(async () =>
            {
                if (_isCheckingUpdate) return;
                IsCheckingUpdate = true;
                UpdateStatusText = "正在检查更新…";

                try
                {
                    await AutoUpdater.CheckUpdateAsync(userInitiated: true);
                }
                finally
                {
                    UpdateStatusText = "";
                    IsCheckingUpdate = false;
                }
            });

        }
        public async void MaintainSession()
        {
            if (_isRefreshing || _isChangingAccount) return;
            _isRefreshing = true;
            try
            {
                var old = AccountSession.Current;
                var current = await Task.Run(AccountSession.EnsureFresh);
                if (old?.token != current?.token)
                {
                    AccountChanged?.Invoke(this, EventArgs.Empty);
                    if (current == null) SetLoginError("登录已失效，请重新登录。");
                }
            }
            catch (Exception ex) { SetLoginError(ex.Message); }
            finally { _isRefreshing = false; }
        }

        private void OnConnectionStateChanged(object? sender, string state)
        {
            IsConnected = state == "connected";
            IsConnecting = state == "connecting";
            ConnectionState = state switch
            {
                "connected" => "已连接",
                "connecting" => "连接中…",
                _ => "未连接",
            };
        }
    }
}
