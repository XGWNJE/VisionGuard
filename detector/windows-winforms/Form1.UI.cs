// Form1.UI.cs — UI 构建：主布局（左预览 + 右 TabControl）、各页面、事件绑定、辅助方法
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
        // BuildUI — 主布局：960x640，左预览（2/3）+ 右 TabControl（1/3）+ 状态栏
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

            // Preview container: fills left column, preview panel fills it and scales frame proportionally
            var previewContainer = new Panel { Dock = DockStyle.Fill };
            _previewPanel = new Panel { Dock = DockStyle.Fill };
            EnableDoubleBuffering(_previewPanel);
            _previewPanel.Paint += PreviewPanel_Paint;
            previewContainer.Controls.Add(_previewPanel);

            // TabControl: 4 tabs for right side (Win7 native style)
            _tabControl = new TabControl { Dock = DockStyle.Fill };
            _tabCapture  = new TabPage("捕获");
            _tabSettings = new TabPage("设置");
            _tabServer   = new TabPage("服务器");
            _tabControl.TabPages.AddRange(new[] { _tabCapture, _tabSettings, _tabServer });

            // Layout: left 2/3 = preview, right 1/3 = TabControl
            var contentLayout = new TableLayoutPanel
            {
                Dock        = DockStyle.Fill,
                ColumnCount = 2,
                RowCount    = 1
            };
            contentLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 66.67F));
            contentLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.33F));
            contentLayout.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
            contentLayout.Controls.Add(previewContainer, 0, 0);
            contentLayout.Controls.Add(_tabControl,      1, 0);

            // Assemble
            Controls.Add(contentLayout);
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
            var page = new Panel { Dock = DockStyle.Fill, Padding = new Padding(12) };
            int fh = Font.Height;
            int gap = fh / 3;

            var title1 = new Label { Text = "捕获区域", Dock = DockStyle.Top, Height = fh + 4, Font = new Font(Font, FontStyle.Bold) };
            page.Controls.Add(title1);
            page.Controls.SetChildIndex(title1, 0);
            AddGap(page, gap);

            _lblRegionInfo = new Label { Text = "未选择区域", Dock = DockStyle.Top, Height = fh + 4 };
            page.Controls.Add(_lblRegionInfo);
            page.Controls.SetChildIndex(_lblRegionInfo, 0);
            AddGap(page, gap);

            _btnPickWindow   = AddBtn(page, "选择窗口...", fh + 12); AddGap(page, gap);
            _btnSelectRegion = AddBtn(page, "拖拽选区...", fh + 12); AddGap(page, gap);
            _btnEditMasks    = AddBtn(page, "遮罩区域...", fh + 12); AddGap(page, gap / 2);

            _lblMaskInfo = new Label { Text = "当前遮罩：-", Dock = DockStyle.Top, Height = fh + 4 };
            page.Controls.Add(_lblMaskInfo);
            page.Controls.SetChildIndex(_lblMaskInfo, 0);
            AddGap(page, gap);

            _btnResetCapture = AddBtn(page, "重置", fh + 12); AddGap(page, gap * 2);

            var title2 = new Label { Text = "监控控制", Dock = DockStyle.Top, Height = fh + 4, Font = new Font(Font, FontStyle.Bold) };
            page.Controls.Add(title2);
            page.Controls.SetChildIndex(title2, 0);
            AddGap(page, gap);

            _btnStart = AddBtn(page, "开始监控", fh + 16); AddGap(page, gap);
            _btnStop  = AddBtn(page, "停止监控", fh + 16);
            _btnStop.Enabled = false;

            _tabCapture.Controls.Add(page);
        }

        // ════════════════════════════════════════════════════════════
        // Page 2: Detection settings (matches WPF order)
        // ════════════════════════════════════════════════════════════

        private void BuildSettingsPage()
        {
            var page = new Panel { Dock = DockStyle.Fill, Padding = new Padding(12), AutoScroll = true };
            int fh = Font.Height;
            int gap = fh / 3;
            int sliderH = fh * 2 + 8;

            // 1. 置信度阈值
            AddTitle(page, "置信度阈值", fh); AddGap(page, gap);
            _trkThreshold = AddSlider(page, 10, 95, 45, 10, sliderH); AddGap(page, gap);
            _lblThreshold = AddVal(page, "45%", fh); AddGap(page, gap * 2);

            // 2. 目标采样率
            AddTitle(page, "目标采样率", fh); AddGap(page, gap);
            _sliderSamplingRate = AddSlider(page, 1, 5, 3, 1, sliderH); AddGap(page, gap);
            _lblSamplingRate = AddVal(page, "3 次/秒", fh); AddGap(page, gap * 2);

            // 3. 警报推送冷却时间
            AddTitle(page, "警报推送冷却时间", fh); AddGap(page, gap);
            _sliderCooldown = AddSlider(page, 1, 300, 5, 30, sliderH); AddGap(page, gap);
            _lblCooldown = AddVal(page, "5 秒", fh); AddGap(page, gap * 2);

            // 4. 模型选择
            AddTitle(page, "模型选择", fh); AddGap(page, gap);
            _cmbModel = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Top };
            _cmbModel.Items.AddRange(new object[] {
                "YOLOv5nu 320 (极速 ~10MB)",
                "YOLOv5nu 640 (极速高精 ~10MB)",
                "YOLOv5su 320 (快速 ~35MB)",
                "YOLOv5su 640 (快速高精 ~35MB)",
                "YOLOv5mu 320 (均衡 ~96MB)",
                "YOLOv5mu 640 (均衡高精 ~96MB)",
            });
            _cmbModel.SelectedIndex = 0;
            _cmbModel.SelectedIndexChanged += (s, e) => {
                string[] keys = { "yolov5nu_320","yolov5nu_640","yolov5su_320","yolov5su_640",
                                  "yolov5mu_320","yolov5mu_640" };
                if (_cmbModel.SelectedIndex >= 0 && _cmbModel.SelectedIndex < keys.Length)
                    _selectedModel = keys[_cmbModel.SelectedIndex];
                SaveSettings();
            };
            page.Controls.Add(_cmbModel);
            page.Controls.SetChildIndex(_cmbModel, 0);
            AddGap(page, gap * 2);

            // 5. 监控目标
            AddTitle(page, "监控目标", fh); AddGap(page, gap);
            _targetListBox = new CheckedListBox
            {
                Dock = DockStyle.Top,
                Height = fh * 8,
                CheckOnClick = true,
                IntegralHeight = false,
                BorderStyle = BorderStyle.FixedSingle
            };
            foreach (string key in _targetClassKeys)
                _targetListBox.Items.Add(CocoClassMap.EnZh[key], key == "person");
            page.Controls.Add(_targetListBox);
            page.Controls.SetChildIndex(_targetListBox, 0);

            _tabSettings.Controls.Add(page);
        }

        // ════════════════════════════════════════════════════════════
        // Page 4: Server push
        // ════════════════════════════════════════════════════════════

        private void BuildServerPage()
        {
            var page = new Panel { Dock = DockStyle.Fill, Padding = new Padding(12) };
            int fh = Font.Height;
            int gap = fh / 3;

            AddTitle(page, "服务器连接", fh); AddGap(page, gap);

            // Row: connection state + retry button
            var connRow = new Panel { Dock = DockStyle.Top, Height = fh + 14 };
            _lblConnState = new Label
            {
                Text = "未连接", Dock = DockStyle.Left, AutoSize = true,
                Font = new Font(Font, FontStyle.Bold), TextAlign = ContentAlignment.MiddleLeft
            };
            _btnRetry = new Button
            {
                Text = "重试连接", Dock = DockStyle.Right, Width = 96
            };
            connRow.Controls.Add(_btnRetry);
            connRow.Controls.Add(_lblConnState);
            page.Controls.Add(connRow);
            page.Controls.SetChildIndex(connRow, 0);
            AddGap(page, gap * 3);

            // Separator
            var sep = new Label { Dock = DockStyle.Top, Height = 1, BorderStyle = BorderStyle.Fixed3D };
            page.Controls.Add(sep);
            page.Controls.SetChildIndex(sep, 0);
            AddGap(page, gap * 3);

            AddTitle(page, "设备名称", fh); AddGap(page, gap);

            // Row: device name + apply button
            var nameRow = new Panel { Dock = DockStyle.Top, Height = fh + 12 };
            var btnApplyName = new Button { Text = "应用", Dock = DockStyle.Right, Width = 70 };
            _txtDeviceName = new TextBox { Dock = DockStyle.Fill, Text = Environment.MachineName };
            nameRow.Controls.Add(btnApplyName);
            nameRow.Controls.Add(_txtDeviceName);
            page.Controls.Add(nameRow);
            page.Controls.SetChildIndex(nameRow, 0);

            // Hidden detail label (still assigned by WireServerPushEvents)
            _lblConnDetail = new Label { Visible = false };
            page.Controls.Add(_lblConnDetail);

            _tabServer.Controls.Add(page);

            // Button events
            _btnRetry.Click += (s, e) =>
            {
                string name     = _txtDeviceName.Text.Trim();
                string deviceId = EnsureDeviceId();
                string serverUrl = ResolveServerUrlForCurrentSystem();
                _serverPushService.Disconnect();
                _serverPushService.Configure(serverUrl, ServerApiKey, deviceId, name);
                _log.Info("[Server] 手动重试连接...");
            };

            btnApplyName.Click += (s, e) =>
            {
                string name = _txtDeviceName.Text.Trim();
                if (string.IsNullOrEmpty(name)) { _log.Warn("设备名不能为空。"); return; }
                SettingsStore.Set("DeviceName", name);
                SettingsStore.Save();
                string deviceId = EnsureDeviceId();
                string serverUrl = ResolveServerUrlForCurrentSystem();
                _serverPushService.Disconnect();
                _serverPushService.Configure(serverUrl, ServerApiKey, deviceId, name);
                _log.Info($"[Server] 设备名已更新为「{name}」，重新连接中...");
            };
        }

        // ════════════════════════════════════════════════════════════
        // Event wiring
        // ════════════════════════════════════════════════════════════

        private void WireEvents()
        {
            // Capture page
            _btnSelectRegion.Click += BtnSelectRegion_Click;
            _btnPickWindow.Click   += BtnPickWindow_Click;
            _btnEditMasks.Click    += BtnEditMasks_Click;
            _btnResetCapture.Click += BtnResetCapture_Click;
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
        // UI helpers — all use Dock for layout, no absolute positioning
        // ════════════════════════════════════════════════════════════

        private static void AddGap(Control parent, int h)
        {
            var p = new Panel { Dock = DockStyle.Top, Height = h };
            parent.Controls.Add(p);
            parent.Controls.SetChildIndex(p, 0);
        }

        private static Button AddBtn(Control parent, string text, int h)
        {
            var btn = new Button { Text = text, Dock = DockStyle.Top, Height = h };
            parent.Controls.Add(btn);
            parent.Controls.SetChildIndex(btn, 0);
            return btn;
        }

        private static void AddTitle(Control parent, string text, int fh)
        {
            var lbl = new Label { Text = text, Dock = DockStyle.Top, Height = fh + 4, Font = new Font(parent.Font, FontStyle.Bold) };
            parent.Controls.Add(lbl);
            parent.Controls.SetChildIndex(lbl, 0);
        }

        private static Label AddVal(Control parent, string text, int fh)
        {
            var lbl = new Label { Text = text, Dock = DockStyle.Top, Height = fh + 2 };
            parent.Controls.Add(lbl);
            parent.Controls.SetChildIndex(lbl, 0);
            return lbl;
        }

        private static TrackBar AddSlider(Control parent, int min, int max, int val, int tick, int h)
        {
            var tb = new TrackBar { Minimum = min, Maximum = max, Value = val, TickFrequency = tick, Dock = DockStyle.Top, Height = h };
            parent.Controls.Add(tb);
            parent.Controls.SetChildIndex(tb, 0);
            return tb;
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
