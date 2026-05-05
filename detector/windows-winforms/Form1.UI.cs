// Form1.UI.cs — UI 构建：主布局、各页面、事件绑定、辅助方法
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Windows.Forms;
using VisionGuard.Data;
using VisionGuard.Models;
using VisionGuard.Services;
using VisionGuard.Utils;

namespace VisionGuard
{
    public partial class Form1
    {
        // ════════════════════════════════════════════════════════════
        // BuildUI — 主布局：960x640，左侧菜单 + 内容区 + 状态栏
        // ════════════════════════════════════════════════════════════

        private void BuildUI()
        {
            Text            = "VisionGuard — 人员检测监控";
            Size            = new Size(960, 640);
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox     = false;
            StartPosition   = FormStartPosition.CenterScreen;

            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

            SuspendLayout();

            // StatusBar
            var strip = new StatusStrip { SizingGrip = false };
            _tsStatus    = new ToolStripStatusLabel("已停止") { ForeColor = Color.Gray };
            _tsLastAlert = new ToolStripStatusLabel("最后报警：-") { Spring = true };
            _tsInferMs   = new ToolStripStatusLabel("推理 - ms") { Alignment = ToolStripItemAlignment.Right };
            strip.Items.AddRange(new ToolStripItem[] { _tsStatus, _tsLastAlert, _tsInferMs });

            // Left menu panel
            _menuPanel = new Panel { Dock = DockStyle.Left, Width = 72 };

            _menuCapture = new Button { Text = "捕获",   Dock = DockStyle.Top, Height = 56, FlatStyle = FlatStyle.Flat, UseVisualStyleBackColor = true };
            _menuParams  = new Button { Text = "参数",   Dock = DockStyle.Top, Height = 56, FlatStyle = FlatStyle.Flat, UseVisualStyleBackColor = true };
            _menuTargets = new Button { Text = "目标",   Dock = DockStyle.Top, Height = 56, FlatStyle = FlatStyle.Flat, UseVisualStyleBackColor = true };
            _menuServer  = new Button { Text = "服务器", Dock = DockStyle.Top, Height = 56, FlatStyle = FlatStyle.Flat, UseVisualStyleBackColor = true };
            _allMenuButtons = new[] { _menuCapture, _menuParams, _menuTargets, _menuServer };

            // Dock.Top stacks bottom-to-top by add order — add in reverse
            _menuPanel.Controls.Add(_menuServer);
            _menuPanel.Controls.Add(_menuTargets);
            _menuPanel.Controls.Add(_menuParams);
            _menuPanel.Controls.Add(_menuCapture);

            // Preview panel: 1:1 square, frame drawn scale-to-fit inside
            _previewPanel = new Panel { Size = new Size(420, 420) };
            EnableDoubleBuffering(_previewPanel);
            _previewPanel.Paint += PreviewPanel_Paint;

            // Page container (4 pages, stacked with Dock.Fill, toggled by Visible)
            _pageCapture = new Panel { Dock = DockStyle.Fill, Visible = true };
            _pageParams  = new Panel { Dock = DockStyle.Fill, Visible = false };
            _pageTargets = new Panel { Dock = DockStyle.Fill, Visible = false };
            _pageServer  = new Panel { Dock = DockStyle.Fill, Visible = false };

            _pageContainer = new Panel { Dock = DockStyle.Fill };
            _pageContainer.Controls.Add(_pageCapture);
            _pageContainer.Controls.Add(_pageParams);
            _pageContainer.Controls.Add(_pageTargets);
            _pageContainer.Controls.Add(_pageServer);

            // Layout: left column holds square preview, right column fills rest
            var contentLayout = new TableLayoutPanel
            {
                Dock        = DockStyle.Fill,
                ColumnCount = 2,
                RowCount    = 1
            };
            contentLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 432F));
            contentLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100F));
            contentLayout.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
            contentLayout.Controls.Add(_previewPanel,  0, 0);
            contentLayout.Controls.Add(_pageContainer, 1, 0);

            // Assemble
            Controls.Add(contentLayout);
            Controls.Add(_menuPanel);
            Controls.Add(strip);

            ResumeLayout(false);
        }

        // ════════════════════════════════════════════════════════════
        // Preview paint — draw frame + detection boxes
        // ════════════════════════════════════════════════════════════

        private void PreviewPanel_Paint(object sender, PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            Bitmap frame;
            List<Detection> dets;
            lock (_previewLock)
            {
                frame = _previewFrame != null ? (Bitmap)_previewFrame.Clone() : null;
                dets  = _previewDetections;
            }

            if (frame == null)
            {
                using (var font = new Font("Microsoft Sans Serif", 10))
                using (var brush = new SolidBrush(SystemColors.GrayText))
                {
                    string msg = "等待捕获...";
                    SizeF sz   = g.MeasureString(msg, font);
                    g.DrawString(msg, font, brush,
                        (_previewPanel.Width - sz.Width) / 2f,
                        (_previewPanel.Height - sz.Height) / 2f);
                }
                return;
            }

            try
            {
                // Scale frame to fit inside the 1:1 square canvas
                var dst = FitRect(frame.Width, frame.Height, _previewPanel.Width, _previewPanel.Height);
                g.DrawImage(frame, dst);

                if (dets == null || dets.Count == 0) return;

                float sx = dst.Width  / frame.Width;
                float sy = dst.Height / frame.Height;

                using (var pen  = new Pen(Color.LimeGreen, 2))
                using (var font = new Font("Consolas", 8, FontStyle.Bold))
                {
                    foreach (var det in dets)
                    {
                        float x = dst.X + det.BoundingBox.X * sx;
                        float y = dst.Y + det.BoundingBox.Y * sy;
                        float w = det.BoundingBox.Width  * sx;
                        float h = det.BoundingBox.Height * sy;

                        g.DrawRectangle(pen, x, y, w, h);

                        string label = $"{det.Label} {det.Confidence:P0}";
                        SizeF sz = g.MeasureString(label, font);

                        float lx = x;
                        float ly = y - sz.Height - 2;
                        if (ly < 0) ly = y + 2;

                        using (var bgBrush = new SolidBrush(Color.FromArgb(180, Color.Black)))
                            g.FillRectangle(bgBrush, lx, ly, sz.Width, sz.Height);
                        using (var textBrush = new SolidBrush(Color.LimeGreen))
                            g.DrawString(label, font, textBrush, lx, ly);
                    }
                }
            }
            finally
            {
                frame.Dispose();
            }
        }

        private static RectangleF FitRect(int srcW, int srcH, int dstW, int dstH)
        {
            float scale = Math.Min((float)dstW / srcW, (float)dstH / srcH);
            float w = srcW * scale;
            float h = srcH * scale;
            return new RectangleF((dstW - w) / 2f, (dstH - h) / 2f, w, h);
        }

        // ════════════════════════════════════════════════════════════
        // Page 1: Capture area + Start/Stop
        // ════════════════════════════════════════════════════════════

        private void BuildCapturePage()
        {
            int fh    = Font.Height;
            int pad   = 12;
            int gap   = fh / 3;
            int btnH  = fh + 12;
            int y     = 12;

            _pageCapture.Controls.Add(MakeTitle("捕获区域", pad, ref y, fh));

            _lblRegionInfo = new Label
            {
                Text = "未选择区域", Left = pad, Top = y,
                Width = _pageCapture.ClientSize.Width - pad * 2,
                Height = fh + 4, AutoSize = false,
                Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top
            };
            _pageCapture.Controls.Add(_lblRegionInfo);
            y += fh + 4 + gap;

            _btnPickWindow   = MakePageBtn(_pageCapture, "选择窗口...",   pad, btnH, ref y);
            y += gap;
            _btnSelectRegion = MakePageBtn(_pageCapture, "拖拽选区...",   pad, btnH, ref y);
            y += gap;
            _btnEditMasks    = MakePageBtn(_pageCapture, "遮罩区域...",   pad, btnH, ref y);
            y += gap / 2;

            _lblMaskInfo = new Label
            {
                Text = "当前遮罩：-", Left = pad, Top = y,
                Width = _pageCapture.ClientSize.Width - pad * 2,
                Height = fh + 4, AutoSize = false,
                Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top
            };
            _pageCapture.Controls.Add(_lblMaskInfo);
            y += fh + 4 + gap;

            y += fh;
            _pageCapture.Controls.Add(MakeTitle("监控控制", pad, ref y, fh));

            _btnStart = MakePageBtn(_pageCapture, "开始监控", pad, btnH + 4, ref y);
            y += gap;
            _btnStop  = MakePageBtn(_pageCapture, "停止监控", pad, btnH + 4, ref y);
            _btnStop.Enabled = false;
        }

        // ════════════════════════════════════════════════════════════
        // Page 2: Detection parameters
        // ════════════════════════════════════════════════════════════

        private void BuildParamsPage()
        {
            int fh      = Font.Height;
            int pad     = 12;
            int gap     = fh / 3;
            int sliderH = fh + 16;
            int y       = 12;

            // Confidence threshold
            _pageParams.Controls.Add(MakeTitle("置信度阈值", pad, ref y, fh));

            _trkThreshold = new TrackBar
            {
                Left = pad, Top = y, Height = sliderH,
                Width = _pageParams.ClientSize.Width - pad * 2,
                Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top,
                Minimum = 10, Maximum = 95, Value = 45,
                TickFrequency = 10
            };
            _pageParams.Controls.Add(_trkThreshold);
            y += sliderH + gap;

            _lblThreshold = new Label
            {
                Text = "45%", Left = pad, Top = y, Height = fh + 2,
                Width = _pageParams.ClientSize.Width - pad * 2,
                AutoSize = false,
                Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top
            };
            _pageParams.Controls.Add(_lblThreshold);
            y += fh + 2 + gap + fh / 2;

            // Target sampling rate
            _pageParams.Controls.Add(MakeTitle("目标采样率", pad, ref y, fh));

            _sliderSamplingRate = new TrackBar
            {
                Left = pad, Top = y, Height = sliderH,
                Width = _pageParams.ClientSize.Width - pad * 2,
                Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top,
                Minimum = 1, Maximum = 5, Value = 3,
                TickFrequency = 1
            };
            _pageParams.Controls.Add(_sliderSamplingRate);
            y += sliderH + gap;

            _lblSamplingRate = new Label
            {
                Text = "3 次/秒", Left = pad, Top = y, Height = fh + 2,
                Width = _pageParams.ClientSize.Width - pad * 2,
                AutoSize = false,
                Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top
            };
            _pageParams.Controls.Add(_lblSamplingRate);
            y += fh + 2 + gap + fh / 2;

            // Alert cooldown
            _pageParams.Controls.Add(MakeTitle("警报推送冷却时间", pad, ref y, fh));

            _sliderCooldown = new TrackBar
            {
                Left = pad, Top = y, Height = sliderH,
                Width = _pageParams.ClientSize.Width - pad * 2,
                Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top,
                Minimum = 1, Maximum = 300, Value = 5,
                TickFrequency = 30
            };
            _pageParams.Controls.Add(_sliderCooldown);
            y += sliderH + gap;

            _lblCooldown = new Label
            {
                Text = "5 秒", Left = pad, Top = y, Height = fh + 2,
                Width = _pageParams.ClientSize.Width - pad * 2,
                AutoSize = false,
                Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top
            };
            _pageParams.Controls.Add(_lblCooldown);
            y += fh + 2 + gap + fh / 2;

            // Model selection
            _pageParams.Controls.Add(MakeTitle("模型选择", pad, ref y, fh));

            int rowH = fh + 12;
            _cmbModel = new ComboBox
            {
                Left = pad, Top = y, Height = rowH,
                Width = _pageParams.ClientSize.Width - pad * 2,
                DropDownStyle = ComboBoxStyle.DropDownList,
                Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top
            };
            _cmbModel.Items.AddRange(new object[] { "YOLOv5nu 320 (轻量 ~10MB)" });
            _cmbModel.SelectedIndex = 0;
            _cmbModel.SelectedIndexChanged += (s, e) => { _selectedModel = "yolov5nu"; };
            _pageParams.Controls.Add(_cmbModel);
        }

        // ════════════════════════════════════════════════════════════
        // Page 3: Monitoring targets
        // ════════════════════════════════════════════════════════════

        private void BuildTargetsPage()
        {
            int pad = 12;
            int y   = 12;

            _pageTargets.Controls.Add(MakeTitle("监控目标", pad, ref y, Font.Height));

            _targetListBox = new CheckedListBox
            {
                Left           = pad,
                Top            = y,
                Width          = _pageTargets.ClientSize.Width - pad * 2,
                Height         = _pageTargets.ClientSize.Height - y - pad,
                CheckOnClick   = true,
                IntegralHeight = false,
                BorderStyle    = BorderStyle.FixedSingle,
                Anchor         = AnchorStyles.Left | AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Right
            };
            foreach (string key in _targetClassKeys)
                _targetListBox.Items.Add(CocoClassMap.EnZh[key], key == "person");

            _pageTargets.Controls.Add(_targetListBox);
        }

        // ════════════════════════════════════════════════════════════
        // Page 4: Server push
        // ════════════════════════════════════════════════════════════

        private void BuildServerPage()
        {
            const int pad = 12;
            int fh = Font.Height;
            int y  = 12;

            // Server connection
            _pageServer.Controls.Add(MakeTitle("服务器连接", pad, ref y, fh));

            _lblConnState = new Label
            {
                Text      = "未连接",
                Left      = pad,
                Top       = y + 2,
                AutoSize  = true,
                Font      = new Font(Font, FontStyle.Bold),
            };
            _pageServer.Controls.Add(_lblConnState);

            _btnRetry = new Button
            {
                Text   = "重试连接",
                Left   = _pageServer.ClientSize.Width - pad - 100,
                Top    = y,
                Width  = 100,
                Height = 28,
                Anchor = AnchorStyles.Right | AnchorStyles.Top
            };
            _pageServer.Controls.Add(_btnRetry);
            y += 44;

            // Separator
            _pageServer.Controls.Add(new Label
            {
                Left      = pad,
                Top       = y,
                Width     = _pageServer.ClientSize.Width - pad * 2,
                Height    = 1,
                BorderStyle = BorderStyle.Fixed3D,
                Anchor    = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top
            });
            y += 17;

            // Device name
            _pageServer.Controls.Add(MakeTitle("设备名称", pad, ref y, fh));

            _txtDeviceName = new TextBox
            {
                Left        = pad,
                Top         = y,
                Width       = 180,
                Text        = Environment.MachineName,
                Anchor      = AnchorStyles.Left | AnchorStyles.Top
            };
            _pageServer.Controls.Add(_txtDeviceName);

            var btnApplyName = new Button
            {
                Text   = "应用",
                Left   = pad + 180 + 8,
                Top    = y,
                Width  = 72,
                Height = 22,
                Anchor = AnchorStyles.Left | AnchorStyles.Top
            };
            _pageServer.Controls.Add(btnApplyName);

            // Hidden detail label (still assigned by WireServerPushEvents)
            _lblConnDetail = new Label { Visible = false };
            _pageServer.Controls.Add(_lblConnDetail);

            // Button events
            _btnRetry.Click += (s, e) =>
            {
                string name     = _txtDeviceName.Text.Trim();
                string deviceId = EnsureDeviceId();
                _serverPushService.Disconnect();
                _serverPushService.Configure(ServerUrl, ServerApiKey, deviceId, name);
                _log.Info("[Server] 手动重试连接...");
            };

            btnApplyName.Click += (s, e) =>
            {
                string name = _txtDeviceName.Text.Trim();
                if (string.IsNullOrEmpty(name)) { _log.Warn("设备名不能为空。"); return; }
                SettingsStore.Set("DeviceName", name);
                SettingsStore.Save();
                string deviceId = EnsureDeviceId();
                _serverPushService.Disconnect();
                _serverPushService.Configure(ServerUrl, ServerApiKey, deviceId, name);
                _log.Info($"[Server] 设备名已更新为「{name}」，重新连接中...");
            };
        }

        // ════════════════════════════════════════════════════════════
        // Event wiring
        // ════════════════════════════════════════════════════════════

        private void WireEvents()
        {
            // Menu switching
            _menuCapture.Click += (s, e) => ShowPage(_pageCapture, _menuCapture);
            _menuParams.Click  += (s, e) => ShowPage(_pageParams,  _menuParams);
            _menuTargets.Click += (s, e) => ShowPage(_pageTargets, _menuTargets);
            _menuServer.Click  += (s, e) => ShowPage(_pageServer,  _menuServer);

            // Capture page
            _btnSelectRegion.Click += BtnSelectRegion_Click;
            _btnPickWindow.Click   += BtnPickWindow_Click;
            _btnEditMasks.Click    += BtnEditMasks_Click;
            _btnStart.Click        += BtnStart_Click;
            _btnStop.Click         += BtnStop_Click;

            // Params page: TrackBar Scroll
            _trkThreshold.ValueChanged += (s, e) =>
                _lblThreshold.Text = $"{_trkThreshold.Value}%";

            _sliderSamplingRate.ValueChanged += (s, e) =>
                _lblSamplingRate.Text = $"{_sliderSamplingRate.Value} 次/秒";

            _sliderCooldown.ValueChanged += (s, e) =>
                _lblCooldown.Text = $"{_sliderCooldown.Value} 秒";
        }

        // ════════════════════════════════════════════════════════════
        // UI helpers
        // ════════════════════════════════════════════════════════════

        private Label MakeTitle(string text, int padX, ref int y, int fh)
        {
            var lbl = new Label
            {
                Text = text, Left = padX, Top = y,
                Height = fh + 4, AutoSize = false,
                Font = new Font(Font, FontStyle.Bold),
                Anchor = AnchorStyles.Left | AnchorStyles.Top
            };
            y += fh + 4 + fh / 3;
            return lbl;
        }

        private static Button MakePageBtn(Panel page, string text, int padX, int btnH, ref int y)
        {
            var btn = new Button
            {
                Text = text, Left = padX, Top = y, Height = btnH,
                Width = page.ClientSize.Width - padX * 2,
                Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top
            };
            page.Controls.Add(btn);
            y += btnH;
            return btn;
        }

        private static void EnableDoubleBuffering(Control c)
        {
            typeof(Control).GetProperty("DoubleBuffered",
                System.Reflection.BindingFlags.Instance |
                System.Reflection.BindingFlags.NonPublic)
                ?.SetValue(c, true);
        }
    }
}
