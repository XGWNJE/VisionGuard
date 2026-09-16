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
        // 原生 WinForms 的统一度量：不依赖第三方控件库，Win7 与高 DPI 下仍保持可预测的排版。
        private const int UiPagePadding = 16;
        private const int UiGap = 8;
        private const int UiFieldHeight = 32;
        private const int UiButtonHeight = 36;
        private const int UiPrimaryButtonHeight = 40;
        private const int UiSectionTitleHeight = 26;
        private const int UiCaptionHeight = 20;

        // ════════════════════════════════════════════════════════════
        // BuildUI — 主布局：960x640，左预览（2/3）+ 右 TabControl（1/3）+ 状态栏
        // ════════════════════════════════════════════════════════════

        private void BuildUI()
        {
            Text            = "VisionGuard — 人员检测监控";
            Size            = new Size(1420, 880);
            MinimumSize     = new Size(1100, 700);
            FormBorderStyle = FormBorderStyle.Sizable;
            MaximizeBox     = true;
            StartPosition   = FormStartPosition.CenterScreen;
            Font            = new Font("Segoe UI", 9F, FontStyle.Regular, GraphicsUnit.Point);

            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

            SuspendLayout();

            // StatusBar
            var strip = new StatusStrip { SizingGrip = false, Dock = DockStyle.Top, Height = 36, Padding = new Padding(10, 5, 10, 5) };
            _tsStatus    = new ToolStripStatusLabel("未就绪") { ForeColor = Color.Gray };
            _tsLastAlert = new ToolStripStatusLabel("最后报警：-") { Spring = true };
            _tsInferMs   = new ToolStripStatusLabel("服务器：连接中") { Alignment = ToolStripItemAlignment.Right };
            strip.Items.AddRange(new ToolStripItem[] { _tsStatus, _tsLastAlert, _tsInferMs });

            // Preview container: fills left column, preview panel fills it and scales frame proportionally
            var previewContainer = new Panel { Dock = DockStyle.Fill };
            _previewGrid = new TableLayoutPanel { Dock = DockStyle.Fill, BackColor = Color.FromArgb(24, 24, 24) };
            previewContainer.Controls.Add(_previewGrid);

            // TabControl: 3 tabs for right side (Win7 native style)
            _tabControl = new TabControl { Dock = DockStyle.Fill, Padding = new Point(14, 6) };
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
            contentLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 70F));
            contentLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 30F));
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
            const int headerHeight = 62;
            using (var header = new SolidBrush(Color.FromArgb(42, 42, 42))) g.FillRectangle(header, 0, 0, panel.Width, headerHeight);
            using (var titleFont = new Font("Segoe UI", 9F, FontStyle.Bold))
            {
                TextRenderer.DrawText(g, title + "  ·  " + statusText, titleFont,
                    new Rectangle(9, 6, Math.Max(1, panel.Width - 100), 20), Color.White,
                    TextFormatFlags.EndEllipsis | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);
            }
            string details = string.Format("更新 {0}  推理 {1} ms  报警 {2}  {3}",
                updatedAt == DateTime.MinValue ? "-" : updatedAt.ToString("HH:mm:ss"), inferenceMs,
                lastAlertAt == DateTime.MinValue ? "-" : lastAlertAt.ToString("HH:mm:ss"),
                status == null ? "CPU" : status.ActiveBackend);
            using (var detailFont = new Font("Segoe UI", 8F, FontStyle.Regular))
            {
                TextRenderer.DrawText(g, details, detailFont,
                    new Rectangle(9, 29, Math.Max(1, panel.Width - 18), 17), Color.Gainsboro,
                    TextFormatFlags.EndEllipsis | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);
            }
            string notice = status == null ? string.Empty
                : !string.IsNullOrWhiteSpace(status.Error) ? status.Error : status.PerformanceWarning;
            if (!string.IsNullOrWhiteSpace(notice))
            {
                using (var noticeFont = new Font("Segoe UI", 8F, FontStyle.Regular))
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
            var page = new Panel { Dock = DockStyle.Fill, Padding = new Padding(UiPagePadding), AutoScroll = true };
            _currentSourcePage = page;

            // 新增的顶部控件会排在 Dock=Top 的前面；按从底到顶构造，得到来源→采集→参数→控制的阅读顺序。
            BuildMonitoringControls(page);
            BuildDetectionControls(page);
            BuildCaptureTargetControls(page);
            BuildSourceManagementControls(page);

            _tabCapture.Controls.Add(page);
        }

        private void BuildCaptureTargetControls(Control page)
        {
            Panel content;
            AddTop(page, CreateSection("采集目标", 128, out content));
            var layout = CreateTwoColumnLayout(4, new[] { 28, 28, UiButtonHeight, UiButtonHeight });
            _lblRegionInfo = new Label { Text = "当前目标：未选择区域", Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleLeft, AutoEllipsis = true };
            _lblMaskInfo = new Label { Text = "当前遮罩：-", Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleLeft };
            _btnPickWindow = CreateActionButton("选择窗口", true);
            _btnSelectRegion = CreateActionButton("屏幕选区", false);
            _btnEditMasks = CreateActionButton("编辑遮罩", false);
            _btnResetCapture = CreateActionButton("重置", false);
            PlaceInTwoColumnLayout(layout, _lblRegionInfo, 0, 0, 2);
            PlaceInTwoColumnLayout(layout, _lblMaskInfo, 0, 1, 2);
            PlaceInTwoColumnLayout(layout, _btnPickWindow, 0, 2);
            PlaceInTwoColumnLayout(layout, _btnSelectRegion, 1, 2);
            PlaceInTwoColumnLayout(layout, _btnEditMasks, 0, 3);
            PlaceInTwoColumnLayout(layout, _btnResetCapture, 1, 3);
            content.Controls.Add(layout);
        }

        private void BuildMonitoringControls(Control page)
        {
            Panel content;
            AddTop(page, CreateSection("监控控制", UiPrimaryButtonHeight, out content));
            var layout = CreateTwoColumnLayout(1, new[] { UiPrimaryButtonHeight });
            _btnStart = CreateActionButton("开始监控", true, UiPrimaryButtonHeight);
            _btnStop = CreateActionButton("停止监控", false, UiPrimaryButtonHeight);
            _btnStop.Enabled = false;
            PlaceInTwoColumnLayout(layout, _btnStart, 0, 0);
            PlaceInTwoColumnLayout(layout, _btnStop, 1, 0);
            content.Controls.Add(layout);
        }

        private void BuildDetectionControls(Control page)
        {
            Panel content;
            AddTop(page, CreateSection("识别设置", 366, out content));
            var layout = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 9, Margin = Padding.Empty };
            for (int i = 0; i < 9; i++) layout.RowStyles.Add(new RowStyle(SizeType.Absolute, i == 7 ? 96 : (i == 8 ? UiButtonHeight + 4 : 30)));

            _trkThreshold = AddSlider(layout, 10, 95, 45, 10, 30);
            _lblThreshold = new Label { Text = "45%", Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleRight };
            _sliderSamplingRate = AddSlider(layout, 1, 5, 3, 1, 30);
            _lblSamplingRate = new Label { Text = "3 次/秒", Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleRight };
            _sliderCooldown = AddSlider(layout, 1, 300, 5, 30, 30);
            _lblCooldown = new Label { Text = "5 秒", Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleRight };
            _targetListBox = new CheckedListBox { Dock = DockStyle.Fill, CheckOnClick = true, IntegralHeight = false, BorderStyle = BorderStyle.FixedSingle };
            foreach (string key in _targetClassKeys) _targetListBox.Items.Add(CocoClassMap.EnZh[key], key == "person");

            layout.Controls.Add(CreateInlineTitle("置信度阈值", _lblThreshold), 0, 0);
            layout.Controls.Add(_trkThreshold, 0, 1);
            layout.Controls.Add(CreateInlineTitle("目标采样率", _lblSamplingRate), 0, 2);
            layout.Controls.Add(_sliderSamplingRate, 0, 3);
            layout.Controls.Add(CreateInlineTitle("报警冷却时间", _lblCooldown), 0, 4);
            layout.Controls.Add(_sliderCooldown, 0, 5);
            layout.Controls.Add(CreateInlineTitle("监控目标", null), 0, 6);
            layout.Controls.Add(_targetListBox, 0, 7);
            var actions = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.LeftToRight, WrapContents = false, Padding = new Padding(0, 4, 0, 0) };
            var save = CreateActionButton("保存此路", true); save.Width = 112;
            var cancel = CreateActionButton("撤销修改", false); cancel.Width = 112;
            save.Click += (s, e) => SaveCurrentSourceFromUi();
            cancel.Click += (s, e) => CancelCurrentSourceEdits();
            actions.Controls.Add(save); actions.Controls.Add(cancel);
            layout.Controls.Add(actions, 0, 8);
            content.Controls.Add(layout);
        }

        // ════════════════════════════════════════════════════════════
        // Page 2: Detection settings (matches WPF order)
        // ════════════════════════════════════════════════════════════

        private void BuildSettingsPage()
        {
            var page = new Panel { Dock = DockStyle.Fill, Padding = new Padding(UiPagePadding), AutoScroll = true };
            Panel capacityContent;
            AddTop(page, CreateSection("CPU 建议容量", UiFieldHeight, out capacityContent));
            _numCpuCapacity = new NumericUpDown
            {
                Dock = DockStyle.Fill,
                Minimum = 1,
                Maximum = MultiSourceMonitorCoordinator.MaximumSourceLimit,
                Value = MultiSourceMonitorCoordinator.DefaultSourceLimit,
            };
            _numCpuCapacity.ValueChanged += (s, e) =>
            {
                _cpuCapacity = (int)_numCpuCapacity.Value;
                _monitorCoordinator.NotifyCapacityChanged();
                RefreshAllPreviewPanels();
                if (_sourceStates.Count > 0) SaveSettings();
            };
            capacityContent.Controls.Add(_numCpuCapacity);

            Panel modelContent;
            AddTop(page, CreateSection("模型选择", 80, out modelContent));
            _cmbModel = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Top, Height = UiFieldHeight };
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
            // Model status + download button
            var modelStatusRow = new Panel { Dock = DockStyle.Bottom, Height = UiButtonHeight, Padding = new Padding(0, UiGap, 0, 0) };
            _lblModelStatus = new Label
            {
                Text = "○ 未下载",
                Dock = DockStyle.Left, AutoSize = true,
                TextAlign = ContentAlignment.MiddleLeft
            };
            var btnDownloadModel = CreateActionButton("下载模型", true);
            btnDownloadModel.Dock = DockStyle.Right;
            btnDownloadModel.Width = 112;
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
            modelContent.Controls.Add(modelStatusRow);
            modelContent.Controls.Add(_cmbModel);

            // Init status
            UpdateModelStatusLabel();

            _tabSettings.Controls.Add(page);
        }

        // ════════════════════════════════════════════════════════════
        // Page 4: Server push
        // ════════════════════════════════════════════════════════════

        private void BuildServerPage()
        {
            var page = new Panel { Dock = DockStyle.Fill, Padding = new Padding(UiPagePadding), AutoScroll = true };
            Panel connectionContent;
            var connectionSection = CreateSection("服务器连接", UiButtonHeight, out connectionContent);
            AddTop(page, connectionSection);

            // Row: connection state + retry button
            var connRow = new Panel { Dock = DockStyle.Fill };
            _lblConnState = new Label
            {
                Text = "未连接", Dock = DockStyle.Left, AutoSize = true,
                Font = new Font(Font, FontStyle.Bold), TextAlign = ContentAlignment.MiddleLeft
            };
            _btnRetry = CreateActionButton("重试连接", true);
            _btnRetry.Dock = DockStyle.Right;
            _btnRetry.Width = 112;
            connRow.Controls.Add(_btnRetry);
            connRow.Controls.Add(_lblConnState);
            connectionContent.Controls.Add(connRow);

            Panel deviceContent;
            var deviceSection = CreateSection("设备名称", UiFieldHeight, out deviceContent);
            AddTop(page, deviceSection);

            // Row: device name + apply button
            var nameRow = new Panel { Dock = DockStyle.Fill };
            var btnApplyName = CreateActionButton("应用", true);
            btnApplyName.Dock = DockStyle.Right;
            btnApplyName.Width = 80;
            _txtDeviceName = new TextBox { Dock = DockStyle.Fill, Text = Environment.MachineName };
            nameRow.Controls.Add(btnApplyName);
            nameRow.Controls.Add(_txtDeviceName);
            deviceContent.Controls.Add(nameRow);

            Panel updateContent;
            var updateSection = CreateSection("版本更新", UiButtonHeight, out updateContent);
            AddTop(page, updateSection);

            // Row: version label + check update button
            var updateRow = new Panel { Dock = DockStyle.Fill };
            var lblVersion = new Label
            {
                Text = $"当前版本 {Utils.AutoUpdater.CurrentVersion}",
                Dock = DockStyle.Left, AutoSize = true,
                TextAlign = ContentAlignment.MiddleLeft
            };
            var btnCheckUpdate = CreateActionButton("检查更新", false);
            btnCheckUpdate.Dock = DockStyle.Right;
            btnCheckUpdate.Width = 112;
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
            updateContent.Controls.Add(updateRow);

            // Hidden detail label (still assigned by WireServerPushEvents)
            _lblConnDetail = new Label { Visible = false };
            page.Controls.Add(_lblConnDetail);
            // Dock=Top 的最后一个可见子控件最靠上；显式保持连接→设备→更新的阅读顺序。
            page.Controls.SetChildIndex(connectionSection, page.Controls.Count - 1);
            page.Controls.SetChildIndex(deviceSection, page.Controls.Count - 2);
            page.Controls.SetChildIndex(updateSection, page.Controls.Count - 3);

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
            var btn = CreateActionButton(text, false, h);
            btn.Dock = DockStyle.Top;
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
            var tb = new TrackBar { Minimum = min, Maximum = max, Value = val, TickFrequency = tick, Dock = DockStyle.Fill, Height = h, Margin = new Padding(0) };
            if (!(parent is TableLayoutPanel))
            {
                parent.Controls.Add(tb);
                parent.Controls.SetChildIndex(tb, 0);
            }
            return tb;
        }

        private static void AddTop(Control parent, Control control)
        {
            parent.Controls.Add(control);
        }

        private static Panel CreateSection(string title, int contentHeight, out Panel content)
        {
            var section = new Panel
            {
                Dock = DockStyle.Top,
                Height = UiSectionTitleHeight + contentHeight + UiGap * 2,
                Padding = new Padding(0, 0, 0, UiGap * 2)
            };
            var heading = new Label
            {
                Text = title,
                Dock = DockStyle.Top,
                Height = UiSectionTitleHeight,
                Font = new Font(SystemFonts.MessageBoxFont, FontStyle.Bold),
                TextAlign = ContentAlignment.MiddleLeft
            };
            content = new Panel { Dock = DockStyle.Fill };
            section.Controls.Add(content);
            section.Controls.Add(heading);
            return section;
        }

        private static TableLayoutPanel CreateTwoColumnLayout(int rows, int[] heights)
        {
            var layout = new TableLayoutPanel
            {
                Dock = DockStyle.Fill,
                ColumnCount = 2,
                RowCount = rows,
                Margin = Padding.Empty,
                Padding = Padding.Empty
            };
            layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50F));
            layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50F));
            for (int i = 0; i < rows; i++) layout.RowStyles.Add(new RowStyle(SizeType.Absolute, heights[i]));
            return layout;
        }

        private static void PlaceInTwoColumnLayout(TableLayoutPanel layout, Control control, int column, int row, int columnSpan = 1)
        {
            control.Dock = DockStyle.Fill;
            control.Margin = columnSpan == 2
                ? Padding.Empty
                : column == 0 ? new Padding(0, 0, 4, 0) : new Padding(4, 0, 0, 0);
            layout.Controls.Add(control, column, row);
            if (columnSpan > 1) layout.SetColumnSpan(control, columnSpan);
        }

        private static Panel CreateInlineTitle(string title, Label value)
        {
            var row = new Panel { Dock = DockStyle.Fill };
            var label = new Label
            {
                Text = title,
                Dock = DockStyle.Left,
                AutoSize = true,
                Font = new Font(SystemFonts.MessageBoxFont, FontStyle.Bold),
                TextAlign = ContentAlignment.MiddleLeft
            };
            if (value != null) row.Controls.Add(value);
            row.Controls.Add(label);
            return row;
        }

        private static Label CreateFieldCaption(string text)
        {
            return new Label
            {
                Text = text,
                Dock = DockStyle.Fill,
                ForeColor = SystemColors.GrayText,
                TextAlign = ContentAlignment.BottomLeft
            };
        }

        private static Button CreateActionButton(string text, bool primary, int height = UiButtonHeight)
        {
            var button = new Button
            {
                Text = text,
                Dock = DockStyle.Fill,
                Height = height,
                Margin = new Padding(0),
                TextAlign = ContentAlignment.MiddleCenter,
                UseVisualStyleBackColor = true
            };
            if (primary) button.Font = new Font(SystemFonts.MessageBoxFont, FontStyle.Bold);
            return button;
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
