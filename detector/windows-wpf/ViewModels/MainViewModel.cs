using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Windows.Media.Imaging;
using VisionGuard.Models;
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

        // ── 预览区 ──────────────────────────────────────────────────
        private BitmapSource? _previewImage;
        public BitmapSource? PreviewImage
        {
            get => _previewImage;
            set => SetProperty(ref _previewImage, value);
        }

        private double _frameWidth;
        public double FrameWidth
        {
            get => _frameWidth;
            set => SetProperty(ref _frameWidth, value);
        }

        private double _frameHeight;
        public double FrameHeight
        {
            get => _frameHeight;
            set => SetProperty(ref _frameHeight, value);
        }

        public ObservableCollection<DetectionItem> Detections { get; } = new();

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

        private readonly ServerPushService _serverPushService;
        private readonly System.Windows.Threading.DispatcherTimer _heartbeatTimer;

        public MainViewModel()
        {
            // 加载持久化设置
            SettingsStore.Load();

            // 创建共享服务
            var alertService = new AlertService();
            _serverPushService = new ServerPushService();

            // 报警事件 → 推送服务器 + 更新状态栏
            alertService.AlertTriggered += (s, e) =>
            {
                // 推送报警元数据到服务器（WebSocket），按需截图由服务端 request-screenshot 拉取
                _serverPushService.PushAlert(e);

                System.Windows.Application.Current.Dispatcher.BeginInvoke(() =>
                {
                    string target = e.Detections.FirstOrDefault()?.Label ?? "目标";
                    LastAlertText = $"最后报警：{target} ({e.Detections.Count} 个)";
                });
            };

            // 子 ViewModel（注入共享服务 + MainViewModel 自身用于预览回调）
            SettingsVm = new SettingsViewModel();
            MonitorVm = new MonitorViewModel(alertService, _serverPushService, SettingsVm, this);
            ServerVm = new ServerViewModel(_serverPushService);

            // ── 远控命令路由 ──────────────────────────────────────────
            _serverPushService.CommandReceived += (s, cmd) =>
            {
                System.Windows.Application.Current.Dispatcher.BeginInvoke(() =>
                {
                    switch (cmd)
                    {
                        case "pause":
                            MonitorVm.StopMonitor(remote: true);
                            break;
                        case "resume":
                            MonitorVm.StartMonitor(remote: true);
                            break;
                        case "stop-alarm":
                            _serverPushService.SendCommandAck(cmd, false, "当前无报警");
                            break;
                    }
                    RefreshHeartbeat(_serverPushService);
                });
            };

            _serverPushService.SetConfigReceived += (s, kv) =>
            {
                System.Windows.Application.Current.Dispatcher.BeginInvoke(() =>
                {
                    MonitorVm.ApplyRemoteConfig(kv.Key, kv.Value);
                    RefreshHeartbeat(_serverPushService);
                });
            };

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
            _serverPushService.Configure(
                AppConfig.ServerUrl,
                AppConfig.ApiKey,
                AppConfig.DeviceId,
                ServerVm.DeviceName);

            NavigateToMonitorCommand = new RelayCommand(() => CurrentPage = PageType.Monitor);
            NavigateToSettingsCommand = new RelayCommand(() => CurrentPage = PageType.Settings);
            NavigateToServerCommand = new RelayCommand(() => CurrentPage = PageType.Server);

            // 心跳参数定时刷新(3s,与 WinForms 对齐): 确保 isMonitoring/cooldown/confidence/targets 实时同步到接收端
            _heartbeatTimer = new System.Windows.Threading.DispatcherTimer
            {
                Interval = System.TimeSpan.FromSeconds(3)
            };
            _heartbeatTimer.Tick += (s, e) => RefreshHeartbeat(_serverPushService);
            _heartbeatTimer.Start();
        }

        /// <summary>由 MonitorViewModel 在 UI 线程调用，更新预览画面与检测框。</summary>
        public void UpdatePreview(BitmapSource image, List<Detection> detections)
        {
            PreviewImage = image;
            FrameWidth = image.PixelWidth;
            FrameHeight = image.PixelHeight;

            Detections.Clear();
            foreach (var d in detections)
            {
                Detections.Add(new DetectionItem
                {
                    Left   = d.BoundingBox.Left,
                    Top    = d.BoundingBox.Top,
                    Width  = d.BoundingBox.Width,
                    Height = d.BoundingBox.Height,
                    Label  = $"{d.Label} {(int)(d.Confidence * 100)}%"
                });
            }
        }

        /// <summary>停止监控后清空预览。</summary>
        public void ClearPreview()
        {
            PreviewImage = null;
            Detections.Clear();
            FrameWidth = 0;
            FrameHeight = 0;
        }

        /// <summary>程序退出前清理资源。</summary>
        public void Shutdown()
        {
            // 停止心跳定时器
            _heartbeatTimer?.Stop();

            // 强制保存一次当前设置
            SettingsVm.Save();
            MonitorVm.Save();
            ServerVm.Save();

            // 停止监控（会释放 ONNX 引擎）
            if (MonitorVm.IsMonitoring)
                MonitorVm.StopMonitor();

            // 释放监控服务
            MonitorVm.Dispose();
            // 释放 WebSocket 连接与事件循环线程
            _serverPushService.Dispose();
        }

        /// <summary>远控命令/配置变更后刷新心跳参数并立即推送。</summary>
        private void RefreshHeartbeat(ServerPushService sps)
        {
            var targets = SettingsVm.GetWatchedClasses();
            sps.UpdateHeartbeatParams(
                isMonitoring: MonitorVm.IsMonitoring,
                isReady: MonitorVm.HasCaptureTarget, // 选区已设定即就绪，与 WinForms 对齐
                cooldown: SettingsVm.Cooldown,
                confidence: SettingsVm.Threshold / 100f,
                targets: string.Join(",", targets),
                targetSamplingRate: SettingsVm.SamplingRate,
                modelKey: SettingsVm.SelectedModelName,
                modelOptions: Utils.ModelManager.ModelKeys,
                canSwitchModelWhileMonitoring: false);
            sps.SendHeartbeatNow();
        }
    }
}
