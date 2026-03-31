using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Windows.Forms;
using VisionGuard.Capture;
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

        // ── UI 控件（代码构建，不依赖 Designer）──────────────────────
        private DetectionOverlayPanel _overlayPanel;
        private NumericUpDown _nudX, _nudY, _nudW, _nudH;
        private NumericUpDown _nudFps, _nudThreads, _nudCooldown;
        private TrackBar      _trkThreshold;
        private Label         _lblThreshold;
        private Button        _btnSelectRegion, _btnStart, _btnStop;
        private CheckBox      _chkPlaySound;
        private TextBox       _txtSoundPath;
        private Button        _btnPickSound;
        private ListBox       _lstLog;
        private ToolStripStatusLabel _tsStatus, _tsLastAlert, _tsInferMs;
        private NotifyIcon    _notifyIcon;
        private GlobalKeyHook _keyHook;

        private string ModelPath => Path.Combine(
            AppDomain.CurrentDomain.BaseDirectory, "Assets", "yolov5nu.onnx");

        public Form1()
        {
            InitializeComponent();
            BuildUI();
            WireEvents();

            _alertService   = new AlertService();
            _monitorService = new MonitorService(_alertService);
            _log            = new LogManager(_lstLog);

            _alertService.AlertTriggered   += OnAlertTriggered;
            _alertService.AlarmStarted     += OnAlarmStarted;
            _alertService.AlarmStopped     += OnAlarmStopped;
            _monitorService.FrameProcessed += OnFrameProcessed;

            SetupTrayIcon();
            UpdateControlState(started: false);

            _log.Info("VisionGuard 已就绪，请选择捕获区域后点击「开始」。");
        }

        // ── 控件事件 ─────────────────────────────────────────────────

        private void BtnSelectRegion_Click(object sender, EventArgs e)
        {
            using (var selector = new RegionSelectorForm())
            {
                Hide();
                selector.ShowDialog(this);
                Show();
                BringToFront();

                if (selector.SelectedRegion != Rectangle.Empty)
                {
                    Rectangle r = selector.SelectedRegion;
                    _nudX.Value = r.X;
                    _nudY.Value = r.Y;
                    _nudW.Value = r.Width;
                    _nudH.Value = r.Height;
                    _log.Info($"已选择区域：({r.X}, {r.Y})  {r.Width}×{r.Height}");
                }
            }
        }

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
            if (cfg.CaptureRegion.Width < 32 || cfg.CaptureRegion.Height < 32)
            {
                MessageBox.Show("捕获区域太小（最小 32×32），请重新选择。",
                    "区域无效", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }

            try
            {
                _monitorService.Start(ModelPath, cfg);
                // 启动全局键盘钩子（UI 线程创建，确保有消息循环）
                _keyHook = new GlobalKeyHook();
                _keyHook.KeyDown += OnGlobalKeyDown;
                UpdateControlState(started: true);
                _log.Info($"监控已启动 | 区域 {cfg.CaptureRegion} | {cfg.TargetFps} FPS | 阈值 {cfg.ConfidenceThreshold:P0}");
            }
            catch (Exception ex)
            {
                // 展开完整异常链，帮助诊断 Win7 DLL 缺失问题
                string fullMsg = BuildExceptionMessage(ex);
                _log.Error("启动失败：" + fullMsg);
                MessageBox.Show(fullMsg, "启动失败",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        private void BtnStop_Click(object sender, EventArgs e)
        {
            _alertService.StopAlarm();   // 先停铃声，再停推理，避免 Resume 后立刻又被 Stop
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

        // ── MonitorService 回调（ThreadPool 线程）────────────────────

        private void OnFrameProcessed(object sender, FrameResultEventArgs e)
        {
            if (e.HasError)
            {
                _log.Error(e.Error.Message);
                return;
            }

            // overlayPanel 内部处理线程安全 + BeginInvoke
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
            {
                _tsLastAlert.Text = "最后报警：" + e.Timestamp.ToString("HH:mm:ss");
            }));

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

        // ── 辅助 ─────────────────────────────────────────────────────

        private MonitorConfig BuildConfig()
        {
            return new MonitorConfig
            {
                CaptureRegion       = new Rectangle(
                    (int)_nudX.Value, (int)_nudY.Value,
                    (int)_nudW.Value, (int)_nudH.Value),
                ConfidenceThreshold  = _trkThreshold.Value / 100f,
                TargetFps            = (int)_nudFps.Value,
                IntraOpNumThreads    = (int)_nudThreads.Value,
                WatchedClassIds      = new System.Collections.Generic.HashSet<int> { 0 },
                AlertCooldownSeconds = (int)_nudCooldown.Value,
                SaveAlertSnapshot    = true,
                PlayAlertSound       = _chkPlaySound.Checked,
                AlertSoundPath       = _txtSoundPath.Text == "默认系统音" ? string.Empty : _txtSoundPath.Text
            };
        }

        private void UpdateControlState(bool started)
        {
            _btnStart.Enabled        = !started;
            _btnStop.Enabled         =  started;
            _btnSelectRegion.Enabled = !started;
            _nudX.Enabled = _nudY.Enabled = _nudW.Enabled = _nudH.Enabled = !started;
            _nudFps.Enabled = _nudThreads.Enabled = _nudCooldown.Enabled = _chkPlaySound.Enabled = !started;
            _txtSoundPath.Enabled = !started && _chkPlaySound.Checked;
            _btnPickSound.Enabled = !started && _chkPlaySound.Checked;
            _trkThreshold.Enabled = !started;
            _tsStatus.Text      = started ? "● 监控中" : "○ 已停止";
            _tsStatus.ForeColor = started ? Color.LimeGreen : Color.Gray;
        }

        private void SetupTrayIcon()
        {
            _notifyIcon = new NotifyIcon
            {
                Text    = "VisionGuard",
                Icon    = SystemIcons.Shield,
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
            _alertService?.StopAlarm();
            _keyHook?.Dispose();
            _keyHook = null;
            _monitorService?.Stop();
            _monitorService?.Dispose();
            _notifyIcon?.Dispose();
            base.OnFormClosing(e);
        }

        // ── 异常诊断辅助 ─────────────────────────────────────────────

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

        // ── 构建 UI（纯代码，不依赖 Designer）────────────────────────

        private void BuildUI()
        {
            Text          = "VisionGuard — 人员检测监控";
            MinimumSize   = new Size(900, 600);
            Size          = new Size(1020, 680);
            StartPosition = FormStartPosition.CenterScreen;
            BackColor     = Color.FromArgb(25, 25, 25);
            ForeColor     = Color.LightGray;

            // ── 状态栏 ───────────────────────────────────────────────
            var strip   = new StatusStrip { BackColor = Color.FromArgb(40, 40, 40) };
            _tsStatus   = new ToolStripStatusLabel("○ 已停止") { ForeColor = Color.Gray };
            _tsLastAlert= new ToolStripStatusLabel("最后报警：—") { Spring = true };
            _tsInferMs  = new ToolStripStatusLabel("推理 — ms") { Alignment = ToolStripItemAlignment.Right };
            strip.Items.AddRange(new ToolStripItem[] { _tsStatus, _tsLastAlert, _tsInferMs });

            // ── 日志面板 ─────────────────────────────────────────────
            _lstLog = new ListBox
            {
                Dock                = DockStyle.Fill,
                Font                = new Font("Consolas", 8),
                BackColor           = Color.FromArgb(15, 15, 15),
                ForeColor           = Color.LightGray,
                ScrollAlwaysVisible = true,
                BorderStyle         = BorderStyle.None
            };
            var logPanel = new Panel { Dock = DockStyle.Bottom, Height = 130 };
            logPanel.Controls.Add(_lstLog);

            // ── 预览面板 ─────────────────────────────────────────────
            _overlayPanel = new DetectionOverlayPanel { Dock = DockStyle.Fill };

            // ── 控制面板 ─────────────────────────────────────────────
            var ctrl = new Panel
            {
                Dock      = DockStyle.Left,
                Width     = 240,
                Padding   = new Padding(8),
                BackColor = Color.FromArgb(35, 35, 35)
            };

            int y = 8;

            // 捕获区域组
            y = AddGroup(ctrl, "捕获区域", y, (gb, gy) =>
            {
                _nudX = MakeNud(gb, "X：",    gy,  0, 9999, 0);   gy += 26;
                _nudY = MakeNud(gb, "Y：",    gy,  0, 9999, 0);   gy += 26;
                _nudW = MakeNud(gb, "宽：",   gy, 32, 3840, 640); gy += 26;
                _nudH = MakeNud(gb, "高：",   gy, 32, 2160, 480); gy += 26;
                _btnSelectRegion = new Button
                {
                    Text      = "拖拽选区...",
                    Bounds    = new Rectangle(8, gy, gb.Width - 20, 26),
                    BackColor = Color.FromArgb(50, 80, 50),
                    ForeColor = Color.White,
                    FlatStyle = FlatStyle.Flat
                };
                gb.Controls.Add(_btnSelectRegion);
                return gy + 30;
            });

            // 检测参数组
            y = AddGroup(ctrl, "检测参数", y, (gb, gy) =>
            {
                gb.Controls.Add(new Label
                {
                    Text      = "置信度阈值：",
                    Bounds    = new Rectangle(8, gy, 120, 18),
                    ForeColor = Color.LightGray
                });
                gy += 20;
                _trkThreshold = new TrackBar
                {
                    Bounds        = new Rectangle(4, gy, gb.Width - 16, 30),
                    Minimum       = 10, Maximum = 90, Value = 45,
                    TickFrequency = 10
                };
                gb.Controls.Add(_trkThreshold);
                gy += 32;
                _lblThreshold = new Label
                {
                    Text      = "0.45",
                    Bounds    = new Rectangle(8, gy, 60, 18),
                    ForeColor = Color.LightGray
                };
                gb.Controls.Add(_lblThreshold);
                gy += 22;
                _nudCooldown = MakeNud(gb, "冷却(s)：", gy, 1, 300, 5); gy += 26;
                _chkPlaySound = new CheckBox
                {
                    Text      = "警报铃声",
                    Bounds    = new Rectangle(8, gy, gb.Width - 20, 22),
                    ForeColor = Color.LightGray,
                    Checked   = true
                };
                gb.Controls.Add(_chkPlaySound);
                gy += 26;
                _txtSoundPath = new TextBox
                {
                    Bounds    = new Rectangle(8, gy, gb.Width - 52, 22),
                    BackColor = Color.FromArgb(45, 45, 45),
                    ForeColor = Color.DimGray,
                    Text      = "默认系统音",
                    ReadOnly  = true
                };
                gb.Controls.Add(_txtSoundPath);
                _btnPickSound = new Button
                {
                    Text      = "...",
                    Bounds    = new Rectangle(gb.Width - 42, gy, 32, 22),
                    BackColor = Color.FromArgb(60, 60, 60),
                    ForeColor = Color.White,
                    FlatStyle = FlatStyle.Flat
                };
                gb.Controls.Add(_btnPickSound);
                gy += 26;
                return gy;
            });

            // 性能参数组
            y = AddGroup(ctrl, "性能参数", y, (gb, gy) =>
            {
                _nudFps     = MakeNud(gb, "FPS：",   gy, 1, 5, 2); gy += 26;
                _nudThreads = MakeNud(gb, "线程数：", gy, 1, 4, 2); gy += 26;
                return gy;
            });

            // 开始/停止
            _btnStart = new Button
            {
                Text      = "▶  开  始",
                Bounds    = new Rectangle(8, y, 104, 36),
                BackColor = Color.FromArgb(30, 100, 30),
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat,
                Font      = new Font(Font, FontStyle.Bold)
            };
            _btnStop = new Button
            {
                Text      = "■  停  止",
                Bounds    = new Rectangle(120, y, 104, 36),
                BackColor = Color.FromArgb(100, 30, 30),
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat,
                Font      = new Font(Font, FontStyle.Bold),
                Enabled   = false
            };
            ctrl.Controls.Add(_btnStart);
            ctrl.Controls.Add(_btnStop);

            // ── 组装 ─────────────────────────────────────────────────
            var main = new Panel { Dock = DockStyle.Fill };
            main.Controls.Add(_overlayPanel);
            main.Controls.Add(ctrl);

            Controls.Add(main);
            Controls.Add(logPanel);
            Controls.Add(strip);
        }

        private void WireEvents()
        {
            _btnSelectRegion.Click += BtnSelectRegion_Click;
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
        }

        // ── UI 构建辅助 ───────────────────────────────────────────────

        private int AddGroup(Panel parent, string title, int top, Func<GroupBox, int, int> build)
        {
            var gb = new GroupBox
            {
                Text      = title,
                ForeColor = Color.LightGray,
                Left      = 4, Top = top,
                Width     = parent.Width - 16
            };
            int innerY = build(gb, 18);
            gb.Height  = innerY + 8;
            parent.Controls.Add(gb);
            return top + gb.Height + 6;
        }

        private NumericUpDown MakeNud(GroupBox gb, string label, int top, int min, int max, int val)
        {
            gb.Controls.Add(new Label
            {
                Text      = label,
                Bounds    = new Rectangle(8, top + 2, 58, 18),
                ForeColor = Color.LightGray
            });
            var nud = new NumericUpDown
            {
                Bounds    = new Rectangle(68, top, gb.Width - 80, 22),
                Minimum   = min, Maximum = max, Value = val,
                BackColor = Color.FromArgb(45, 45, 45),
                ForeColor = Color.White
            };
            gb.Controls.Add(nud);
            return nud;
        }
    }
}
