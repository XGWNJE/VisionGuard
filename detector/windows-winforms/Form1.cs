// Form1.cs — 核心：字段、构造、生命周期、配置、状态控制
using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Threading.Tasks;
using System.Windows.Forms;
using VisionGuard.Capture;
using VisionGuard.Models;
using VisionGuard.Services;
using VisionGuard.Utils;

namespace VisionGuard
{
    public partial class Form1 : Form
    {
        // Services
        private AlertService   _alertService;
        private MonitorService _monitorService;
        private LogManager     _log;

        // Preview
        private Bitmap          _previewFrame;
        private List<Detection> _previewDetections;
        private readonly object _previewLock = new object();

        // Preview panel
        private Panel   _previewPanel;

        // Tab control
        private TabControl _tabControl;
        private TabPage _tabCapture, _tabSettings, _tabServer;

        // Capture page
        private Label  _lblRegionInfo;
        private Button _btnSelectRegion;
        private Button _btnPickWindow;
        private Button _btnStart, _btnStop;
        private Button _btnEditMasks;
        private Label  _lblMaskInfo;

        // Params page
        private TrackBar _trkThreshold;
        private Label    _lblThreshold;
        private TrackBar _sliderSamplingRate;
        private Label    _lblSamplingRate;
        private TrackBar _sliderCooldown;
        private Label    _lblCooldown;
        private ComboBox _cmbModel;

        // Targets page
        private CheckedListBox   _targetListBox;
        private readonly string[] _targetClassKeys = { "person", "bicycle", "car", "motorcycle", "bus", "truck" };

        // Server page
        private TextBox _txtDeviceName;
        private Label   _lblConnState;
        private Label   _lblConnDetail;
        private Button  _btnRetry;

        // Server constants
        private const string ServerUrl = "https://xgwnje.cn";
        private const string ServerApiKey = "XG-VisionGuard-2024";

        // ServerPushService + heartbeat
        private ServerPushService _serverPushService;
        private System.Windows.Forms.Timer _heartbeatTimer;

        // StatusBar
        private ToolStripStatusLabel _tsStatus, _tsLastAlert, _tsInferMs;

        // System tray
        private NotifyIcon _notifyIcon;

        // Runtime target
        private WindowInfo _targetWindow;
        private Rectangle  _screenRegion;
        private Rectangle  _windowSubRegion;

        // Model
        private string _selectedModel = "yolov5nu";
        private string ModelPath => Path.Combine(
            AppDomain.CurrentDomain.BaseDirectory, "Assets", $"{_selectedModel}.onnx");

        // Mask regions (relative coords [0,1])
        private List<RectangleF> _maskRegions = new List<RectangleF>();

        public Form1()
        {
            InitializeComponent();
            BuildUI();

            _alertService   = new AlertService();
            _monitorService = new MonitorService(_alertService);
            _log            = new LogManager();
            _serverPushService = new ServerPushService();

            _alertService.AlertTriggered   += OnAlertTriggered;
            _monitorService.FrameProcessed += OnFrameProcessed;

            SetupTrayIcon();
        }

        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);
            Text = "VisionGuard";

            BuildCapturePage();
            BuildSettingsPage();
            BuildServerPage();

            WireEvents();
            LoadSettings();
            UpdateControlState(started: false);

            Task.Run(async () =>
            {
                await Utils.NtpSync.SyncAsync();
            });

            _log.Info("VisionGuard 已就绪，请选择捕获区域或目标窗口后点击「开始」。");
        }

        // Config
        private MonitorConfig BuildConfig()
        {
            var watched = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            for (int i = 0; i < _targetClassKeys.Length; i++)
                if (_targetListBox.GetItemChecked(i))
                    watched.Add(_targetClassKeys[i]);

            var cfg = new MonitorConfig
            {
                ConfidenceThreshold  = _trkThreshold.Value / 100f,
                TargetFps            = _sliderSamplingRate.Value,
                AlertCooldownSeconds = _sliderCooldown.Value,
                WatchedClasses       = watched,
                SaveAlertSnapshot    = true,
            };

            if (_targetWindow != null)
            {
                cfg.CaptureMode        = CaptureMode.WindowHandle;
                cfg.TargetWindowTitle  = _targetWindow.Title;
                cfg.TargetWindowHandle = _targetWindow.Handle;
                cfg.WindowSubRegion    = _windowSubRegion;
                cfg.CaptureRegion = _windowSubRegion != Rectangle.Empty
                    ? _windowSubRegion
                    : _targetWindow.Bounds;
            }
            else
            {
                cfg.CaptureMode   = CaptureMode.ScreenRegion;
                cfg.CaptureRegion = _screenRegion;
            }

            cfg.MaskRegions = new List<RectangleF>(_maskRegions);
            return cfg;
        }

        // Control state
        private void UpdateControlState(bool started)
        {
            _btnStart.Enabled         = !started;
            _btnStop.Enabled          =  started;
            _btnSelectRegion.Enabled  = !started;
            _btnPickWindow.Enabled    = !started;
            if (_btnEditMasks != null) _btnEditMasks.Enabled = !started;
            _sliderSamplingRate.Enabled = !started;
            _sliderCooldown.Enabled     = !started;
            _trkThreshold.Enabled       = !started;
            _targetListBox.Enabled = !started;

            _tsStatus.Text      = started ? "监控中" : "已停止";
            _tsStatus.ForeColor = started ? Color.LimeGreen : Color.Gray;
        }

        private void UpdateRegionLabel()
        {
            if (_targetWindow != null)
            {
                string sub = _windowSubRegion != Rectangle.Empty
                    ? $"  子区域 {_windowSubRegion.Width}x{_windowSubRegion.Height}"
                    : "  全窗口";
                _lblRegionInfo.Text = $"[{_targetWindow.Title}]{sub}";
            }
            else
            {
                _lblRegionInfo.Text = _screenRegion == Rectangle.Empty
                    ? "未选择区域"
                    : $"X:{_screenRegion.X}  Y:{_screenRegion.Y}  {_screenRegion.Width}x{_screenRegion.Height}";
            }
        }

        private void UpdateMaskInfoLabel()
        {
            if (_lblMaskInfo == null) return;
            int n = _maskRegions != null ? _maskRegions.Count : 0;
            _lblMaskInfo.Text = n == 0 ? "当前遮罩：-" : $"当前遮罩：{n} 个";
        }

        // Menu switching — handled by TabControl natively

        // Tray
        private void SetupTrayIcon()
        {
            var trayIcon = Icon.ExtractAssociatedIcon(Application.ExecutablePath) ?? SystemIcons.Shield;
            _notifyIcon = new NotifyIcon { Text = "VisionGuard", Icon = trayIcon, Visible = true };
            var menu = new ContextMenu(new[]
            {
                new MenuItem("显示主窗口", (s, ev) => { Show(); WindowState = FormWindowState.Normal; Activate(); }),
                new MenuItem("退出",        (s, ev) => Application.Exit())
            });
            _notifyIcon.ContextMenu = menu;
            _notifyIcon.DoubleClick += (s, ev) => { Show(); WindowState = FormWindowState.Normal; Activate(); };

            Resize += (s, ev) =>
            {
                if (WindowState == FormWindowState.Minimized) Hide();
            };
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            SaveSettings();
            _heartbeatTimer?.Stop();
            _heartbeatTimer?.Dispose();
            _serverPushService?.Dispose();
            _alertService?.StopAlarm();
            _monitorService?.Stop();
            _monitorService?.Dispose();
            _notifyIcon?.Dispose();
            base.OnFormClosing(e);
        }

        // Helpers
        private static string BuildExceptionMessage(Exception ex)
        {
            var sb = new System.Text.StringBuilder();
            Exception cur = ex;
            int depth = 0;
            while (cur != null && depth < 6)
            {
                if (depth > 0) sb.AppendLine("\n--- InnerException ---");
                sb.AppendLine(cur.GetType().Name + ": " + cur.Message);
                cur = cur.InnerException;
                depth++;
            }
            return sb.ToString();
        }

        private bool IsRegionReady
        {
            get
            {
                if (_targetWindow != null) return true;
                return _screenRegion.Width >= 32 && _screenRegion.Height >= 32;
            }
        }

        // Preview update (called from OnFrameProcessed)
        private void UpdatePreview(Bitmap frame, List<Detection> detections)
        {
            lock (_previewLock)
            {
                _previewFrame?.Dispose();
                _previewFrame = frame;
                _previewDetections = detections;
            }
            if (_previewPanel.IsDisposed) return;
            if (_previewPanel.InvokeRequired)
                _previewPanel.BeginInvoke(new Action(() => _previewPanel.Invalidate()));
            else
                _previewPanel.Invalidate();
        }
    }
}
