// Form1.UI.cs — UI 构建：主布局（左预览 + 右 TabControl）、各页面、事件绑定、辅助方法
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Threading.Tasks;
using System.Linq;
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
            Size            = new Size(1420, 880);
            MinimumSize     = new Size(960, 640);
            FormBorderStyle = FormBorderStyle.Sizable;
            MaximizeBox     = true;
            StartPosition   = FormStartPosition.CenterScreen;

            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

            SuspendLayout();

            // StatusBar
            var strip = new StatusStrip { SizingGrip = false, Dock = DockStyle.Top };
            _tsStatus    = new ToolStripStatusLabel("未就绪") { ForeColor = Color.Gray };
            _tsLastAlert = new ToolStripStatusLabel("最后报警：-") { Spring = true };
            _tsInferMs   = new ToolStripStatusLabel("服务器：连接中") { Alignment = ToolStripItemAlignment.Right };
            strip.Items.AddRange(new ToolStripItem[] { _tsStatus, _tsLastAlert, _tsInferMs });

            // Preview container: fills left column, preview panel fills it and scales frame proportionally
            var previewContainer = new Panel { Dock = DockStyle.Fill };
            _previewGrid = new TableLayoutPanel { Dock = DockStyle.Fill, BackColor = Color.FromArgb(24, 24, 24) };
            previewContainer.Controls.Add(_previewGrid);

            // TabControl: 3 tabs for right side (Win7 native style)
            _tabControl = new TabControl { Dock = DockStyle.Fill };
            _tabCapture  = new TabPage("当前来源");
            _tabSettings = new TabPage("运行环境");
            _tabServer   = new TabPage("连接");
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
            Panel panel = (Panel)sender;
            string sourceId = panel.Tag as string ?? string.Empty;
            Graphics g = e.Graphics;
            Bitmap frame;
            List<Detection> dets;
            DateTime updatedAt;
            DateTime lastAlertAt;
            long inferenceMs;
            lock (_previewLock)
            {
                SourcePreviewState preview;
                if (_sourcePreviews.TryGetValue(sourceId, out preview) && preview.Frame != null)
                {
                    frame = (Bitmap)preview.Frame.Clone();
                    dets = preview.Detections;
                    updatedAt = preview.UpdatedAt;
                    lastAlertAt = preview.LastAlertAt;
                    inferenceMs = preview.InferenceMs;
                }
                else { frame = null; dets = null; updatedAt = DateTime.MinValue; lastAlertAt = DateTime.MinValue; inferenceMs = 0; }
            }

            MonitorSourceStatus status = _monitorCoordinator.Statuses.FirstOrDefault(value => value.SourceId == sourceId);
            SourceEditorState state = FindSourceState(sourceId);
            string title = state == null ? sourceId : state.SourceName;
            string statusText = status != null && status.IsStarting
                ? "启动中"
                : status != null && !string.IsNullOrWhiteSpace(status.Error)
                    ? "异常"
                    : status != null && status.IsMonitoring
                        ? string.Format("检测中  {0:0.##} FPS", status.ActualFps)
                        : status != null && status.IsReady ? "就绪" : "未配置";
            const int headerHeight = 66;
            using (var header = new SolidBrush(Color.FromArgb(40, 40, 40))) g.FillRectangle(header, 0, 0, panel.Width, headerHeight);
            using (var font = new Font(Font, FontStyle.Bold))
            using (var brush = new SolidBrush(Color.White)) g.DrawString(title + "  ·  " + statusText, font, brush, 8, 5);
            string details = string.Format("更新 {0}  推理 {1} ms  报警 {2}  {3}",
                updatedAt == DateTime.MinValue ? "-" : updatedAt.ToString("HH:mm:ss"), inferenceMs,
                lastAlertAt == DateTime.MinValue ? "-" : lastAlertAt.ToString("HH:mm:ss"),
                status == null ? "CPU" : status.ActiveBackend);
            using (var detailFont = new Font(Font.FontFamily, Math.Max(7f, Font.Size - 1f)))
            using (var detailBrush = new SolidBrush(Color.Gainsboro)) g.DrawString(details, detailFont, detailBrush, 8, 27);
            string notice = status == null ? string.Empty
                : !string.IsNullOrWhiteSpace(status.Error) ? status.Error : status.PerformanceWarning;
            if (!string.IsNullOrWhiteSpace(notice))
            {
                using (var noticeFont = new Font(Font.FontFamily, Math.Max(7f, Font.Size - 1f)))
                using (var noticeBrush = new SolidBrush(string.IsNullOrWhiteSpace(status.Error) ? Color.Goldenrod : Color.OrangeRed))
                    g.DrawString(notice, noticeFont, noticeBrush, new RectangleF(8, 45, Math.Max(1, panel.Width - 16), 18));
            }

            if (frame == null)
            {
                using (var font = new Font("Microsoft Sans Serif", 10))
                using (var brush = new SolidBrush(SystemColors.GrayText))
                {
                    string msg = "等待捕获...";
                    SizeF sz   = g.MeasureString(msg, font);
                    g.DrawString(msg, font, brush,
                        (panel.Width - sz.Width) / 2f,
                        headerHeight + (panel.Height - headerHeight - sz.Height) / 2f);
                }
                return;
            }

            try
            {
                // Scale frame to fit inside the 1:1 square canvas
                var fitted = FitRect(frame.Width, frame.Height, panel.Width, Math.Max(1, panel.Height - headerHeight));
                var dst = new RectangleF(fitted.X, fitted.Y + headerHeight, fitted.Width, fitted.Height);
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
            var page = new Panel { Dock = DockStyle.Fill, Padding = new Padding(12), AutoScroll = true };
            _currentSourcePage = page;
            int fh = Font.Height;
            int gap = fh / 3;

            // DockStyle.Top controls retain their visual order by being created top-to-bottom.
            BuildSourceManagementControls(page);

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
            Control sourcePage = _currentSourcePage ?? throw new InvalidOperationException("当前来源页面尚未创建。");
            int fh = Font.Height;
            int gap = fh / 3;
            int sliderH = fh * 2 + 8;

            AddTitle(page, "CPU 建议容量", fh); AddGap(page, gap);
            _numCpuCapacity = new NumericUpDown
            {
                Dock = DockStyle.Top,
                Minimum = 1,
                Maximum = MultiSourceMonitorCoordinator.MaximumSourceLimit,
                Value = MultiSourceMonitorCoordinator.DefaultSourceLimit,
                Height = fh + 12,
            };
            _numCpuCapacity.ValueChanged += (s, e) =>
            {
                _cpuCapacity = (int)_numCpuCapacity.Value;
                _monitorCoordinator.NotifyCapacityChanged();
                RefreshAllPreviewPanels();
                if (_sourceStates.Count > 0) SaveSettings();
            };
            page.Controls.Add(_numCpuCapacity);
            page.Controls.SetChildIndex(_numCpuCapacity, 0);
            AddGap(page, gap * 2);

            // 1. 置信度阈值
            AddTitle(sourcePage, "置信度阈值", fh); AddGap(sourcePage, gap);
            _trkThreshold = AddSlider(sourcePage, 10, 95, 45, 10, sliderH); AddGap(sourcePage, gap);
            _lblThreshold = AddVal(sourcePage, "45%", fh); AddGap(sourcePage, gap * 2);

            // 2. 目标采样率
            AddTitle(sourcePage, "目标采样率", fh); AddGap(sourcePage, gap);
            _sliderSamplingRate = AddSlider(sourcePage, 1, 5, 3, 1, sliderH); AddGap(sourcePage, gap);
            _lblSamplingRate = AddVal(sourcePage, "3 次/秒", fh); AddGap(sourcePage, gap * 2);

            // 3. 警报推送冷却时间
            AddTitle(sourcePage, "警报推送冷却时间", fh); AddGap(sourcePage, gap);
            _sliderCooldown = AddSlider(sourcePage, 1, 300, 5, 30, sliderH); AddGap(sourcePage, gap);
            _lblCooldown = AddVal(sourcePage, "5 秒", fh); AddGap(sourcePage, gap * 2);

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
                UpdateModelStatusLabel();
            };
            page.Controls.Add(_cmbModel);
            page.Controls.SetChildIndex(_cmbModel, 0);
            AddGap(page, gap);

            // Model status + download button
            var modelStatusRow = new Panel { Dock = DockStyle.Top, Height = fh + 10 };
            _lblModelStatus = new Label
            {
                Text = "○ 未下载",
                Dock = DockStyle.Left, AutoSize = true,
                TextAlign = ContentAlignment.MiddleLeft
            };
            var btnDownloadModel = new Button { Text = "下载模型", Dock = DockStyle.Right, Width = 96 };
            btnDownloadModel.Click += async (s, e2) =>
            {
                btnDownloadModel.Enabled = false;
                btnDownloadModel.Text = "下载中...";
                var key = _selectedModel;
                var progress = new Progress<int>(p =>
                {
                    this.Invoke((Action)(() => btnDownloadModel.Text = $"{p}%"));
                });
                var ok = await Task.Run(() => Utils.ModelManager.DownloadModel(key, progress));
                this.Invoke((Action)(() =>
                {
                    btnDownloadModel.Enabled = true;
                    btnDownloadModel.Text = ok ? "已下载" : "重试";
                    UpdateModelStatusLabel();
                }));
            };
            modelStatusRow.Controls.Add(btnDownloadModel);
            modelStatusRow.Controls.Add(_lblModelStatus);
            page.Controls.Add(modelStatusRow);
            page.Controls.SetChildIndex(modelStatusRow, 0);
            AddGap(page, gap * 2);

            // Init status
            UpdateModelStatusLabel();

            // 5. 监控目标
            AddTitle(sourcePage, "监控目标", fh); AddGap(sourcePage, gap);
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
            sourcePage.Controls.Add(_targetListBox);
            sourcePage.Controls.SetChildIndex(_targetListBox, 0);

            AddGap(sourcePage, gap * 2);
            var saveRow = new FlowLayoutPanel
            {
                Dock = DockStyle.Top,
                Height = fh + 16,
                FlowDirection = FlowDirection.LeftToRight,
                WrapContents = false,
            };
            var btnSaveSource = new Button { Text = "保存", Width = 96, Height = fh + 12 };
            var btnCancelSource = new Button { Text = "取消", Width = 96, Height = fh + 12 };
            btnSaveSource.Click += (s, e) => SaveCurrentSourceFromUi();
            btnCancelSource.Click += (s, e) => CancelCurrentSourceEdits();
            saveRow.Controls.Add(btnSaveSource);
            saveRow.Controls.Add(btnCancelSource);
            sourcePage.Controls.Add(saveRow);
            sourcePage.Controls.SetChildIndex(saveRow, 0);

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
            AddGap(page, gap * 3);

            // Separator
            var sep2 = new Label { Dock = DockStyle.Top, Height = 1, BorderStyle = BorderStyle.Fixed3D };
            page.Controls.Add(sep2);
            page.Controls.SetChildIndex(sep2, 0);
            AddGap(page, gap * 3);

            AddTitle(page, "版本更新", fh); AddGap(page, gap);

            // Row: version label + check update button
            var updateRow = new Panel { Dock = DockStyle.Top, Height = fh + 12 };
            var lblVersion = new Label
            {
                Text = $"当前版本 {Utils.AutoUpdater.CurrentVersion}",
                Dock = DockStyle.Left, AutoSize = true,
                TextAlign = ContentAlignment.MiddleLeft
            };
            var btnCheckUpdate = new Button { Text = "检查更新", Dock = DockStyle.Right, Width = 96 };
            btnCheckUpdate.Click += (s, e) =>
            {
                btnCheckUpdate.Enabled = false;
                btnCheckUpdate.Text = "检查中…";
                    Task.Run(async () =>
                    {
                        await Utils.AutoUpdater.CheckUpdate(ServerApiKey);
                        this.Invoke((Action)(() =>
                        {
                            btnCheckUpdate.Text = "检查更新";
                            btnCheckUpdate.Enabled = true;
                        }));
                    });
            };
            updateRow.Controls.Add(btnCheckUpdate);
            updateRow.Controls.Add(lblVersion);
            page.Controls.Add(updateRow);
            page.Controls.SetChildIndex(updateRow, 0);

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
