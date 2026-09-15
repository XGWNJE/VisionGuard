using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Forms;
using VisionGuard.Capture;
using VisionGuard.Models;
using VisionGuard.Services;
using VisionGuard.Utils;

namespace VisionGuard
{
    public partial class Form1
    {
        private readonly List<SourceEditorState> _sourceStates = new List<SourceEditorState>();
        private ComboBox _cmbSource;
        private TextBox _txtSourceName;
        private Button _btnAddSource;
        private Button _btnRemoveSource;
        private Button _btnStartConfigured;
        private Button _btnStopAll;
        private bool _changingSource;
        private bool _bulkOperationInProgress;

        private void BuildSourceManagementControls(Control page)
        {
            int h = Font.Height + 12;
            var buttons = new FlowLayoutPanel
            {
                Dock = DockStyle.Top,
                Height = h + 2,
                FlowDirection = FlowDirection.LeftToRight,
                WrapContents = false,
            };
            _btnAddSource = new Button { Text = "新增来源", Width = 92, Height = h };
            _btnRemoveSource = new Button { Text = "删除来源", Width = 92, Height = h };
            buttons.Controls.Add(_btnAddSource);
            buttons.Controls.Add(_btnRemoveSource);

            var allButtons = new FlowLayoutPanel
            {
                Dock = DockStyle.Top,
                Height = h + 2,
                FlowDirection = FlowDirection.LeftToRight,
                WrapContents = false,
            };
            _btnStartConfigured = new Button { Text = "启动已配置", Width = 110, Height = h };
            _btnStopAll = new Button { Text = "全部停止", Width = 92, Height = h };
            allButtons.Controls.Add(_btnStartConfigured);
            allButtons.Controls.Add(_btnStopAll);

            var nameRow = new Panel { Dock = DockStyle.Top, Height = h };
            var apply = new Button { Text = "改名", Dock = DockStyle.Right, Width = 62 };
            _txtSourceName = new TextBox { Dock = DockStyle.Fill };
            nameRow.Controls.Add(_txtSourceName);
            nameRow.Controls.Add(apply);

            _cmbSource = new ComboBox
            {
                Dock = DockStyle.Top,
                DropDownStyle = ComboBoxStyle.DropDownList,
                DisplayMember = "DisplayName",
                Height = h,
            };

            AddGap(page, Font.Height / 2);
            page.Controls.Add(allButtons); page.Controls.SetChildIndex(allButtons, 0);
            page.Controls.Add(buttons); page.Controls.SetChildIndex(buttons, 0);
            page.Controls.Add(nameRow); page.Controls.SetChildIndex(nameRow, 0);
            page.Controls.Add(_cmbSource); page.Controls.SetChildIndex(_cmbSource, 0);
            AddTitle(page, "当前来源", Font.Height);

            _cmbSource.SelectedIndexChanged += (s, e) => SelectSourceFromUi();
            _btnAddSource.Click += (s, e) => AddSourceFromUi();
            _btnRemoveSource.Click += (s, e) => RemoveCurrentSource();
            _btnStartConfigured.Click += (s, e) => StartConfiguredSources();
            _btnStopAll.Click += (s, e) => StopAllSources();
            apply.Click += (s, e) => RenameCurrentSource();
        }

        private static readonly string[] SourceKeySuffixes =
        {
            "Id", "Name", "Model", "ConfidenceThresholdPct", "TargetFps", "AlertCooldownSeconds",
            "WatchedClasses", "MaskRegions", "CaptureMode", "ScreenRegion", "WindowSubRegion",
            "TargetWindowTitle", "TargetWindowClass", "TargetWindowProcess",
        };

        /// <summary>
        /// 早期的设置键用“信号”前缀；统一为“来源”后做一次性迁移，旧键保留作备份。
        /// </summary>
        private static void EnsureSourceKeyMigration()
        {
            if (SettingsStore.GetBool("WinForms.Source.KeyMigrationCompleted", false)) return;
            SourceSettingsMigration.Migrate(SettingsStore.Raw, "WinForms.Signal.", "WinForms.Source.",
                SourceKeySuffixes, MultiSourceMonitorCoordinator.MaximumSourceLimit);
            SourceSettingsMigration.MigrateCount(SettingsStore.Raw, "WinForms.Signal.Count", "WinForms.Source.Count", 0);
            SettingsStore.Set("WinForms.Source.KeyMigrationCompleted", true);
            SettingsStore.Save();
        }

        private void InitializeMultiSource()
        {
            int savedCount = SettingsStore.GetInt("WinForms.Source.Count", 0);
            SourceEditorState first = savedCount > 0 ? LoadSourceState(1) : null;
            if (first == null)
            {
                first = new SourceEditorState
                {
                    Index = 1,
                    SourceId = _sourceId,
                    SourceName = _sourceName,
                    ModelKey = _selectedModel,
                    Config = BuildConfig(),
                    TargetWindow = _targetWindow,
                };
            }
            _sourceId = first.SourceId;
            _sourceName = first.SourceName;
            _selectedModel = first.ModelKey;
            _sourceStates.Add(first);
            _monitorCoordinator.Add(first.ToMonitorSource());

            int count = Math.Max(1, Math.Min(MultiSourceMonitorCoordinator.MaximumSourceLimit,
                savedCount > 0 ? savedCount : 1));
            for (int index = 2; index <= count; index++)
            {
                SourceEditorState state = LoadSourceState(index);
                if (state == null) continue;
                try
                {
                    _sourceStates.Add(state);
                    _monitorCoordinator.Add(state.ToMonitorSource());
                }
                catch (InvalidOperationException ex)
                {
                    _log.Warn("来源 " + index + " 未恢复：" + ex.Message);
                }
            }
            RefreshSourceSelector(_sourceId);
            RefreshPreviewGrid();
            LoadSourceIntoEditor(first);
            UpdateSourceButtons();
        }

        private void AddSourceFromUi()
        {
            CaptureCurrentSourceState();
            if (_sourceStates.Count >= _monitorCoordinator.SourceLimit)
            {
                MessageBox.Show("已达到服务器允许的来源数量上限（" + _monitorCoordinator.SourceLimit + "）。",
                    "无法新增来源", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }

            int index = NextSourceIndex();
            string id = "winforms-" + Guid.NewGuid().ToString("N");
            var config = new MonitorConfig
            {
                CaptureMode = CaptureMode.ScreenRegion,
                CaptureRegion = Rectangle.Empty,
                TargetFps = 3,
                ConfidenceThreshold = 0.45f,
                AlertCooldownSeconds = 5,
            };
            config.WatchedClasses.Add("person");
            var state = new SourceEditorState
            {
                Index = index,
                SourceId = id,
                SourceName = "来源 " + index,
                ModelKey = "yolov5nu_320",
                Config = config,
            };
            _monitorCoordinator.Add(state.ToMonitorSource());
            _sourceStates.Add(state);
            RefreshSourceSelector(id);
            RefreshPreviewGrid();
            LoadSourceIntoEditor(state);
            SaveSettings();
            UpdateSourceButtons();
            UpdateServerHeartbeatParams();
            _serverPushService.SendHeartbeatNow();
        }

        private void RemoveCurrentSource()
        {
            if (_sourceStates.Count <= 1)
            {
                MessageBox.Show("至少保留一个来源。", "无法删除", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            SourceEditorState state = CurrentEditorState;
            if (state == null) return;
            if (IsCurrentSourceBusy)
            {
                MessageBox.Show("请先停止当前来源。", "来源正在运行", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            int oldPosition = _sourceStates.IndexOf(state);
            _monitorCoordinator.Remove(state.SourceId);
            _sourceStates.Remove(state);
            SourceEditorState next = _sourceStates[Math.Min(oldPosition, _sourceStates.Count - 1)];
            RefreshSourceSelector(next.SourceId);
            lock (_previewLock)
            {
                SourcePreviewState preview;
                if (_sourcePreviews.TryGetValue(state.SourceId, out preview) && preview.Frame != null) preview.Frame.Dispose();
                _sourcePreviews.Remove(state.SourceId);
            }
            RefreshPreviewGrid();
            LoadSourceIntoEditor(next);
            SaveSettings();
            UpdateSourceButtons();
            UpdateServerHeartbeatParams();
            _serverPushService.SendHeartbeatNow();
        }

        private void RenameCurrentSource()
        {
            SourceEditorState state = CurrentEditorState;
            string name = _txtSourceName.Text.Trim();
            if (state == null || string.IsNullOrWhiteSpace(name)) return;
            state.SourceName = name;
            _sourceName = name;
            _monitorCoordinator.Rename(state.SourceId, name);
            RefreshSourceSelector(state.SourceId);
            SaveSettings();
            UpdateServerHeartbeatParams();
            _serverPushService.SendHeartbeatNow();
        }

        private void SaveCurrentSourceFromUi()
        {
            CaptureCurrentSourceState();
            SaveSettings();
            UpdateServerHeartbeatParams();
            _serverPushService.SendHeartbeatNow();
            _log.Info("当前来源配置已保存。");
        }

        private void CancelCurrentSourceEdits()
        {
            SourceEditorState current = CurrentEditorState;
            if (current == null || current.Index <= 0) return;
            SourceEditorState persisted = LoadSourceState(current.Index);
            if (persisted == null)
            {
                _log.Warn("当前来源尚无已保存配置，无法取消。");
                return;
            }
            current.SourceName = persisted.SourceName;
            current.ModelKey = persisted.ModelKey;
            current.Config = persisted.Config;
            current.TargetWindow = persisted.TargetWindow;
            _monitorCoordinator.Rename(current.SourceId, current.SourceName);
            _monitorCoordinator.UpdateModel(current.SourceId, current.ModelKey);
            _monitorCoordinator.UpdateConfig(current.SourceId, current.Config);
            LoadSourceIntoEditor(current);
            RefreshSourceSelector(current.SourceId);
            RefreshAllPreviewPanels();
            _log.Info("已取消未保存的来源配置。");
        }

        private async void StartConfiguredSources()
        {
            if (_bulkOperationInProgress) return;
            CaptureCurrentSourceState();
            _bulkOperationInProgress = true;
            UpdateSourceButtons();
            _tsStatus.Text = "正在启动已配置来源...";
            var failures = new List<string>();
            int started = 0;
            try
            {
                SourceEditorState[] sources = _sourceStates.ToArray();
                await Task.Run(() =>
                {
                    foreach (SourceEditorState state in sources)
                    {
                        if (!IsSourceConfigured(state.Config)) continue;
                        string modelPath = ModelManager.GetModelPath(state.ModelKey);
                        if (!System.IO.File.Exists(modelPath))
                        {
                            failures.Add(state.SourceName + "：模型未下载");
                            continue;
                        }
                        try
                        {
                            _monitorCoordinator.Start(state.SourceId, modelPath);
                            started++;
                        }
                        catch (Exception ex) { failures.Add(state.SourceName + "：" + ex.Message); }
                    }
                });
            }
            finally
            {
                _bulkOperationInProgress = false;
            }
            UpdateControlState(IsCurrentSourceStarted);
            RefreshAllPreviewPanels();
            UpdateServerHeartbeatParams();
            _serverPushService.SendHeartbeatNow();
            _log.Info("已启动 " + started + " 个来源。" + (failures.Count == 0 ? string.Empty : " 未启动：" + string.Join("；", failures)));
        }

        private async void StopAllSources()
        {
            if (_bulkOperationInProgress) return;
            _bulkOperationInProgress = true;
            UpdateSourceButtons();
            _tsStatus.Text = "正在停止全部来源...";
            IDictionary<string, string> failures;
            try { failures = await Task.Run(() => _monitorCoordinator.StopAll()); }
            finally { _bulkOperationInProgress = false; }
            UpdateControlState(false);
            RefreshAllPreviewPanels();
            UpdateServerHeartbeatParams();
            _serverPushService.SendHeartbeatNow();
            _log.Info(failures.Count == 0
                ? "全部来源已停止。"
                : "部分来源停止失败：" + string.Join("；", failures.Select(item => item.Key + "：" + item.Value)));
        }

        private static bool IsSourceConfigured(MonitorConfig config)
        {
            if (config.CaptureMode == CaptureMode.WindowHandle) return config.TargetWindowHandle != IntPtr.Zero;
            return CaptureSizeConstraints.IsValid(config.CaptureRegion);
        }

        private void RefreshAllPreviewPanels()
        {
            if (_previewGrid == null) return;
            foreach (Control control in _previewGrid.Controls) control.Invalidate();
        }

        private void OnSourceStatusChanged(object sender, MonitorSourceStatusEventArgs e)
        {
            if (IsDisposed || !IsHandleCreated) return;
            BeginInvoke(new Action(() =>
            {
                Panel panel = FindPreviewPanel(e.Status.SourceId);
                if (panel != null)
                {
                    Button toggle = panel.Controls.OfType<Button>().FirstOrDefault();
                    if (toggle != null)
                    {
                        toggle.Text = e.Status.IsStarting ? "启动中" : e.Status.IsMonitoring ? "停止" : "启动";
                        toggle.Enabled = !e.Status.IsStarting;
                    }
                    panel.Invalidate();
                }
                if (string.Equals(e.Status.SourceId, _sourceId, StringComparison.Ordinal))
                {
                    UpdateControlState(e.Status.IsMonitoring);
                    if (e.Status.IsStarting)
                    {
                        _btnStart.Enabled = false;
                        _btnStop.Enabled = false;
                        _btnSelectRegion.Enabled = false;
                        _btnPickWindow.Enabled = false;
                    }
                }
                else
                    UpdateSourceButtons();
                UpdateAggregateSummary();
            }));
        }

        private void UpdateAggregateSummary()
        {
            IList<MonitorSourceStatus> statuses = _monitorCoordinator.Statuses;
            int running = statuses.Count(status => status.IsMonitoring);
            int errors = statuses.Count(status => !string.IsNullOrWhiteSpace(status.Error));
            // 一个完整配置的来源都没有时是未就绪，不能显示成“检测中 0/4”。
            int configured = _sourceStates.Count(state => IsSourceConfigured(state.Config));
            _tsStatus.Text = configured == 0
                ? "未就绪"
                : string.Format("检测中 {0}/{1}  异常 {2}", running, configured, errors);
            _tsStatus.ForeColor = configured == 0 ? Color.Gray : errors > 0 ? Color.OrangeRed : running > 0 ? Color.LimeGreen : Color.Gray;
        }

        private void SelectSourceFromUi()
        {
            if (_changingSource) return;
            SourceListItem item = _cmbSource.SelectedItem as SourceListItem;
            if (item == null || string.Equals(item.SourceId, _sourceId, StringComparison.Ordinal)) return;
            CaptureCurrentSourceState();
            SourceEditorState state = _sourceStates.FirstOrDefault(value => value.SourceId == item.SourceId);
            if (state != null) LoadSourceIntoEditor(state);
        }

        private void CaptureCurrentSourceState()
        {
            if (_changingSource || _sourceStates.Count == 0) return;
            SourceEditorState state = CurrentEditorState;
            if (state == null) return;
            state.SourceName = _sourceName;
            state.ModelKey = _selectedModel;
            state.TargetWindow = _targetWindow;
            state.Config = BuildConfig();
            _monitorCoordinator.UpdateConfig(state.SourceId, state.Config);
            _monitorCoordinator.UpdateModel(state.SourceId, state.ModelKey);
        }

        private void LoadSourceIntoEditor(SourceEditorState state)
        {
            _changingSource = true;
            try
            {
                _sourceId = state.SourceId;
                _sourceName = state.SourceName;
                _selectedModel = state.ModelKey;
                _targetWindow = state.TargetWindow;
                _screenRegion = state.Config.CaptureRegion;
                _windowSubRegion = state.Config.WindowSubRegion;
                _maskRegions = new List<RectangleF>(state.Config.MaskRegions ?? new List<RectangleF>());
                _txtSourceName.Text = state.SourceName;
                _trkThreshold.Value = Clamp((int)Math.Round(state.Config.ConfidenceThreshold * 100), _trkThreshold.Minimum, _trkThreshold.Maximum);
                _sliderSamplingRate.Value = Clamp(state.Config.TargetFps, _sliderSamplingRate.Minimum, _sliderSamplingRate.Maximum);
                _sliderCooldown.Value = Clamp(state.Config.AlertCooldownSeconds, _sliderCooldown.Minimum, _sliderCooldown.Maximum);
                for (int i = 0; i < _targetClassKeys.Length; i++)
                    _targetListBox.SetItemChecked(i, state.Config.WatchedClasses.Contains(_targetClassKeys[i]));
                _cmbModel.SelectedIndex = Math.Max(0, Array.IndexOf(ModelManager.ModelKeys, state.ModelKey));
                _btnSelectRegion.Text = _targetWindow == null ? "拖拽选区..." : "选择子区域…";
                UpdateRegionLabel();
                UpdateMaskInfoLabel();
                UpdateModelStatusLabel();
                UpdateControlState(CurrentSourceStatus.IsMonitoring);
            }
            finally { _changingSource = false; }
        }

        private void RefreshSourceSelector(string selectedId)
        {
            _changingSource = true;
            try
            {
                _cmbSource.Items.Clear();
                foreach (SourceEditorState state in _sourceStates.OrderBy(value => value.Index))
                    _cmbSource.Items.Add(new SourceListItem(state.SourceId, state.SourceName));
                for (int i = 0; i < _cmbSource.Items.Count; i++)
                {
                    var item = (SourceListItem)_cmbSource.Items[i];
                    if (item.SourceId == selectedId) { _cmbSource.SelectedIndex = i; break; }
                }
            }
            finally { _changingSource = false; }
        }

        private void RefreshPreviewGrid()
        {
            if (_previewGrid == null) return;
            _previewGrid.SuspendLayout();
            _previewGrid.Controls.Clear();
            _previewGrid.ColumnStyles.Clear();
            _previewGrid.RowStyles.Clear();
            int count = Math.Max(1, _sourceStates.Count);
            int columns = count == 1 ? 1 : (int)Math.Ceiling(Math.Sqrt(count));
            int rows = (int)Math.Ceiling(count / (double)columns);
            _previewGrid.ColumnCount = columns;
            _previewGrid.RowCount = rows;
            for (int column = 0; column < columns; column++)
                _previewGrid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f / columns));
            for (int row = 0; row < rows; row++)
                _previewGrid.RowStyles.Add(new RowStyle(SizeType.Percent, 100f / rows));
            for (int i = 0; i < _sourceStates.Count; i++)
            {
                SourceEditorState state = _sourceStates[i];
                var panel = new Panel
                {
                    Dock = DockStyle.Fill,
                    Margin = new Padding(3),
                    BorderStyle = BorderStyle.FixedSingle,
                    BackColor = Color.Black,
                    Tag = state.SourceId,
                    Cursor = Cursors.Hand,
                };
                EnableDoubleBuffering(panel);
                panel.Paint += PreviewPanel_Paint;
                panel.Click += (s, e) => SelectSourceById((string)((Panel)s).Tag);
                var toggle = new Button
                {
                    Text = "启动",
                    Width = 58,
                    Height = 24,
                    Anchor = AnchorStyles.Top | AnchorStyles.Right,
                    Tag = "toggle:" + state.SourceId,
                };
                Action positionToggle = () => toggle.Location = new Point(Math.Max(0, panel.ClientSize.Width - toggle.Width - 2), 1);
                panel.Resize += (s, e) => positionToggle();
                toggle.Click += (s, e) =>
                {
                    MonitorSourceStatus current = _monitorCoordinator.Statuses.FirstOrDefault(value => value.SourceId == state.SourceId);
                    if (current != null && current.IsMonitoring) StopMonitor(false, targetSourceId: state.SourceId);
                    else StartMonitor(false, targetSourceId: state.SourceId);
                };
                panel.Controls.Add(toggle);
                positionToggle();
                _previewGrid.Controls.Add(panel, i % columns, i / columns);
                if (string.Equals(state.SourceId, _sourceId, StringComparison.Ordinal)) _previewPanel = panel;
            }
            _previewGrid.ResumeLayout(true);
        }

        private void SelectSourceById(string sourceId)
        {
            if (string.Equals(sourceId, _sourceId, StringComparison.Ordinal)) return;
            CaptureCurrentSourceState();
            SourceEditorState state = FindSourceState(sourceId);
            if (state == null) return;
            RefreshSourceSelector(sourceId);
            LoadSourceIntoEditor(state);
            _previewPanel = FindPreviewPanel(sourceId);
        }

        private SourceEditorState CurrentEditorState
        {
            get { return _sourceStates.FirstOrDefault(value => value.SourceId == _sourceId); }
        }

        private SourceEditorState FindSourceState(string sourceId)
        {
            return _sourceStates.FirstOrDefault(value => string.Equals(value.SourceId, sourceId, StringComparison.Ordinal));
        }

        private string ResolveCommandSourceId(string targetSourceId, bool defaultToFirst = false)
        {
            if (!string.IsNullOrWhiteSpace(targetSourceId)) return targetSourceId;
            return defaultToFirst && _sourceStates.Count > 0 ? _sourceStates[0].SourceId : _sourceId;
        }

        private bool HasSource(string sourceId) { return FindSourceState(sourceId) != null; }

        private bool TryApplyRemoteConfigToSource(string sourceId, string key, string value, out string error)
        {
            error = string.Empty;
            SourceEditorState state = FindSourceState(sourceId);
            if (state == null) { error = "目标来源不存在"; return false; }
            int number;
            float confidence;
            switch (key)
            {
                case "cooldown":
                    if (!int.TryParse(value, out number) || number < 1 || number > 300) { error = "值无效（1-300）"; return false; }
                    state.Config.AlertCooldownSeconds = number;
                    break;
                case "confidence":
                    if (!float.TryParse(value, System.Globalization.NumberStyles.Float,
                        System.Globalization.CultureInfo.InvariantCulture, out confidence) || confidence < 0.1f || confidence > 0.95f)
                    { error = "值无效（0.1-0.95）"; return false; }
                    state.Config.ConfidenceThreshold = confidence;
                    break;
                case "targetSamplingRate":
                    if (!int.TryParse(value, out number) || number < 1 || number > 5) { error = "值无效（1-5）"; return false; }
                    state.Config.TargetFps = number;
                    break;
                case "targets":
                    state.Config.WatchedClasses.Clear();
                    foreach (string item in (value ?? string.Empty).Split(','))
                    {
                        string trimmed = item.Trim();
                        if (_targetClassKeys.Contains(trimmed, StringComparer.OrdinalIgnoreCase))
                            state.Config.WatchedClasses.Add(trimmed);
                    }
                    break;
                case "modelKey":
                    MonitorSourceStatus status = _monitorCoordinator.Statuses.FirstOrDefault(item => item.SourceId == sourceId);
                    if (status != null && status.IsMonitoring) { error = "请先停止监控再切换模型"; return false; }
                    if (!ModelManager.ModelKeys.Contains(value)) { error = "模型不支持"; return false; }
                    state.ModelKey = value;
                    _monitorCoordinator.UpdateModel(sourceId, value);
                    break;
                default:
                    error = "未知配置项：" + key;
                    return false;
            }
            _monitorCoordinator.UpdateConfig(sourceId, state.Config);
            return true;
        }

        private void UpdateSourceButtons()
        {
            if (_btnAddSource != null) _btnAddSource.Enabled = !_bulkOperationInProgress && _sourceStates.Count < _monitorCoordinator.SourceLimit;
            if (_btnRemoveSource != null) _btnRemoveSource.Enabled = !_bulkOperationInProgress && _sourceStates.Count > 1 && !IsCurrentSourceBusy;
            // 必须至少有一个完整配置的来源才能启动；没有就是未就绪，按钮直接不可用而不是点了没反应。
            if (_btnStartConfigured != null) _btnStartConfigured.Enabled = !_bulkOperationInProgress && _sourceStates.Any(state => IsSourceConfigured(state.Config));
            if (_btnStopAll != null) _btnStopAll.Enabled = !_bulkOperationInProgress && _monitorCoordinator.Statuses.Any(status => status.IsMonitoring);
        }

        private int NextSourceIndex()
        {
            int index = 1;
            var used = new HashSet<int>(_sourceStates.Select(value => value.Index));
            while (used.Contains(index)) index++;
            return index;
        }

        private void SaveAllSourceSettings()
        {
            if (_sourceStates.Count == 0) return;
            CaptureCurrentSourceState();
            SettingsStore.Set("WinForms.Source.Count", _sourceStates.Count);
            int position = 1;
            foreach (SourceEditorState state in _sourceStates.OrderBy(value => value.Index))
            {
                state.Index = position;
                string key = "WinForms.Source." + position + ".";
                SettingsStore.Set(key + "Id", state.SourceId);
                SettingsStore.Set(key + "Name", state.SourceName);
                SettingsStore.Set(key + "Model", state.ModelKey);
                SettingsStore.Set(key + "ConfidenceThresholdPct", (int)Math.Round(state.Config.ConfidenceThreshold * 100));
                SettingsStore.Set(key + "TargetFps", state.Config.TargetFps);
                SettingsStore.Set(key + "AlertCooldownSeconds", state.Config.AlertCooldownSeconds);
                SettingsStore.Set(key + "WatchedClasses", string.Join(",", state.Config.WatchedClasses));
                SettingsStore.Set(key + "MaskRegions", MasksToJson(state.Config.MaskRegions));
                SettingsStore.Set(key + "CaptureMode", state.Config.CaptureMode.ToString());
                SettingsStore.Set(key + "ScreenRegion", RectToString(state.Config.CaptureRegion));
                SettingsStore.Set(key + "WindowSubRegion", RectToString(state.Config.WindowSubRegion));
                SettingsStore.Set(key + "TargetWindowTitle", state.TargetWindow == null ? state.Config.TargetWindowTitle : state.TargetWindow.Title);
                SettingsStore.Set(key + "TargetWindowClass", state.TargetWindow == null ? string.Empty : state.TargetWindow.ClassName);
                SettingsStore.Set(key + "TargetWindowProcess", state.TargetWindow == null ? string.Empty : state.TargetWindow.ProcessName);
                position++;
            }
        }

        private SourceEditorState LoadSourceState(int index)
        {
            string key = "WinForms.Source." + index + ".";
            string id = SettingsStore.GetString(key + "Id", string.Empty);
            if (string.IsNullOrWhiteSpace(id)) return null;
            string title = SettingsStore.GetString(key + "TargetWindowTitle", string.Empty);
            string className = SettingsStore.GetString(key + "TargetWindowClass", string.Empty);
            string processName = SettingsStore.GetString(key + "TargetWindowProcess", string.Empty);
            WindowInfo window = ResolveSavedWindow(title, className, processName);
            CaptureMode mode;
            if (!Enum.TryParse(SettingsStore.GetString(key + "CaptureMode", "ScreenRegion"), out mode)) mode = CaptureMode.ScreenRegion;
            var config = new MonitorConfig
            {
                CaptureMode = mode,
                CaptureRegion = ParseRect(SettingsStore.GetString(key + "ScreenRegion", string.Empty)),
                WindowSubRegion = ParseRect(SettingsStore.GetString(key + "WindowSubRegion", string.Empty)),
                TargetWindowTitle = title,
                TargetWindowHandle = window == null ? IntPtr.Zero : window.Handle,
                ConfidenceThreshold = Clamp(SettingsStore.GetInt(key + "ConfidenceThresholdPct", 45), 10, 95) / 100f,
                TargetFps = Clamp(SettingsStore.GetInt(key + "TargetFps", 3), 1, 5),
                AlertCooldownSeconds = Clamp(SettingsStore.GetInt(key + "AlertCooldownSeconds", 5), 1, 300),
                MaskRegions = ParseMasksJson(SettingsStore.GetString(key + "MaskRegions", string.Empty)),
            };
            foreach (string watched in SettingsStore.GetStringList(key + "WatchedClasses")) config.WatchedClasses.Add(watched);
            if (config.WatchedClasses.Count == 0) config.WatchedClasses.Add("person");
            return new SourceEditorState
            {
                Index = index,
                SourceId = id,
                SourceName = SettingsStore.GetString(key + "Name", "来源 " + index),
                ModelKey = SettingsStore.GetString(key + "Model", "yolov5nu_320"),
                Config = config,
                TargetWindow = window,
            };
        }

        private WindowInfo ResolveSavedWindow(string title, string className, string processName)
        {
            if (string.IsNullOrWhiteSpace(title)) return null;
            List<WindowInfo> windows = WindowEnumerator.GetWindows(Handle);
            List<WindowInfo> exact = windows.Where(window =>
                string.Equals(window.Title, title, StringComparison.OrdinalIgnoreCase) &&
                (string.IsNullOrWhiteSpace(className) || string.Equals(window.ClassName, className, StringComparison.Ordinal)) &&
                (string.IsNullOrWhiteSpace(processName) || string.Equals(window.ProcessName, processName, StringComparison.OrdinalIgnoreCase))).ToList();
            if (exact.Count == 1) return exact[0];
            if (exact.Count > 1 || string.IsNullOrWhiteSpace(className) || string.IsNullOrWhiteSpace(processName)) return null;

            List<WindowInfo> stable = windows.Where(window =>
                string.Equals(window.ClassName, className, StringComparison.Ordinal) &&
                string.Equals(window.ProcessName, processName, StringComparison.OrdinalIgnoreCase)).ToList();
            return stable.Count == 1 ? stable[0] : null;
        }

        private static string RectToString(Rectangle value)
        {
            return value == Rectangle.Empty ? string.Empty : string.Format("{0},{1},{2},{3}", value.X, value.Y, value.Width, value.Height);
        }

        private static Rectangle ParseRect(string value)
        {
            string[] parts = (value ?? string.Empty).Split(',');
            int x, y, width, height;
            return parts.Length == 4 && int.TryParse(parts[0], out x) && int.TryParse(parts[1], out y) &&
                int.TryParse(parts[2], out width) && int.TryParse(parts[3], out height)
                ? new Rectangle(x, y, width, height) : Rectangle.Empty;
        }

        private static int Clamp(int value, int min, int max) { return Math.Max(min, Math.Min(max, value)); }

        private sealed class SourceEditorState
        {
            public int Index;
            public string SourceId;
            public string SourceName;
            public string ModelKey;
            public MonitorConfig Config;
            public WindowInfo TargetWindow;
            public MonitorSource ToMonitorSource() { return new MonitorSource(SourceId, SourceName, ModelKey, Config); }
        }

        private sealed class SourceListItem
        {
            public string SourceId { get; private set; }
            public string DisplayName { get; private set; }
            public SourceListItem(string sourceId, string displayName) { SourceId = sourceId; DisplayName = displayName; }
            public override string ToString() { return DisplayName; }
        }
    }
}
