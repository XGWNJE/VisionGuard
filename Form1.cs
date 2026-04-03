using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Windows.Forms;
using VisionGuard.Capture;
using VisionGuard.Data;
using VisionGuard.Models;
using VisionGuard.Services;
using VisionGuard.UI;
using VisionGuard.Utils;

namespace VisionGuard
{
    public partial class Form1 : Form
    {
        // ── 服务 ────────────────────────────────────────────────────
        private AlertService   _alertService;
        private MonitorService _monitorService;
        private LogManager     _log;

        // ── 高 DPI ──────────────────────────────────────────────────
        private float _scaleFactor = 1.0f;

        // ── 布局骨架 ─────────────────────────────────────────────────
        private TableLayoutPanel  _mainLayout;
        private SplitContainer    _rightSplit;
        private Panel             _ctrlOuter;   // 左侧可滚动容器（延迟填充卡片）

        // ── 捕获区域 Card ────────────────────────────────────────────
        private Label           _lblRegionInfo;
        private FlatRoundButton _btnSelectRegion;
        private FlatRoundButton _btnPickWindow;

        // ── 参数 Card（TextBox 替换 NumericUpDown）───────────────────
        private TextBox         _txtFps, _txtThreads, _txtCooldown;
        private DarkSlider      _trkThreshold;
        private Label           _lblThreshold;
        private CheckBox        _chkPlaySound;
        private TextBox         _txtSoundPath;
        private FlatRoundButton _btnPickSound;

        // ── 监控对象 Card ────────────────────────────────────────────
        private CocoClassPickerControl _classPicker;

        // ── 预览 / 日志 ──────────────────────────────────────────────
        private DetectionOverlayPanel _overlayPanel;
        private OwnerDrawListBox      _lstLog;

        // ── 控制按钮 ─────────────────────────────────────────────────
        private FlatRoundButton _btnStart, _btnStop;

        // ── 状态栏 ───────────────────────────────────────────────────
        private ToolStripStatusLabel _tsStatus, _tsLastAlert, _tsInferMs;

        // ── 系统托盘 / 键钩 ──────────────────────────────────────────
        private NotifyIcon    _notifyIcon;
        private GlobalKeyHook _keyHook;

        // ── 运行时目标窗口（不持久化 HWND）──────────────────────────
        private WindowInfo    _targetWindow;   // null = 屏幕区域模式
        private Rectangle     _screenRegion;   // ScreenRegion 模式下的坐标
        private Rectangle     _windowSubRegion; // WindowHandle 子区域

        private string ModelPath => Path.Combine(
            AppDomain.CurrentDomain.BaseDirectory, "Assets", "yolov5nu.onnx");

        // ════════════════════════════════════════════════════════════
        // 构造
        // ════════════════════════════════════════════════════════════

        public Form1()
        {
            InitializeComponent();

            // 高 DPI：在任何控件创建前确定缩放系数
            AutoScaleMode       = AutoScaleMode.Dpi;
            AutoScaleDimensions = new SizeF(96F, 96F);

            BuildUI();
            // BuildCards / WireEvents / LoadSettings 推迟到 OnShown，
            // 确保 DPI 字体高度已完全生效，卡片高度计算正确

            _alertService   = new AlertService();
            _monitorService = new MonitorService(_alertService);
            _log            = new LogManager(_lstLog);

            _alertService.AlertTriggered   += OnAlertTriggered;
            _alertService.AlarmStarted     += OnAlarmStarted;
            _alertService.AlarmStopped     += OnAlarmStopped;
            _monitorService.FrameProcessed += OnFrameProcessed;

            SetupTrayIcon();
        }

        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);

            // 此时 DPI 缩放完全生效，Font.Height 已是最终值
            var ver = System.Reflection.Assembly.GetExecutingAssembly().GetName().Version;
            this.Text = $"VisionGuard v{ver.Major}.{ver.Minor}";
            BuildCards(_ctrlOuter);
            WireEvents();
            LoadSettings();
            UpdateControlState(started: false);
            ApplySplitterRatio();

            _log.Info("VisionGuard 已就绪，请选择捕获区域或目标窗口后点击「开始」。");
        }

        // ════════════════════════════════════════════════════════════
        // 高 DPI
        // ════════════════════════════════════════════════════════════

        protected override void OnLoad(EventArgs e)
        {
            base.OnLoad(e);
            _scaleFactor = DeviceDpi / 96.0f;
        }

        protected override void OnDpiChanged(DpiChangedEventArgs e)
        {
            base.OnDpiChanged(e);
            _scaleFactor = e.DeviceDpiNew / 96.0f;
            // 重设分割比例
            ApplySplitterRatio();
            _overlayPanel?.Invalidate();
        }

        // ════════════════════════════════════════════════════════════
        // 按钮事件
        // ════════════════════════════════════════════════════════════

        // ── 选择屏幕区域 ─────────────────────────────────────────────

        private void BtnSelectRegion_Click(object sender, EventArgs e)
        {
            if (_targetWindow != null)
            {
                // WindowHandle 模式：在目标窗口截图上框选子区域
                Bitmap snapshot;
                try
                {
                    snapshot = WindowCapturer.CaptureWindow(_targetWindow.Handle, Rectangle.Empty);
                }
                catch (Exception ex)
                {
                    _log.Error("无法捕获目标窗口截图：" + ex.Message);
                    return;
                }

                using (snapshot)
                using (var selector = new RegionSelectorForm(snapshot))
                {
                    selector.ShowDialog(this);
                    if (selector.SelectedRegion != Rectangle.Empty)
                    {
                        _windowSubRegion = selector.SelectedRegion;
                        _log.Info($"已选择子区域：{_windowSubRegion.Width}×{_windowSubRegion.Height} @ ({_windowSubRegion.X},{_windowSubRegion.Y})");
                    }
                    else
                    {
                        _windowSubRegion = Rectangle.Empty;
                        _log.Info("子区域已清除，将捕获整个窗口。");
                    }
                    UpdateRegionLabel();
                }
            }
            else
            {
                // ScreenRegion 模式：全屏半透明遮罩拖拽
                using (var selector = new RegionSelectorForm())
                {
                    Hide();
                    selector.ShowDialog(this);
                    Show();
                    BringToFront();

                    if (selector.SelectedRegion != Rectangle.Empty)
                    {
                        _screenRegion = selector.SelectedRegion;
                        _log.Info($"已选择区域：({_screenRegion.X}, {_screenRegion.Y})  {_screenRegion.Width}×{_screenRegion.Height}");
                        UpdateRegionLabel();
                    }
                }
            }
        }

        // ── 选择目标窗口 ─────────────────────────────────────────────

        private void BtnPickWindow_Click(object sender, EventArgs e)
        {
            using (var picker = new WindowPickerForm(Handle))
            {
                if (picker.ShowDialog(this) == DialogResult.OK && picker.SelectedWindow != null)
                {
                    _targetWindow    = picker.SelectedWindow;
                    _windowSubRegion = Rectangle.Empty;  // 重置子区域
                    _btnSelectRegion.Text = "选择子区域…";
                    UpdateRegionLabel();
                    _log.Info($"已选择目标窗口：{_targetWindow.Title}  [{_targetWindow.ClassName}]");
                }
            }
        }

        // ── 开始监控 ─────────────────────────────────────────────────

        private void BtnStart_Click(object sender, EventArgs e)
        {
            if (!File.Exists(ModelPath))
            {
                MessageBox.Show(
                    $"找不到模型文件：\n{ModelPath}\n\n请参阅 Assets/ASSETS_README.md。",
                    "模型缺失", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }

            MonitorConfig cfg = BuildConfig();

            // 验证有效捕获源
            if (cfg.CaptureMode == CaptureMode.ScreenRegion)
            {
                if (cfg.CaptureRegion.Width < 32 || cfg.CaptureRegion.Height < 32)
                {
                    MessageBox.Show("捕获区域太小（最小 32×32），请重新选择。",
                        "区域无效", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    return;
                }
            }
            else // WindowHandle
            {
                if (cfg.TargetWindowHandle == IntPtr.Zero)
                {
                    MessageBox.Show("请先点击「选择窗口…」选择目标窗口。",
                        "未选择目标窗口", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    return;
                }
            }

            try
            {
                _monitorService.Start(ModelPath, cfg);
                _keyHook = new GlobalKeyHook();
                _keyHook.KeyDown += OnGlobalKeyDown;
                UpdateControlState(started: true);
                string src = cfg.CaptureMode == CaptureMode.WindowHandle
                    ? $"窗口「{cfg.TargetWindowTitle}」"
                    : $"区域 {cfg.CaptureRegion}";
                _log.Info($"监控已启动 | {src} | {cfg.TargetFps} FPS | 阈值 {cfg.ConfidenceThreshold:P0}");
            }
            catch (Exception ex)
            {
                string fullMsg = BuildExceptionMessage(ex);
                _log.Error("启动失败：" + fullMsg);
                MessageBox.Show(fullMsg, "启动失败", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        // ── 停止监控 ─────────────────────────────────────────────────

        private void BtnStop_Click(object sender, EventArgs e)
        {
            _alertService.StopAlarm();
            _keyHook?.Dispose();
            _keyHook = null;
            _monitorService.Stop();
            UpdateControlState(started: false);
            _log.Info("监控已停止。");
        }

        private void OnGlobalKeyDown(Keys key)
        {
            if (key == Keys.Space && _alertService.IsAlarming)
            {
                _alertService.StopAlarm();
                _log.Info("用户按 Space 键，铃声已停止，推理恢复。");
            }
        }

        // ════════════════════════════════════════════════════════════
        // MonitorService 回调（ThreadPool 线程）
        // ════════════════════════════════════════════════════════════

        private void OnFrameProcessed(object sender, FrameResultEventArgs e)
        {
            if (e.HasError)
            {
                _log.Error(e.Error.Message);
                return;
            }

            _overlayPanel.UpdateFrame(e.Frame, e.Detections);

            string inferText = $"推理 {e.InferenceMs} ms";
            BeginInvoke(new Action(() => _tsInferMs.Text = inferText));
        }

        private void OnAlertTriggered(object sender, AlertEvent e)
        {
            string msg = string.Empty;
            foreach (var d in e.Detections)
                msg += $"[{d.Label} {d.Confidence:P0}] ";

            _log.Warn("报警：" + msg.Trim());

            BeginInvoke(new Action(() =>
                _tsLastAlert.Text = "最后报警：" + e.Timestamp.ToString("HH:mm:ss")));

            e.Snapshot?.Dispose();
        }

        private void OnAlarmStarted(object sender, EventArgs e)
        {
            _monitorService.Pause();
            BeginInvoke(new Action(() =>
            {
                _tsStatus.Text      = "⚠ 报警中 — 按 Space 停止";
                _tsStatus.ForeColor = Color.OrangeRed;
            }));
        }

        private void OnAlarmStopped(object sender, EventArgs e)
        {
            _monitorService.Resume();
            BeginInvoke(new Action(() =>
            {
                _tsStatus.Text      = "● 监控中";
                _tsStatus.ForeColor = Color.LimeGreen;
            }));
        }

        // ════════════════════════════════════════════════════════════
        // 配置构建
        // ════════════════════════════════════════════════════════════

        private MonitorConfig BuildConfig()
        {
            var cfg = new MonitorConfig
            {
                ConfidenceThreshold  = _trkThreshold.Value / 100f,
                TargetFps            = ParseInt(_txtFps.Text,      1,   5,  2),
                IntraOpNumThreads    = ParseInt(_txtThreads.Text,  1,   8,  2),
                AlertCooldownSeconds = ParseInt(_txtCooldown.Text, 1, 300,  5),
                WatchedClasses       = _classPicker.SelectedClasses,
                SaveAlertSnapshot    = true,
                PlayAlertSound       = _chkPlaySound.Checked,
                AlertSoundPath       = _txtSoundPath.Text == "默认系统音" ? string.Empty : _txtSoundPath.Text
            };

            if (_targetWindow != null)
            {
                // WindowHandle 模式
                cfg.CaptureMode          = CaptureMode.WindowHandle;
                cfg.TargetWindowTitle    = _targetWindow.Title;
                cfg.TargetWindowHandle   = _targetWindow.Handle;
                cfg.WindowSubRegion      = _windowSubRegion;

                // CaptureRegion 填入子区域（或整窗口）供显示用
                cfg.CaptureRegion = _windowSubRegion != Rectangle.Empty
                    ? _windowSubRegion
                    : _targetWindow.Bounds;
            }
            else
            {
                // ScreenRegion 模式
                cfg.CaptureMode   = CaptureMode.ScreenRegion;
                cfg.CaptureRegion = _screenRegion;
            }

            return cfg;
        }

        // ════════════════════════════════════════════════════════════
        // 设置持久化
        // ════════════════════════════════════════════════════════════

        private void LoadSettings()
        {
            SettingsStore.Load();

            // 阈值 / 参数
            _trkThreshold.Value = Math.Max(_trkThreshold.Minimum,
                Math.Min(_trkThreshold.Maximum, SettingsStore.GetInt("ConfidenceThresholdPct", 45)));
            _txtFps.Text      = SettingsStore.GetInt("TargetFps",            2).ToString();
            _txtThreads.Text  = SettingsStore.GetInt("IntraOpNumThreads",    2).ToString();
            _txtCooldown.Text = SettingsStore.GetInt("AlertCooldownSeconds", 5).ToString();
            _chkPlaySound.Checked = SettingsStore.GetBool("PlayAlertSound", true);

            string soundPath = SettingsStore.GetString("AlertSoundPath", string.Empty);
            if (!string.IsNullOrEmpty(soundPath) && File.Exists(soundPath))
            {
                _txtSoundPath.Text      = soundPath;
                _txtSoundPath.ForeColor = Color.White;
            }

            // 监控对象（中英文选择器）
            HashSet<string> watched = SettingsStore.GetStringList("WatchedClasses");
            _classPicker.SetSelection(watched);

            // 捕获模式
            string modeStr = SettingsStore.GetString("CaptureMode", CaptureMode.ScreenRegion.ToString());
            if (Enum.TryParse(modeStr, out CaptureMode mode) && mode == CaptureMode.WindowHandle)
            {
                // 恢复目标窗口标题（HWND 不可跨会话）
                string title = SettingsStore.GetString("TargetWindowTitle", string.Empty);
                if (!string.IsNullOrEmpty(title))
                {
                    // 尝试按标题重匹配
                    var windows = WindowEnumerator.GetWindows(Handle);
                    WindowInfo found = null;
                    foreach (var w in windows)
                        if (w.Title.Equals(title, StringComparison.OrdinalIgnoreCase))
                        { found = w; break; }

                    if (found != null)
                    {
                        _targetWindow = found;
                        _btnSelectRegion.Text = "选择子区域…";
                        _log.Info($"已恢复目标窗口：{found.Title}");
                    }
                    else
                    {
                        _log.Warn($"目标窗口「{title}」未找到，已回退到屏幕区域模式。");
                    }
                }

                // 恢复子区域
                string subStr = SettingsStore.GetString("WindowSubRegion", string.Empty);
                if (!string.IsNullOrEmpty(subStr))
                {
                    var parts = subStr.Split(',');
                    if (parts.Length == 4
                        && int.TryParse(parts[0], out int x)
                        && int.TryParse(parts[1], out int y)
                        && int.TryParse(parts[2], out int w)
                        && int.TryParse(parts[3], out int h))
                    {
                        _windowSubRegion = new Rectangle(x, y, w, h);
                    }
                }
            }
            else
            {
                // ScreenRegion：恢复坐标
                string regStr = SettingsStore.GetString("ScreenRegion", string.Empty);
                if (!string.IsNullOrEmpty(regStr))
                {
                    var parts = regStr.Split(',');
                    if (parts.Length == 4
                        && int.TryParse(parts[0], out int x)
                        && int.TryParse(parts[1], out int y)
                        && int.TryParse(parts[2], out int w)
                        && int.TryParse(parts[3], out int h))
                    {
                        _screenRegion = new Rectangle(x, y, w, h);
                    }
                }
            }

            UpdateRegionLabel();

            // 窗口尺寸恢复
            int ww = Math.Max(MinimumSize.Width,  SettingsStore.GetInt("WindowWidth",  Width));
            int wh = Math.Max(MinimumSize.Height, SettingsStore.GetInt("WindowHeight", Height));
            Size = new Size(ww, wh);
            if (SettingsStore.GetBool("WindowMaximized", false))
                WindowState = FormWindowState.Maximized;
        }

        private void SaveSettings()
        {
            SettingsStore.Set("ConfidenceThresholdPct",  _trkThreshold.Value);
            SettingsStore.Set("TargetFps",               ParseInt(_txtFps.Text,      1,  5, 2));
            SettingsStore.Set("IntraOpNumThreads",        ParseInt(_txtThreads.Text,  1,  8, 2));
            SettingsStore.Set("AlertCooldownSeconds",     ParseInt(_txtCooldown.Text, 1, 300, 5));
            SettingsStore.Set("PlayAlertSound",           _chkPlaySound.Checked);
            SettingsStore.Set("AlertSoundPath",
                _txtSoundPath.Text == "默认系统音" ? string.Empty : _txtSoundPath.Text);

            // 监控对象（存英文类名逗号分隔）
            SettingsStore.Set("WatchedClasses",
                string.Join(",", _classPicker.SelectedClasses));

            // 捕获模式
            if (_targetWindow != null)
            {
                SettingsStore.Set("CaptureMode",       CaptureMode.WindowHandle.ToString());
                SettingsStore.Set("TargetWindowTitle",  _targetWindow.Title);
                SettingsStore.Set("WindowSubRegion",
                    _windowSubRegion == Rectangle.Empty
                        ? string.Empty
                        : $"{_windowSubRegion.X},{_windowSubRegion.Y},{_windowSubRegion.Width},{_windowSubRegion.Height}");
            }
            else
            {
                SettingsStore.Set("CaptureMode", CaptureMode.ScreenRegion.ToString());
                SettingsStore.Set("ScreenRegion",
                    $"{_screenRegion.X},{_screenRegion.Y},{_screenRegion.Width},{_screenRegion.Height}");
            }

            // 窗口尺寸
            Size saveSize = WindowState == FormWindowState.Normal ? Size : RestoreBounds.Size;
            SettingsStore.Set("WindowWidth",     saveSize.Width);
            SettingsStore.Set("WindowHeight",    saveSize.Height);
            SettingsStore.Set("WindowMaximized", WindowState == FormWindowState.Maximized);

            SettingsStore.Save();
        }

        // ════════════════════════════════════════════════════════════
        // 控件状态
        // ════════════════════════════════════════════════════════════

        private void UpdateControlState(bool started)
        {
            _btnStart.Enabled        = !started;
            _btnStop.Enabled         =  started;
            _btnSelectRegion.Enabled = !started;
            _btnPickWindow.Enabled   = !started;
            _txtFps.Enabled     = _txtThreads.Enabled = _txtCooldown.Enabled = !started;
            _chkPlaySound.Enabled = !started;
            _txtSoundPath.Enabled = !started && _chkPlaySound.Checked;
            _btnPickSound.Enabled = !started && _chkPlaySound.Checked;
            _trkThreshold.Enabled = !started;
            _classPicker.Enabled  = !started;

            _tsStatus.Text      = started ? "● 监控中" : "○ 已停止";
            _tsStatus.ForeColor = started ? Color.LimeGreen : Color.Gray;
        }

        private void UpdateRegionLabel()
        {
            if (_targetWindow != null)
            {
                string sub = _windowSubRegion != Rectangle.Empty
                    ? $"  子区域 {_windowSubRegion.Width}×{_windowSubRegion.Height}"
                    : "  全窗口";
                _lblRegionInfo.Text = $"[{_targetWindow.Title}]{sub}";
            }
            else
            {
                _lblRegionInfo.Text = _screenRegion == Rectangle.Empty
                    ? "未选择区域"
                    : $"X:{_screenRegion.X}  Y:{_screenRegion.Y}  {_screenRegion.Width}×{_screenRegion.Height}";
            }
        }

        // ════════════════════════════════════════════════════════════
        // 托盘 / 关闭
        // ════════════════════════════════════════════════════════════

        private void SetupTrayIcon()
        {
            var trayIcon = Icon.ExtractAssociatedIcon(Application.ExecutablePath) ?? SystemIcons.Shield;
            _notifyIcon = new NotifyIcon
            {
                Text    = "VisionGuard",
                Icon    = trayIcon,
                Visible = true
            };
            var menu = new ContextMenu(new[]
            {
                new MenuItem("显示主窗口", (s, ev) => { Show(); WindowState = FormWindowState.Normal; }),
                new MenuItem("退出",        (s, ev) => Application.Exit())
            });
            _notifyIcon.ContextMenu = menu;
            _notifyIcon.DoubleClick += (s, ev) => { Show(); WindowState = FormWindowState.Normal; };
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            SaveSettings();
            _alertService?.StopAlarm();
            _keyHook?.Dispose();
            _keyHook = null;
            _monitorService?.Stop();
            _monitorService?.Dispose();
            _notifyIcon?.Dispose();
            base.OnFormClosing(e);
        }

        // ════════════════════════════════════════════════════════════
        // 异常诊断
        // ════════════════════════════════════════════════════════════

        private static string BuildExceptionMessage(Exception ex)
        {
            var sb = new System.Text.StringBuilder();
            Exception cur = ex;
            int depth = 0;
            while (cur != null && depth < 6)
            {
                if (depth > 0) sb.AppendLine("\n─── InnerException ───");
                sb.AppendLine(cur.GetType().Name + ": " + cur.Message);
                cur = cur.InnerException;
                depth++;
            }
            return sb.ToString();
        }

        // ════════════════════════════════════════════════════════════
        // 构建 UI（纯代码，不依赖 Designer）
        // ════════════════════════════════════════════════════════════

        private void BuildUI()
        {
            Text          = "VisionGuard — 人员检测监控";
            MinimumSize   = new Size(900, 600);
            Size          = new Size(1100, 720);
            StartPosition = FormStartPosition.CenterScreen;
            BackColor     = Color.FromArgb(25, 25, 25);
            ForeColor     = Color.LightGray;
            Font = new Font("Segoe UI", 9f, FontStyle.Regular, GraphicsUnit.Point);

            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

            SuspendLayout();

            // ── 状态栏 ───────────────────────────────────────────────
            var strip = new StatusStrip { BackColor = Color.FromArgb(37, 37, 37) };
            strip.Renderer = new DarkStatusRenderer();
            _tsStatus    = new ToolStripStatusLabel("○ 已停止") { ForeColor = Color.Gray };
            _tsLastAlert = new ToolStripStatusLabel("最后报警：—") { Spring = true };
            _tsInferMs   = new ToolStripStatusLabel("推理 — ms") { Alignment = ToolStripItemAlignment.Right };
            strip.Items.AddRange(new ToolStripItem[] { _tsStatus, _tsLastAlert, _tsInferMs });

            // ── 预览面板 ─────────────────────────────────────────────
            _overlayPanel = new DetectionOverlayPanel { Dock = DockStyle.Fill };

            // ── 日志面板 ─────────────────────────────────────────────
            _lstLog = new OwnerDrawListBox { Dock = DockStyle.Fill, ScrollAlwaysVisible = true };
            _lstLog.Font = new Font("Consolas", Font.SizeInPoints, FontStyle.Regular, GraphicsUnit.Point);
            var logContainer = new Panel { Dock = DockStyle.Fill, MinimumSize = new Size(0, 60) };
            logContainer.Controls.Add(_lstLog);

            // ── 右侧分割：上70%预览 / 下30%日志 ─────────────────────
            _rightSplit = new SplitContainer
            {
                Dock          = DockStyle.Fill,
                Orientation   = Orientation.Horizontal,
                Panel1MinSize = 100,
                Panel2MinSize = 60,
                BackColor     = Color.FromArgb(25, 25, 25)
            };
            _rightSplit.Panel1.Controls.Add(_overlayPanel);
            _rightSplit.Panel2.Controls.Add(logContainer);
            Shown  += (s, e) => ApplySplitterRatio();
            Resize += (s, e) => ApplySplitterRatio();

            // ── 左侧控制面板：可滚动容器 ─────────────────────────────
            _ctrlOuter = new Panel
            {
                Dock       = DockStyle.Fill,
                BackColor  = Color.FromArgb(32, 32, 32),
                AutoScroll = true
            };

            // 注意：BuildCards(_ctrlOuter) 推迟到 OnShown 中调用，
            // 确保 DPI 缩放和字体高度已完全生效，卡片高度计算正确。

            // ── 主布局：左1/3 + 右2/3 ───────────────────────────────
            _mainLayout = new TableLayoutPanel
            {
                Dock        = DockStyle.Fill,
                ColumnCount = 2,
                RowCount    = 1
            };
            _mainLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33F));
            _mainLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 67F));
            _mainLayout.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
            _mainLayout.Controls.Add(_ctrlOuter,  0, 0);
            _mainLayout.Controls.Add(_rightSplit, 1, 0);

            Controls.Add(_mainLayout);
            Controls.Add(strip);

            ResumeLayout(false);
        }

        // ════════════════════════════════════════════════════════════
        // 卡片构建（绝对坐标 + Anchor，配合外层 AutoScroll 滚动）
        // ════════════════════════════════════════════════════════════

        /// <summary>
        /// 在 outer (AutoScroll Panel) 中用绝对坐标自上而下放置所有卡片。
        /// 卡片本身以 Anchor = Left|Right|Top 跟随宽度变化；
        /// 卡片内控件也用绝对坐标 + Anchor，并注册 card.Resize 同步宽度。
        /// </summary>
        private void BuildCards(Panel outer)
        {
            // 所有尺寸基于实际字体高度动态计算，适配任意 DPI
            int fh     = this.Font.Height;   // DPI 缩放后的字体像素高度（150%≈24, 100%≈15）
            int MarginH = 6;
            int Gap     = fh / 2 + 2;        // 卡片间距
            int RowH    = fh + 12;            // 行高（给 TextBox/Button 足够空间）
            int BtnH    = fh + 12;
            int PadX    = 10;
            int RowGap  = fh / 3;             // 行间距
            int PadB    = fh / 2 + 6;         // 卡片底部留白

            int curY = Gap;

            void TrackWidth(Control c)
            {
                void Sync() { if (!c.IsDisposed) c.Width = outer.ClientSize.Width - MarginH * 2; }
                outer.ClientSizeChanged += (s, e) => Sync();
                Sync();
            }

            (CardPanel card, int contentY) NewCard(string title)
            {
                var card = new CardPanel
                {
                    Title  = title, Left = MarginH, Top = curY, Height = 1,
                    Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top,
                    Font   = this.Font
                };
                TrackWidth(card);
                outer.Controls.Add(card);
                card.PerformLayout();
                int contentY = card.Padding.Top + RowGap;
                return (card, contentY);
            }

            void FinishCard(CardPanel card, int finalY)
            {
                card.Height = finalY + PadB;
                curY = card.Top + card.Height + Gap;
            }

            TextBox AddRow(CardPanel card, string lbl, int min, int max, int def, ref int y)
            {
                int lblW = PadX + 76;
                card.Controls.Add(new Label
                {
                    Text = lbl, Left = PadX, Top = y + 3, Width = 74, Height = fh + 4,
                    Anchor = AnchorStyles.Left | AnchorStyles.Top, ForeColor = Color.LightGray
                });
                var tb = new TextBox
                {
                    Left = lblW, Top = y, Height = RowH,
                    Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top,
                    BackColor = Color.FromArgb(50, 50, 50), ForeColor = Color.White,
                    BorderStyle = BorderStyle.FixedSingle, Text = def.ToString()
                };
                card.Resize += (s, e) => { if (!tb.IsDisposed) tb.Width = card.Width - lblW - PadX; };
                tb.Width = Math.Max(10, card.Width - lblW - PadX);
                WireIntTextBox(tb, min, max, def);
                card.Controls.Add(tb);
                // 用 TextBox 创建后的实际高度推进 y，避免计算偏差
                y += RowH + RowGap;
                return tb;
            }

            FlatRoundButton AddBtn(CardPanel card, string text, Color normal, Color hover, ref int y)
            {
                var btn = new FlatRoundButton
                {
                    Text = text, Left = PadX, Top = y, Height = BtnH,
                    Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top,
                    NormalColor = normal, HoverColor = hover, ForeColor = Color.White
                };
                card.Resize += (s, e) => { if (!btn.IsDisposed) btn.Width = card.Width - PadX * 2; };
                btn.Width = Math.Max(10, card.Width - PadX * 2);
                card.Controls.Add(btn);
                y += BtnH + RowGap;
                return btn;
            }

            // ════════════════════════════════════════════════════════
            // Card 1：捕获区域
            // ════════════════════════════════════════════════════════
            {
                var (card, y) = NewCard("捕获区域");

                _lblRegionInfo = new Label
                {
                    Text = "未选择区域", Left = PadX, Top = y,
                    Height = fh + 2, ForeColor = Color.Gray, AutoSize = false,
                    Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top
                };
                card.Resize += (s, e) => { if (!_lblRegionInfo.IsDisposed) _lblRegionInfo.Width = card.Width - PadX * 2; };
                _lblRegionInfo.Width = Math.Max(10, card.Width - PadX * 2);
                card.Controls.Add(_lblRegionInfo);
                y += fh + 2 + RowGap;

                _btnPickWindow   = AddBtn(card, "选择窗口…", Color.FromArgb(45, 60, 80),  Color.FromArgb(58, 78, 105),  ref y);
                _btnSelectRegion = AddBtn(card, "拖拽选区…", Color.FromArgb(45, 80, 45),  Color.FromArgb(58, 100, 58), ref y);

                FinishCard(card, y);
            }

            // ════════════════════════════════════════════════════════
            // Card 2：检测参数
            // ════════════════════════════════════════════════════════
            {
                var (card, y) = NewCard("检测参数");

                // 置信度阈值 标题
                var lblT = new Label
                {
                    Text = "置信度阈值：", ForeColor = Color.LightGray,
                    Left = PadX, Top = y, Height = fh + 2,
                    Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top
                };
                card.Resize += (s, e) => { if (!lblT.IsDisposed) lblT.Width = card.Width - PadX * 2; };
                lblT.Width = Math.Max(10, card.Width - PadX * 2);
                card.Controls.Add(lblT);
                y += fh + 2 + RowGap;

                // 滑块
                int sliderH = fh + 8;
                _trkThreshold = new DarkSlider
                {
                    Left = PadX, Top = y, Height = sliderH,
                    Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top,
                    Minimum = 10, Maximum = 90, Value = 45
                };
                card.Resize += (s, e) => { if (!_trkThreshold.IsDisposed) _trkThreshold.Width = card.Width - PadX * 2; };
                _trkThreshold.Width = Math.Max(10, card.Width - PadX * 2);
                card.Controls.Add(_trkThreshold);
                y += sliderH + RowGap;

                // 阈值数值
                _lblThreshold = new Label
                {
                    Text = "0.45", ForeColor = Color.LightGray,
                    Left = PadX, Top = y, Height = fh + 2,
                    Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top
                };
                card.Resize += (s, e) => { if (!_lblThreshold.IsDisposed) _lblThreshold.Width = card.Width - PadX * 2; };
                _lblThreshold.Width = Math.Max(10, card.Width - PadX * 2);
                card.Controls.Add(_lblThreshold);
                y += fh + 2 + RowGap;

                // 冷却
                _txtCooldown = AddRow(card, "冷却(s)：", 1, 300, 5, ref y);

                // 铃声复选
                int chkH = fh + 6;
                _chkPlaySound = new CheckBox
                {
                    Text = "警报铃声", Left = PadX, Top = y, Height = chkH,
                    Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top,
                    ForeColor = Color.LightGray, Checked = true
                };
                card.Resize += (s, e) => { if (!_chkPlaySound.IsDisposed) _chkPlaySound.Width = card.Width - PadX * 2; };
                _chkPlaySound.Width = Math.Max(10, card.Width - PadX * 2);
                card.Controls.Add(_chkPlaySound);
                y += chkH + RowGap;

                // 铃声路径 + 按钮
                int pickW = fh + 8;
                _txtSoundPath = new TextBox
                {
                    Left = PadX, Top = y, Height = RowH,
                    Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top,
                    BackColor = Color.FromArgb(45, 45, 45), ForeColor = Color.DimGray,
                    Text = @"Assets\soundreality-drums-loop-75bpm-3-455453.wav", ReadOnly = true
                };
                _btnPickSound = new FlatRoundButton
                {
                    Text = "…", Top = y, Width = pickW, Height = RowH,
                    Anchor = AnchorStyles.Right | AnchorStyles.Top,
                    NormalColor = Color.FromArgb(60, 60, 60), HoverColor = Color.FromArgb(75, 75, 75),
                    ForeColor = Color.White
                };
                card.Resize += (s, e) =>
                {
                    if (!_txtSoundPath.IsDisposed) _txtSoundPath.Width = card.Width - PadX * 2 - pickW - 4;
                    if (!_btnPickSound.IsDisposed)  _btnPickSound.Left  = card.Width - PadX - pickW;
                };
                _txtSoundPath.Width = Math.Max(10, card.Width - PadX * 2 - pickW - 4);
                _btnPickSound.Left  = Math.Max(0,  card.Width - PadX - pickW);
                card.Controls.Add(_txtSoundPath);
                card.Controls.Add(_btnPickSound);
                y += RowH + RowGap;

                FinishCard(card, y);
            }

            // ════════════════════════════════════════════════════════
            // Card 3：性能参数
            // ════════════════════════════════════════════════════════
            {
                var (card, y) = NewCard("性能参数");

                _txtFps     = AddRow(card, "FPS：",    1, 5, 2, ref y);
                _txtThreads = AddRow(card, "线程数：", 1, 8, 2, ref y);

                FinishCard(card, y);
            }

            // ════════════════════════════════════════════════════════
            // Card 4：监控对象
            // ════════════════════════════════════════════════════════
            {
                const int pickerH = 220;
                var (card, y) = NewCard("监控对象");

                _classPicker = new CocoClassPickerControl
                {
                    Left = PadX, Top = y, Height = pickerH,
                    Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top
                };
                card.Resize += (s, e) => { if (!_classPicker.IsDisposed) _classPicker.Width = card.Width - PadX * 2; };
                _classPicker.Width = Math.Max(10, card.Width - PadX * 2);
                card.Controls.Add(_classPicker);
                y += pickerH;

                FinishCard(card, y);
            }

            // ════════════════════════════════════════════════════════
            // 开始 / 停止按钮行
            // ════════════════════════════════════════════════════════
            {
                var btnPanel = new Panel
                {
                    Left = MarginH, Top = curY, Height = (BtnH + 4) * 2 + 12,
                    Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top,
                    BackColor = Color.FromArgb(32, 32, 32)
                };
                TrackWidth(btnPanel);
                outer.Controls.Add(btnPanel);

                _btnStart = new FlatRoundButton
                {
                    Text = "▶  开  始", Left = 4, Top = 4,
                    Height = BtnH + 4,
                    NormalColor = Color.FromArgb(0, 120, 212), HoverColor = Color.FromArgb(16, 110, 190),
                    PressColor  = Color.FromArgb(0, 90, 170),  ForeColor  = Color.White
                };
                _btnStop = new FlatRoundButton
                {
                    Text = "■  停  止", Left = 4, Top = BtnH + 8,
                    Height = BtnH + 4,
                    NormalColor = Color.FromArgb(58, 58, 58), HoverColor = Color.FromArgb(72, 72, 72),
                    ForeColor = Color.White, Enabled = false
                };
                btnPanel.Resize += (s, e) =>
                {
                    if (!_btnStart.IsDisposed) _btnStart.Width = btnPanel.ClientSize.Width - 8;
                    if (!_btnStop.IsDisposed)  _btnStop.Width  = btnPanel.ClientSize.Width - 8;
                };
                _btnStart.Width = btnPanel.ClientSize.Width - 8;
                _btnStop.Width  = btnPanel.ClientSize.Width - 8;
                btnPanel.Controls.Add(_btnStart);
                btnPanel.Controls.Add(_btnStop);
            }
        }

        private void ApplySplitterRatio()
        {
            if (_rightSplit == null) return;
            try
            {
                int h = _rightSplit.Height;
                int target = (int)(h * 0.70);
                target = Math.Max(_rightSplit.Panel1MinSize,
                         Math.Min(h - _rightSplit.Panel2MinSize, target));
                _rightSplit.SplitterDistance = target;
            }
            catch { /* 尺寸不合法时静默忽略 */ }
        }

        private void WireEvents()
        {
            _btnSelectRegion.Click += BtnSelectRegion_Click;
            _btnPickWindow.Click   += BtnPickWindow_Click;
            _btnStart.Click        += BtnStart_Click;
            _btnStop.Click         += BtnStop_Click;

            _trkThreshold.ValueChanged += (s, e) =>
                _lblThreshold.Text = (_trkThreshold.Value / 100f).ToString("F2");

            _chkPlaySound.CheckedChanged += (s, e) =>
            {
                _txtSoundPath.Enabled = _chkPlaySound.Checked;
                _btnPickSound.Enabled = _chkPlaySound.Checked;
            };

            _btnPickSound.Click += (s, e) =>
            {
                using (var dlg = new OpenFileDialog
                {
                    Title  = "选择警报铃声（WAV）",
                    Filter = "WAV 音频|*.wav",
                    CheckFileExists = true
                })
                {
                    if (dlg.ShowDialog() == DialogResult.OK)
                    {
                        _txtSoundPath.Text      = dlg.FileName;
                        _txtSoundPath.ForeColor = Color.White;
                    }
                }
            };

            _classPicker.SelectionChanged += (s, e) => { /* 可在此实时更新状态显示 */ };
        }

        /// <summary>注册 TextBox 的失焦整数验证（超范围 Clamp，非法恢复默认）。</summary>
        private static void WireIntTextBox(TextBox tb, int min, int max, int def)
        {
            tb.Leave += (s, e) =>
            {
                if (tb.IsDisposed) return;
                if (int.TryParse(tb.Text, out int v))
                    tb.Text = Math.Max(min, Math.Min(max, v)).ToString();
                else
                    tb.Text = def.ToString();
            };
        }

        /// <summary>安全解析 TextBox 值（用于 BuildConfig）。</summary>
        private static int ParseInt(string text, int min, int max, int def)
        {
            if (int.TryParse(text, out int v))
                return Math.Max(min, Math.Min(max, v));
            return def;
        }
    }
}
