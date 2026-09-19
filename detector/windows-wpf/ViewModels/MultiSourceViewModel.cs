using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Media.Imaging;
using VisionGuard.Capture;
using VisionGuard.Data;
using VisionGuard.Inference;
using VisionGuard.Models;
using VisionGuard.Services;
using VisionGuard.Utils;
using VisionGuard.Views;
using VisionGuard.Runtime;

namespace VisionGuard.ViewModels
{
    public sealed class MultiSourceViewModel : ViewModelBase, IDisposable, ICardGridHost
    {
        private readonly MultiSourceMonitorCoordinator _coordinator;
        private readonly ServerPushService _server;
        private readonly SettingsViewModel _settings;
        private SourceViewModel? _selectedSource;
        private string _sourceLimitWarning = "";
        private string _previewSelectionHint = "";
        private bool _isGlobalView;
        // 收到服务端 maxSources 之前先放开到最大值，否则本地会被默认上限卡住，用户加不了更多来源。
        private int _sourceLimit = MultiSourceMonitorCoordinator.MaximumSourceLimit;

        // ── 卡片区状态（固定网格、最多 4 个实时预览位、可拖宽度）──
        // 布局求解本身在 CardLayoutPlanner（纯计算）；这里只保存用户交互产生的状态。
        private double _inspectorPanelWidth = LoadInspectorPanelWidth();

        /// <summary>全部来源：全局来源视图与来源上限都作用在它上面。</summary>
        public ObservableCollection<SourceViewModel> Sources { get; } = new();

        /// <summary>
        /// 进入实时预览的来源（最多 <see cref="CardLayoutPlanner.MaximumVisibleCards"/> 个，按来源顺序排列）。
        /// 主视图只排布它们；不在其中的来源照常采集、推理与报警，只是不占预览位。
        /// </summary>
        public ObservableCollection<SourceViewModel> PreviewSources { get; } = new();

        public IReadOnlyList<MonitorSourceStatus> Statuses => _coordinator.Statuses;
        public SourceViewModel? SelectedSource
        {
            get => _selectedSource;
            set
            {
                if (ReferenceEquals(_selectedSource, value)) return;
                if (_selectedSource != null) _selectedSource.IsSelected = false;
                // 选中只影响右侧检查区；不再有「跳到这一页」——卡片不再分页。
                if (SetProperty(ref _selectedSource, value) && value != null) value.IsSelected = true;
            }
        }

        /// <summary>
        /// 是否处于「全局来源」模式：卡片区换成编号卡片网格，用来勾选进入实时预览的来源。
        /// 只是显示切换，采集、推理与报警都不受影响。
        /// </summary>
        public bool IsGlobalView
        {
            get => _isGlobalView;
            private set
            {
                if (!SetProperty(ref _isGlobalView, value)) return;
                OnPropertyChanged(nameof(IsPreviewView));
                OnPropertyChanged(nameof(GlobalViewToggleText));
            }
        }

        /// <summary>预览视图可见性，与全局来源模式互斥。</summary>
        public bool IsPreviewView => !_isGlobalView;

        /// <summary>切换按钮文案：进全局视图说清目的，退出说清回到哪里。</summary>
        public string GlobalViewToggleText => _isGlobalView ? "返回预览" : "全局来源";

        /// <summary>实时预览位占用情况，常驻卡片区底部。</summary>
        public string PreviewSelectionText => $"实时预览 {PreviewSources.Count}/{CardLayoutPlanner.MaximumVisibleCards}";

        /// <summary>勾选被拒绝时的提示（例如已满 4 个还想再选）；为空表示没有提示。</summary>
        public string PreviewSelectionHint
        {
            get => _previewSelectionHint;
            private set
            {
                if (!SetProperty(ref _previewSelectionHint, value)) return;
                OnPropertyChanged(nameof(HasPreviewSelectionHint));
            }
        }

        public bool HasPreviewSelectionHint => !string.IsNullOrEmpty(_previewSelectionHint);

        /// <summary>
        /// 还在实时预览里的来源数（由 <see cref="PreviewSources"/> 派生，避免两处状态各说各话）。
        /// </summary>
        public int PreviewCount => PreviewSources.Count;

        /// <summary>
        /// 右侧检查区宽度（DIP），默认取最窄的 <see cref="CardLayoutPlanner.MinimumInspectorPanelWidth"/>，
        /// 卡片区因此占满其余空间；用户拖拽分隔条后按拖拽结果持久化。
        ///
        /// 类型必须是 <see cref="GridLength"/> 而不是 double：`ColumnDefinition.Width` 是 GridLength，
        /// double 绑定不会生效——上一版 `CardsPanelWidth` 的 double 绑定就是这样静默失效的，
        /// 结果卡片区与检查区各占一半、设置里的宽度从来没生效过。
        /// </summary>
        public GridLength InspectorWidth
        {
            get => new GridLength(_inspectorPanelWidth);
            set
            {
                double clamped = Net472Compat.Clamp(value.Value,
                    CardLayoutPlanner.MinimumInspectorPanelWidth, CardLayoutPlanner.MaximumInspectorPanelWidth);
                if (Math.Abs(clamped - _inspectorPanelWidth) < 0.5) return;
                _inspectorPanelWidth = clamped;
                OnPropertyChanged(nameof(InspectorWidth));
                SettingsStore.Set(CardLayoutPlanner.InspectorPanelWidthSettingKey, (int)Math.Round(clamped));
            }
        }

        public RelayCommand ToggleGlobalViewCommand { get; }

        public string SourceLimitText => $"服务端允许最多 {_sourceLimit} 路来源";

        /// <summary>
        /// 本机可用模型集合变化后（例如刚在「全局设定」下载完模型）让每个来源重新求值：
        /// 来源页的模型下拉只列已下载模型，下载完必须立刻能看到，不需要重启程序。
        /// </summary>
        internal void RefreshModelAvailability()
        {
            foreach (var source in Sources)
            {
                source.RaiseModelOptionsChanged();
                source.MarkModelSelectionValid();
            }
        }

        /// <summary>
        /// 本机可用模型集合的当前快照（来源页模型下拉的取值来源），与具体来源无关，
        /// 因此验证脚本可以在不构造来源的情况下断言「下拉里会出现哪些模型」。
        /// </summary>
        public static string[] AvailableModelKeys()
        {
            var keys = ModelManager.ModelKeys;
            var available = new List<string>(keys.Length);
            for (int i = 0; i < keys.Length; i++)
                if (ModelManager.IsDownloaded(keys[i])) available.Add(keys[i]);
            return available.ToArray();
        }

        public MultiSourceViewModel(ServerPushService server, SettingsViewModel settings)
        {
            _server = server;
            _settings = settings;
            _coordinator = new MultiSourceMonitorCoordinator(capacityProvider: settings.GetCapacity);
            ToggleGlobalViewCommand = new RelayCommand(() => IsGlobalView = !IsGlobalView);
            EnsureLegacyBackupAndMigration();
            EnsureSourceKeyMigration();
            foreach (int index in ResolveInitialSourceIndexes())
            {
                var slot = new SourceViewModel(index, this);
                Sources.Add(slot);
                _coordinator.Add(slot.BuildSource());
            }
            RestorePreviewSelection();
            SelectedSource = PreviewSources.FirstOrDefault() ?? Sources[0];
            // server 允许为 null：来源配置的自动保存与采集目标重置需要能在没有服务端连接的进程里被单独驱动
            // 与断言（验证探针就是这么做的）。
            if (_server != null)
            {
                _server.SourceLimitReceived += (_, limit) => Application.Current.Dispatcher.Invoke(() => ApplySourceLimit(limit));
                _server.CommandReceived += (_, cmd) =>
                {
                    Application.Current.Dispatcher.BeginInvoke(() =>
                        HandleCommand(cmd.TargetSourceId, cmd.Command, cmd.RequestId));
                };
            }

            // 状态/帧事件可能在来源已被移除之后才送达（重建来源就是在 Remove 之后 Add，
            // 协调器在 Add 里立刻 RaiseStatus）；这里必须按“找不到就丢弃”处理，
            // 用 First() 会在重建来源时抛 NullReferenceException（2026-09-17 实测：点“重置”即触发）。
            _coordinator.AlertTriggered += (_, alert) =>
            {
                _server?.PushAlert(alert);
                Dispatch(() => Sources.FirstOrDefault(source => source.SourceId == alert.SourceId)?.ApplyAlert(alert));
            };
            _coordinator.StatusChanged += (_, status) => Dispatch(() =>
            {
                var slot = Sources.FirstOrDefault(s => s.SourceId == status.SourceId);
                if (slot == null) return;
                slot.ApplyStatus(status);
                RefreshSummary();
            });
            _coordinator.FrameProcessed += (_, e) =>
            {
                if (e.Frame.HasError) { e.Frame.Frame?.Dispose(); return; }
                Dispatch(() =>
                {
                    var slot = Sources.FirstOrDefault(s => s.SourceId == e.SourceId);
                    if (slot == null) { e.Frame.Frame?.Dispose(); return; }
                    using (e.Frame.Frame)
                    {
                        // 不在实时预览里的来源照常采集与推理（报警由协调器独立触发并推送），
                        // 但不做每帧位图转换、不更新检测框与缩放基准：那是纯 UI 开销，省给预览的那几路。
                        if (!slot.IsPreviewSelected)
                        {
                            slot.ApplyFrameStatsOnly(e.Frame.InferenceMs);
                            return;
                        }
                        slot.ApplyFrame(MonitorViewModel.ConvertBitmapToSource(e.Frame.Frame), e.Frame.Detections, e.Frame.InferenceMs);
                    }
                });
            };

            RefreshSummary();
        }

        /// <summary>
        /// 把协调器回调派发到界面线程；没有 Application 时（验证探针、无界面进程）直接同步执行，
        /// 让同一段逻辑在这两种宿主下都能跑。
        /// </summary>
        private static void Dispatch(Action action)
        {
            var dispatcher = Application.Current?.Dispatcher;
            if (dispatcher == null) { action(); return; }
            dispatcher.BeginInvoke(action);
        }

        /// <summary>
        /// 在服务端上限内新增一个来源：新来源默认未配置，等待用户设定采集目标。
        /// </summary>
        internal bool CanAddSource => Sources.Count < _sourceLimit;

        internal bool CanRemoveSource(SourceViewModel slot)
            => Sources.Count > 1 && !slot.IsMonitoring;

        internal void AddSource()
        {
            if (Sources.Count >= _sourceLimit) return;
            int index = 1;
            while (Sources.Any(source => source.Index == index)) index++;
            var slot = new SourceViewModel(index, this);
            Sources.Add(slot);
            _coordinator.Add(slot.BuildSource());
            // 预览位没满就顺手把新来源放进去，用户点「+」后立刻能看到它；满了则先留在全局来源里等勾选。
            if (PreviewSources.Count < CardLayoutPlanner.MaximumVisibleCards)
            {
                AddPreview(slot);
                PersistPreviewSelection();
            }
            SelectedSource = slot;
            PersistSourceIndexes();
            RefreshSummary();
        }

        /// <summary>删除当前来源，保底保留一个；运行中的来源必须先停止。</summary>
        internal void RemoveSource(SourceViewModel slot)
        {
            if (slot == null || Sources.Count <= 1) return;
            if (slot.IsMonitoring)
            {
                _sourceLimitWarning = "请先停止该来源再删除";
                RefreshSummary();
                return;
            }
            _sourceLimitWarning = "";
            _coordinator.Remove(slot.SourceId);
            RemovePreview(slot);
            Sources.Remove(slot);
            EnsurePreviewSelectionNotEmpty();
            PersistPreviewSelection();
            SelectedSource = PreviewSources.FirstOrDefault() ?? Sources[0];
            PersistSourceIndexes();
            RefreshSummary();
        }

        /// <summary>预览位被清空时补回前几个来源：主视图不能一个卡片都不剩。</summary>
        private void EnsurePreviewSelectionNotEmpty()
        {
            foreach (var slot in Sources.Take(CardLayoutPlanner.MaximumVisibleCards).ToArray())
            {
                if (PreviewSources.Count > 0) break;
                AddPreview(slot);
            }
        }

        internal InferenceBackend PreferredBackend => _settings.PreferredBackend;
        internal void Select(SourceViewModel slot) => SelectedSource = slot;

        internal void Rename(SourceViewModel slot) => _coordinator.Rename(slot.SourceId, slot.SourceName);

        internal void Reconfigure(SourceViewModel slot)
        {
            if (slot.IsMonitoring) throw new InvalidOperationException("请先停止该来源再修改配置。");
            _coordinator.Remove(slot.SourceId);
            _coordinator.Add(slot.BuildSource());
            slot.ApplyStatus(_coordinator.Statuses.First(s => s.SourceId == slot.SourceId));
            RefreshSummary();
        }

        internal void Start(SourceViewModel slot)
        {
            // 先做不会改变运行时状态的前置检查。若模型不存在，不能先重建来源：
            // 重建时排队的“就绪”状态会在异常提示之后送达，从而把实际错误伪装成“无响应”。
            var modelPath = ModelManager.GetModelPath(slot.ModelKey);
            if (!File.Exists(modelPath))
                throw new FileNotFoundException($"模型 {slot.ModelKey} 未下载，请在“全局设定 → 模型资源”中下载后重试。", modelPath);

            if (!slot.ResolveWindowForStart()) throw new InvalidOperationException(slot.StatusText);
            Reconfigure(slot);
            slot.MarkStarting();
            _coordinator.Start(slot.SourceId, modelPath);
            RefreshSummary();
        }

        internal void Stop(SourceViewModel slot) { _coordinator.Stop(slot.SourceId); RefreshSummary(); }

        public bool HandleCommand(string sourceId, string command, string requestId)
        {
            // 没有服务端连接（验证探针）时静默忽略：这些方法只负责回执与转发，不影响本地配置语义。
            if (_server == null) return false;

            // 设备级命令（无 targetSourceId）统一作用于全部来源，等价于界面上的
            // “启动已配置 / 全部停止”；不能静默只作用于第一路。
            if (string.IsNullOrWhiteSpace(sourceId))
            {
                try
                {
                    if (command == "resume") StartConfigured();
                    else if (command == "pause") StopAll();
                    else { _server.SendCommandAck(command, false, "设备不支持该命令", requestId); return false; }
                    _server.SendCommandAck(command, true, requestId: requestId);
                    return true;
                }
                catch (Exception ex) { _server.SendCommandAck(command, false, ex.Message, requestId); return false; }
            }

            var targetId = sourceId;
            var ackSourceId = targetId;
            var slot = Sources.FirstOrDefault(s => s.SourceId == targetId);
            if (slot == null) { _server.SendCommandAck(command, false, "来源不存在", requestId, ackSourceId); return false; }
            try
            {
                if (command == "resume") Start(slot);
                else if (command == "pause") Stop(slot);
                else { _server.SendCommandAck(command, false, "来源不支持该命令", requestId, ackSourceId); return false; }
                _server.SendCommandAck(command, true, requestId: requestId, targetSourceId: ackSourceId);
                return true;
            }
            catch (Exception ex) { _server.SendCommandAck(command, false, ex.Message, requestId, ackSourceId); return false; }
        }

        public bool HandleConfig(string sourceId, string key, string value, string requestId)
        {
            var targetId = string.IsNullOrWhiteSpace(sourceId) ? "default" : sourceId;
            var ackSourceId = string.IsNullOrWhiteSpace(sourceId) ? string.Empty : targetId;
            var slot = Sources.FirstOrDefault(s => s.SourceId == targetId);
            var command = $"set-config:{key}";
            if (slot == null) { _server.SendCommandAck(command, false, "来源不存在", requestId, ackSourceId); return false; }
            try
            {
                if (slot.IsMonitoring) throw new InvalidOperationException("请先停止该来源再修改配置。");
                switch (key)
                {
                    case "cooldown" when int.TryParse(value, out var cooldown) && cooldown is >= 1 and <= 300: slot.Cooldown = cooldown; break;
                    case "confidence" when float.TryParse(value, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var confidence) && confidence is >= 0.1f and <= 0.95f: slot.ThresholdPercent = (int)Math.Round(confidence * 100); break;
                    case "targetSamplingRate" when int.TryParse(value, out var fps) && fps is >= 1 and <= 5: slot.TargetFps = fps; break;
                    case "targets": slot.Targets = value; break;
                    case "modelKey" when ModelManager.ModelKeys.Contains(value): slot.ModelKey = value; break;
                    default: throw new ArgumentException("配置值无效或不支持。");
                }
                slot.ApplyAndPersist();
                _server.SendCommandAck(command, true, requestId: requestId, targetSourceId: ackSourceId);
                return true;
            }
            catch (Exception ex) { _server.SendCommandAck(command, false, ex.Message, requestId, ackSourceId); return false; }
        }

        public void RefreshSummary()
        {
            OnPropertyChanged(nameof(SourceLimitText));
            foreach (var source in Sources) source.RaiseSourceActionStates();
        }

        // ── 卡片区：画面比例、实时预览选择与全局来源视图 ──────────────────────────

        /// <summary>
        /// 本屏（= 进入实时预览的来源）的画面宽高比（宽/高）。比例不一致或还没有画面时返回 null，
        /// 此时卡片按画面区填满估算；一致时交给求解器按真实比例等比缩放，画面不会变形。
        /// </summary>
        double? ICardGridHost.UniformCardAspectRatio
        {
            get
            {
                double? ratio = null;
                foreach (var source in PreviewSources)
                {
                    if (source.FrameWidth <= 0 || source.FrameHeight <= 0) return null;
                    double current = source.FrameWidth / source.FrameHeight;
                    if (ratio.HasValue && Math.Abs(ratio.Value - current) > 0.001) return null;
                    ratio = current;
                }
                return ratio;
            }
        }

        /// <summary>
        /// 勾选/取消某个来源的实时预览位，由「全局来源」的编号卡片调用。
        /// 勾满 <see cref="CardLayoutPlanner.MaximumVisibleCards"/> 个后再勾第 5 个会被拒绝并给出提示：
        /// 不自动顶掉用户正在看的画面。
        /// </summary>
        internal void TogglePreviewSelection(SourceViewModel slot)
        {
            if (slot == null) return;
            if (slot.IsPreviewSelected)
            {
                RemovePreview(slot);
                PreviewSelectionHint = "";
                PersistPreviewSelection();
                return;
            }
            if (PreviewSources.Count >= CardLayoutPlanner.MaximumVisibleCards)
            {
                PreviewSelectionHint = $"实时预览最多 {CardLayoutPlanner.MaximumVisibleCards} 个，请先取消一个再勾选";
                return;
            }
            PreviewSelectionHint = "";
            AddPreview(slot);
            PersistPreviewSelection();
        }

        private void AddPreview(SourceViewModel slot)
        {
            if (slot == null || slot.IsPreviewSelected) return;
            if (PreviewSources.Count >= CardLayoutPlanner.MaximumVisibleCards) return;
            slot.IsPreviewSelected = true;
            // 按来源顺序插入：卡片顺序不随勾选先后跳动。
            int sourceIndex = Sources.IndexOf(slot);
            int insert = 0;
            while (insert < PreviewSources.Count && Sources.IndexOf(PreviewSources[insert]) < sourceIndex) insert++;
            PreviewSources.Insert(insert, slot);
            RaisePreviewState();
        }

        private void RemovePreview(SourceViewModel slot)
        {
            if (slot == null || !slot.IsPreviewSelected) return;
            slot.IsPreviewSelected = false;
            PreviewSources.Remove(slot);
            // 移出预览后不再保留最后一帧：位图是每路数 MB 的常驻内存，而用户已经看不到它。
            slot.ClearPreviewFrame();
            RaisePreviewState();
        }

        private void RaisePreviewState()
        {
            OnPropertyChanged(nameof(PreviewCount));
            OnPropertyChanged(nameof(PreviewSelectionText));
        }

        /// <summary>
        /// 恢复实时预览选择：设置里有记录就用记录（按来源顺序、最多 4 个），
        /// 没有记录（首次运行或升级）默认预览前 4 个来源。
        /// </summary>
        private void RestorePreviewSelection()
        {
            foreach (var slot in Sources) slot.IsPreviewSelected = false;
            PreviewSources.Clear();
            var wanted = new List<int>();
            string saved = SettingsStore.GetString(CardLayoutPlanner.PreviewSourceIndexesSettingKey, null);
            if (!string.IsNullOrWhiteSpace(saved))
            {
                foreach (string part in saved.Split(','))
                {
                    int value;
                    if (!int.TryParse(part.Trim(), out value)) continue;
                    if (!wanted.Contains(value)) wanted.Add(value);
                }
            }
            foreach (int index in wanted)
            {
                if (PreviewSources.Count >= CardLayoutPlanner.MaximumVisibleCards) break;
                AddPreview(Sources.FirstOrDefault(source => source.Index == index));
            }
            if (PreviewSources.Count == 0)
                foreach (var slot in Sources.Take(CardLayoutPlanner.MaximumVisibleCards).ToArray()) AddPreview(slot);
            PersistPreviewSelection();
            RaisePreviewState();
        }

        private void PersistPreviewSelection()
        {
            SettingsStore.Set(CardLayoutPlanner.PreviewSourceIndexesSettingKey,
                string.Join(",", PreviewSources.Select(source => source.Index)));
            SettingsStore.Save();
        }

        /// <summary>检查区宽度的持久化读取；非法值一律回落到最窄宽度。</summary>
        private static double LoadInspectorPanelWidth()
        {
            int stored = SettingsStore.GetInt(CardLayoutPlanner.InspectorPanelWidthSettingKey,
                CardLayoutPlanner.MinimumInspectorPanelWidth);
            return Net472Compat.Clamp(stored,
                CardLayoutPlanner.MinimumInspectorPanelWidth, CardLayoutPlanner.MaximumInspectorPanelWidth);
        }

        private void ApplySourceLimit(int limit)
        {
            // 上限只是上界：不超过上限时不动用户的来源数量，超出的部分才需要裁掉。
            _sourceLimit = Net472Compat.Clamp(limit, 1, MultiSourceMonitorCoordinator.MaximumSourceLimit);
            var overLimit = Sources.Where(source => source.Index > _sourceLimit).ToArray();
            if (overLimit.Length == 0)
            {
                _sourceLimitWarning = "";
                RefreshSummary();
                return;
            }
            if (Sources.Any(source => source.IsMonitoring))
            {
                _sourceLimitWarning = $"Server 上限 {_sourceLimit} 路，停止后应用";
                RefreshSummary();
                return;
            }
            _sourceLimitWarning = "";
            foreach (var slot in overLimit)
            {
                if (ReferenceEquals(SelectedSource, slot)) SelectedSource = Sources.First(source => source.Index <= _sourceLimit);
                _coordinator.Remove(slot.SourceId);
                RemovePreview(slot);
                Sources.Remove(slot);
            }
            EnsurePreviewSelectionNotEmpty();
            PersistPreviewSelection();
            PersistSourceIndexes();
            RefreshSummary();
        }

        private void StartConfigured()
        {
            foreach (var slot in Sources.Where(s => s.CanStart).ToArray())
            {
                try { Start(slot); }
                catch (Exception ex) { slot.SetError(ex.Message); break; }
            }
            RefreshSummary();
        }

        private void StopAll()
        {
            foreach (var slot in Sources.Where(s => s.IsMonitoring).ToArray()) Stop(slot);
            RefreshSummary();
        }

        public void Save() => SettingsStore.Save();

        private void EnsureLegacyBackupAndMigration()
        {
            // 早期版本已完成过这次迁移的用户不能再跑一遍，否则会用旧的单来源配置覆盖当前来源 1，
            // 因此新旧两个标记名都要认。
            if (SettingsStore.GetBool("Source.LegacyMigrationCompleted", false) ||
                SettingsStore.GetBool("Signal.LegacyMigrationCompleted", false)) return;
            var keys = new[] { "CaptureMode", "TargetWindowTitle", "WindowSubRegion", "ScreenRegion", "MaskRegions", "ConfidenceThresholdPct", "TargetFps", "AlertCooldownSeconds", "SelectedModelIndex", "WatchedClasses" };
            foreach (var key in keys) SettingsStore.Set($"Source.LegacyBackup.{key}", SettingsStore.GetString(key, string.Empty));
            SettingsStore.Set("Source.LegacyBackupCreated", true);
            SettingsStore.Set("Source.1.Name", "来源 1");
            SettingsStore.Set("Source.1.CaptureMode", SettingsStore.GetString("CaptureMode", CaptureMode.ScreenRegion.ToString()));
            SettingsStore.Set("Source.1.TargetWindowTitle", SettingsStore.GetString("TargetWindowTitle", string.Empty));
            SettingsStore.Set("Source.1.WindowSubRegion", SettingsStore.GetString("WindowSubRegion", string.Empty));
            SettingsStore.Set("Source.1.ScreenRegion", SettingsStore.GetString("ScreenRegion", string.Empty));
            SettingsStore.Set("Source.1.Masks", LegacyMasksToCompact(SettingsStore.GetString("MaskRegions", string.Empty)));
            SettingsStore.Set("Source.1.ModelKey", ModelManager.ModelKeys[Net472Compat.Clamp(SettingsStore.GetInt("SelectedModelIndex", 0), 0, ModelManager.ModelKeys.Length - 1)]);
            SettingsStore.Set("Source.1.Targets", SettingsStore.GetString("WatchedClasses", "person"));
            SettingsStore.Set("Source.1.Threshold", SettingsStore.GetInt("ConfidenceThresholdPct", 45));
            SettingsStore.Set("Source.1.Fps", Net472Compat.Clamp(SettingsStore.GetInt("TargetFps", 3), 1, 5));
            SettingsStore.Set("Source.1.Cooldown", SettingsStore.GetInt("AlertCooldownSeconds", 5));
            SettingsStore.Set("Source.1.Initialized", true);
            SettingsStore.Set("Source.LegacyMigrationCompleted", true);
            SettingsStore.Save();
        }

        private static readonly string[] SourceKeySuffixes =
        {
            "Name", "ModelKey", "Targets", "Threshold", "Fps", "Cooldown", "CaptureMode",
            "TargetWindowTitle", "TargetWindowClassName", "TargetWindowProcessName",
            "ScreenRegion", "WindowSubRegion", "Masks", "Initialized",
        };

        /// <summary>
        /// 早期设置键使用“信号”前缀，统一为“来源”后做一次性迁移；旧键保留作备份。
        /// 必须在读取任何来源键之前执行，否则会把迁移前的配置当成空配置再写回去。
        /// </summary>
        private void EnsureSourceKeyMigration()
        {
            if (SettingsStore.GetBool("Source.KeyMigrationCompleted", false)) return;
            SourceSettingsMigration.Migrate(SettingsStore.Raw, "Signal.", "Source.",
                SourceKeySuffixes, MultiSourceMonitorCoordinator.MaximumSourceLimit);
            SettingsStore.Set("Source.KeyMigrationCompleted", true);
            SettingsStore.Save();
        }

        /// <summary>
        /// 来源槽位用显式索引列表持久化：允许在服务端上限内手动增删，而不是固定等于上限。
        /// </summary>
        private IEnumerable<int> ResolveInitialSourceIndexes()
        {
            var indexes = new List<int>();
            string saved = SettingsStore.GetString("Source.Indexes", null);
            if (!string.IsNullOrWhiteSpace(saved))
            {
                foreach (string part in saved.Split(','))
                {
                    int value;
                    if (!int.TryParse(part.Trim(), out value)) continue;
                    if (value < 1 || value > MultiSourceMonitorCoordinator.MaximumSourceLimit) continue;
                    if (!indexes.Contains(value)) indexes.Add(value);
                }
            }
            if (indexes.Count == 0)
            {
                // 首次运行或从早期版本升级：按已有的来源键推断数量，一个都没有就是一个来源。
                int highest = 0;
                for (int i = 1; i <= MultiSourceMonitorCoordinator.MaximumSourceLimit; i++)
                    if (SettingsStore.GetString($"Source.{i}.Name", null) != null) highest = i;
                for (int i = 1; i <= Math.Max(1, highest); i++) indexes.Add(i);
            }
            indexes.Sort();
            return indexes;
        }

        private void PersistSourceIndexes()
        {
            SettingsStore.Set("Source.Indexes", string.Join(",", Sources.Select(source => source.Index).OrderBy(index => index)));
            SettingsStore.Save();
        }

        private static string LegacyMasksToCompact(string json)
        {
            if (string.IsNullOrWhiteSpace(json)) return string.Empty;
            var dtos = SimpleJson.Deserialize<List<MaskRegionDto>>(json);
            if (dtos == null) return string.Empty;
            return string.Join(";", dtos.Where(d => d.right > d.left && d.bottom > d.top).Select(d => string.Join(",",
                d.left.ToString("R", System.Globalization.CultureInfo.InvariantCulture), d.top.ToString("R", System.Globalization.CultureInfo.InvariantCulture),
                (d.right - d.left).ToString("R", System.Globalization.CultureInfo.InvariantCulture), (d.bottom - d.top).ToString("R", System.Globalization.CultureInfo.InvariantCulture))));
        }

        public void Dispose() => _coordinator.Dispose();
    }

    public sealed class SourceViewModel : ViewModelBase
    {
        private readonly MultiSourceViewModel _owner;
        private readonly int _index;

        /// <summary>槽位索引，也是它持久化键与 SourceId 的依据；删除其它来源不改变它。</summary>
        public int Index { get { return _index; } }
        private string _sourceName = "", _modelKey = "", _targets = "", _statusText = "未配置", _targetWindowTitle = "";
        private string _targetWindowClassName = "", _targetWindowProcessName = "", _windowResolutionError = "";
        private int _thresholdPercent, _targetFps, _cooldown;
        private bool _isMonitoring, _isSelected, _syncingTargetOptions, _isPreviewSelected;
        private CaptureMode _captureMode;
        private Rectangle _screenRegion, _windowSubRegion;
        private WindowInfo? _targetWindow;
        private SavedState _saved = null!;
        private List<string> _pendingApply = new List<string>();
        // 参数自动保存的防抖定时器：与 MainViewModel 的写法一致（500ms 内合并写入）。
        private readonly System.Windows.Threading.DispatcherTimer _autoSaveTimer = new System.Windows.Threading.DispatcherTimer
        {
            Interval = TimeSpan.FromMilliseconds(500),
        };
        private BitmapSource? _previewImage;
        private double _frameWidth, _frameHeight;
        private string _lastFrameText = "尚无画面", _inferenceText = "推理 — ms", _lastAlertText = "最后报警 —", _backendText = "后端 —";

        public string SourceId { get; }
        public string DisplayIndex => $"来源 {_index}";

        /// <summary>
        /// 「全局来源」编号卡片上的槽位编号。用 #N 而不是 DisplayIndex：卡片下面还会显示名称，
        /// 默认名称同样是「来源 N」，两行都写「来源 N」会显得重复冗余。
        /// </summary>
        public string SlotNumber => $"#{_index}";

        /// <summary>
        /// 来源页的模型下拉只列**本机已下载**的模型：
        /// 选一个没下载的模型，启动时才会在“模型未下载”上失败，属于把错误推给用户。
        /// 每次读取都重新判断（下拉或页面重新绑定时会重新求值），因此在全局设定里下载完模型后，
        /// 不需要跨页消息就能在下拉里看到它。
        /// </summary>
        public string[] ModelOptions
        {
            get
            {
                var keys = ModelManager.ModelKeys;
                var available = new List<string>(keys.Length);
                for (int i = 0; i < keys.Length; i++)
                    if (ModelManager.IsDownloaded(keys[i])) available.Add(keys[i]);

                // 选了未下载的模型时收敛到本机已有的第一个；一个都没有时保持原值并置灰（由 CanPickModel 表达）。
                if (available.Count > 0 && !available.Contains(_modelKey)) _modelKey = available[0];
                return available.ToArray();
            }
        }

        /// <summary>本机没有任何已下载模型时不可选择：先到「全局设定」下载。</summary>
        public bool CanPickModel => ModelManager.ModelKeys.Any(ModelManager.IsDownloaded);

        /// <summary>模型下拉为空：界面显示一行原因，而不是留一个空控件让人猜。</summary>
        public bool HasNoModel => !CanPickModel;

        /// <summary>模型下拉为空时的提示原因。</summary>
        public string NoModelHint => CanPickModel ? "" : "本机还没有模型：请到「全局设定 → 模型资源」下载后再选";

        /// <summary>可用模型集合或选中项发生变化后，通知界面重新求值模型相关绑定。</summary>
        internal void RaiseModelOptionsChanged()
        {
            OnPropertyChanged(nameof(ModelOptions));
            OnPropertyChanged(nameof(CanPickModel));
            OnPropertyChanged(nameof(HasNoModel));
            OnPropertyChanged(nameof(NoModelHint));
        }

        /// <summary>把选中模型收敛到本机已下载的模型上（若当前选择未下载）。</summary>
        internal void MarkModelSelectionValid()
        {
            var options = ModelOptions;   // 读取时会自动收敛未下载的选择
            if (options.Length > 0) OnPropertyChanged(nameof(ModelKey));
        }

        public ObservableCollection<DetectionItem> Detections { get; } = new();
        public ObservableCollection<DetectionClassOption> TargetOptions { get; } = new();
        public List<RectangleF> MaskRegions { get; private set; } = new();
        public string SourceName { get => _sourceName; set { if (SetProperty(ref _sourceName, value)) MarkDirty(); } }
        public string ModelKey { get => _modelKey; set { if (SetProperty(ref _modelKey, value)) MarkDirty(); } }
        public string Targets
        {
            get => _targets;
            set
            {
                var normalized = NormalizeTargets(value);
                if (!SetProperty(ref _targets, normalized)) return;
                SyncTargetOptions();
                OnPropertyChanged(nameof(TargetSummary));
                MarkDirty();
            }
        }
        public string TargetSummary
        {
            get
            {
                var selected = TargetOptions.Where(option => option.IsSelected).ToList();
                if (selected.Count == 0) return "请选择检测类别";
                if (selected.Count == 1) return selected[0].DisplayName;
                if (selected.Count == 2) return string.Join("、", selected.Select(option => option.ChineseName));
                return $"{string.Join("、", selected.Take(2).Select(option => option.ChineseName))}等 {selected.Count} 类";
            }
        }
        public int ThresholdPercent { get => _thresholdPercent; set { if (SetProperty(ref _thresholdPercent, Net472Compat.Clamp(value, 10, 95))) MarkDirty(); } }
        public int TargetFps { get => _targetFps; set { if (SetProperty(ref _targetFps, Net472Compat.Clamp(value, 1, 5))) MarkDirty(); } }
        public int Cooldown { get => _cooldown; set { if (SetProperty(ref _cooldown, Net472Compat.Clamp(value, 1, 300))) MarkDirty(); } }
        public bool IsMonitoring { get => _isMonitoring; private set { if (SetProperty(ref _isMonitoring, value)) RaiseCommandStates(); } }
        public bool IsSelected { get => _isSelected; internal set => SetProperty(ref _isSelected, value); }

        /// <summary>
        /// 是否占用了实时预览位。占用时每帧刷画面；未占用时照常采集、推理与报警，
        /// 只是不做位图转换，也不在全局来源视图之外显示画面。
        /// </summary>
        public bool IsPreviewSelected
        {
            get => _isPreviewSelected;
            internal set
            {
                if (!SetProperty(ref _isPreviewSelected, value)) return;
                OnPropertyChanged(nameof(PreviewStateText));
            }
        }

        /// <summary>全局来源的编号卡片上显示这一路当前是「实时预览」还是「仅推理」。</summary>
        public string PreviewStateText => _isPreviewSelected ? "实时预览" : "仅推理";

        /// <summary>
        /// 与「已生效配置」不一致的参数名（例如“阈值”“检测类别”）。
        /// 这些参数是冷改动：本页不做保存/撤销，改动即持久化，但要在下一次启动该来源时才生效，
        /// 所以这里只用来给出「重新启动后生效」的提示，不再拦截启动。
        /// </summary>
        public IReadOnlyList<string> PendingApplyParameters => _pendingApply;

        /// <summary>「重新启动后生效」的提示文案；参数与已生效配置一致时为空。</summary>
        public string PendingApplyText => _pendingApply.Count == 0
            ? string.Empty
            : $"已保存：{string.Join("、", _pendingApply)} · 重新启动此来源后生效";
        public bool HasPendingApply => _pendingApply.Count > 0;

        public bool CanEdit => !IsMonitoring;
        public bool CanStart => !IsMonitoring && IsReady;
        public bool IsReady => _captureMode == CaptureMode.WindowHandle
            ? _targetWindow != null && CaptureSizeConstraints.IsValid(_targetWindow.Bounds)
            : CaptureSizeConstraints.IsValid(_screenRegion);
        public string StatusText { get => _statusText; private set => SetProperty(ref _statusText, value); }
        public string TargetInfo => _captureMode == CaptureMode.WindowHandle
            ? (string.IsNullOrWhiteSpace(_targetWindowTitle) ? "未选择窗口" : $"窗口：{_targetWindowTitle}{(_windowSubRegion == Rectangle.Empty ? "" : $" · 选区 {_windowSubRegion.Width}×{_windowSubRegion.Height}")}")
            : (CaptureSizeConstraints.IsValid(_screenRegion) ? $"屏幕选区：{_screenRegion.X},{_screenRegion.Y} {_screenRegion.Width}×{_screenRegion.Height}" : "未选择屏幕区域");
        public string MaskInfo => MaskRegions.Count == 0 ? "无遮罩" : $"{MaskRegions.Count} 个遮罩";
        public BitmapSource? PreviewImage { get => _previewImage; private set => SetProperty(ref _previewImage, value); }
        public double FrameWidth { get => _frameWidth; private set => SetProperty(ref _frameWidth, value); }
        public double FrameHeight { get => _frameHeight; private set => SetProperty(ref _frameHeight, value); }
        public string LastFrameText { get => _lastFrameText; private set => SetProperty(ref _lastFrameText, value); }
        public string InferenceText { get => _inferenceText; private set => SetProperty(ref _inferenceText, value); }
        public string LastAlertText { get => _lastAlertText; private set => SetProperty(ref _lastAlertText, value); }
        public string BackendText { get => _backendText; private set => SetProperty(ref _backendText, value); }

        public RelayCommand SelectCommand { get; }
        public RelayCommand PickWindowCommand { get; }
        public RelayCommand SelectRegionCommand { get; }
        public RelayCommand ResetTargetCommand { get; }
        public RelayCommand EditMasksCommand { get; }
        public RelayCommand StartCommand { get; }
        public RelayCommand StopCommand { get; }
        public RelayCommand AddSourceCommand { get; }
        public RelayCommand RemoveSourceCommand { get; }
        public RelayCommand TogglePreviewCommand { get; }

        internal SourceViewModel(int index, MultiSourceViewModel owner)
        {
            _owner = owner; _index = index; SourceId = index == 1 ? "default" : $"signal-{index}";
            Load();
            InitializeTargetOptions();
            _saved = CaptureState();
            _autoSaveTimer.Tick += AutoSaveTick;
            SelectCommand = new RelayCommand(() => _owner.Select(this));
            PickWindowCommand = new RelayCommand(PickWindow, () => CanEdit);
            SelectRegionCommand = new RelayCommand(SelectRegion, () => CanEdit);
            // 采集目标三件套（窗口 / 选区 / 遮罩）合用一个重置：它们本来就要一起变，分开清除只会留下半套配置。
            ResetTargetCommand = new RelayCommand(ResetTarget, () => CanEdit && HasAnyTarget);
            EditMasksCommand = new RelayCommand(EditMasks, () => CanEdit && IsReady);
            StartCommand = new RelayCommand(Start, () => CanStart);
            StopCommand = new RelayCommand(() => _owner.Stop(this), () => IsMonitoring);
            AddSourceCommand = new RelayCommand(_owner.AddSource, () => _owner.CanAddSource);
            RemoveSourceCommand = new RelayCommand(() => _owner.RemoveSource(this), () => _owner.CanRemoveSource(this));
            // 预览位是否还能再勾由宿主判断：满了要给提示，所以命令本身始终可执行。
            TogglePreviewCommand = new RelayCommand(() => _owner.TogglePreviewSelection(this));
            StatusText = IsReady ? "就绪" : (!string.IsNullOrWhiteSpace(_targetWindowTitle) ? (string.IsNullOrWhiteSpace(_windowResolutionError) ? "窗口未找到" : _windowResolutionError) : "未配置");
        }

        internal void RaiseSourceActionStates()
        {
            AddSourceCommand.RaiseCanExecuteChanged();
            RemoveSourceCommand.RaiseCanExecuteChanged();
            OnPropertyChanged(nameof(AddSourceToolTip));
        }

        /// <summary>
        /// 新增按钮的提示文案：加不了时必须说清是「服务端上限」而不是静默变灰。
        /// 默认上限曾是 4，界面上完全没有提示，被误读成「布局改崩了、来源加不上」。
        /// </summary>
        public string AddSourceToolTip => _owner.CanAddSource
            ? "新增来源"
            : $"已达服务端上限：{_owner.SourceLimitText}";

        private string Prefix => $"Source.{_index}.";

        private void Load()
        {
            _sourceName = SettingsStore.GetString(Prefix + "Name", $"来源 {_index}");
            // 早期的默认名是“信号 N”，统一改成“来源 N”；用户自己起过的名字不动。
            if (_sourceName != null && _sourceName.Trim() == $"信号 {_index}") _sourceName = $"来源 {_index}";
            _modelKey = SettingsStore.GetString(Prefix + "ModelKey", ModelManager.DefaultModelKey);
            // 旧配置里的模型键可能属于另一档位（例如 Win7 上残留的 yolo26*），回落到本档位默认值。
            if (!ModelManager.IsSupported(_modelKey)) _modelKey = ModelManager.DefaultModelKey;
            _targets = NormalizeTargets(SettingsStore.GetString(Prefix + "Targets", "person"));
            _thresholdPercent = Net472Compat.Clamp(SettingsStore.GetInt(Prefix + "Threshold", 45), 10, 95);
            _targetFps = Net472Compat.Clamp(SettingsStore.GetInt(Prefix + "Fps", 3), 1, 5);
            _cooldown = Net472Compat.Clamp(SettingsStore.GetInt(Prefix + "Cooldown", 5), 1, 300);
            _captureMode = Enum.TryParse<CaptureMode>(SettingsStore.GetString(Prefix + "CaptureMode", CaptureMode.ScreenRegion.ToString()), out var mode) && mode == CaptureMode.WindowHandle ? CaptureMode.WindowHandle : CaptureMode.ScreenRegion;
            _targetWindowTitle = SettingsStore.GetString(Prefix + "TargetWindowTitle", string.Empty);
            _targetWindowClassName = SettingsStore.GetString(Prefix + "TargetWindowClassName", string.Empty);
            _targetWindowProcessName = SettingsStore.GetString(Prefix + "TargetWindowProcessName", string.Empty);
            _screenRegion = ParseRectangle(SettingsStore.GetString(Prefix + "ScreenRegion", string.Empty));
            _windowSubRegion = ParseRectangle(SettingsStore.GetString(Prefix + "WindowSubRegion", string.Empty));
            MaskRegions = ParseMasks(SettingsStore.GetString(Prefix + "Masks", string.Empty));
            ResolveWindow();
        }

        private void InitializeTargetOptions()
        {
            foreach (var englishName in CocoClassMap.EnglishNames)
            {
                TargetOptions.Add(new DetectionClassOption(englishName, CocoClassMap.EnZh[englishName], OnTargetOptionChanged));
            }
            SyncTargetOptions();
        }

        private void SyncTargetOptions()
        {
            if (TargetOptions.Count == 0) return;
            // net472 无 StringSplitOptions.TrimEntries，显式 Trim。
            var selectedNames = _targets.Split(new[] { ',' }, StringSplitOptions.RemoveEmptyEntries)
                .Select(item => item.Trim())
                .ToHashSet(StringComparer.OrdinalIgnoreCase);
            _syncingTargetOptions = true;
            try
            {
                foreach (var option in TargetOptions) option.IsSelected = selectedNames.Contains(option.EnglishName);
            }
            finally { _syncingTargetOptions = false; }
            OnPropertyChanged(nameof(TargetSummary));
        }

        private void OnTargetOptionChanged(DetectionClassOption changedOption)
        {
            if (_syncingTargetOptions) return;
            var selected = TargetOptions.Where(option => option.IsSelected).ToList();
            if (selected.Count == 0)
            {
                _syncingTargetOptions = true;
                changedOption.IsSelected = true;
                _syncingTargetOptions = false;
                return;
            }
            Targets = string.Join(",", selected.Select(option => option.EnglishName));
        }

        private static string NormalizeTargets(string? value)
        {
            var targets = (value ?? string.Empty).Split(new[] { ',' }, StringSplitOptions.RemoveEmptyEntries)
                .Select(item => item.Trim())
                .Distinct(StringComparer.OrdinalIgnoreCase);
            var normalized = string.Join(",", targets);
            return string.IsNullOrWhiteSpace(normalized) ? "person" : normalized;
        }

        internal MonitorSource BuildSource()
        {
            var config = new MonitorConfig
            {
                CaptureMode = _captureMode, CaptureRegion = _screenRegion, TargetWindowTitle = _targetWindowTitle,
                TargetWindowClassName = _targetWindowClassName, TargetWindowProcessName = _targetWindowProcessName,
                TargetWindowHandle = _targetWindow?.Handle ?? IntPtr.Zero, WindowSubRegion = _windowSubRegion,
                ConfidenceThreshold = ThresholdPercent / 100f, AlertCooldownSeconds = Cooldown, TargetFps = TargetFps,
                WatchedClasses = Targets.Split(new[] { ',' }, StringSplitOptions.RemoveEmptyEntries).Select(item => item.Trim()).ToHashSet(StringComparer.OrdinalIgnoreCase),
                MaskRegions = new List<RectangleF>(MaskRegions), SaveAlertSnapshot = true,
            };
            if (config.WatchedClasses.Count == 0) config.WatchedClasses.Add("person");
            return new MonitorSource(SourceId, string.IsNullOrWhiteSpace(SourceName) ? DisplayIndex : SourceName.Trim(), ModelKey, config, _owner.PreferredBackend);
        }

        internal bool ResolveWindowForStart()
        {
            if (_captureMode == CaptureMode.WindowHandle) { ResolveWindow(); if (_targetWindow == null) { StatusText = _windowResolutionError; OnPropertyChanged(nameof(IsReady)); return false; } }
            if (!IsReady) { StatusText = "采集目标宽度和高度必须都大于 100 像素。"; return false; }
            return true;
        }

        private void ResolveWindow()
        {
            _targetWindow = null;
            _windowResolutionError = "";
            if (_captureMode != CaptureMode.WindowHandle || string.IsNullOrWhiteSpace(_targetWindowTitle)) return;
            var main = Application.Current?.MainWindow;
            var excluded = main == null ? IntPtr.Zero : new System.Windows.Interop.WindowInteropHelper(main).Handle;
            var match = WindowMatchResolver.Resolve(
                WindowEnumerator.GetWindows(excluded),
                _targetWindowTitle,
                _targetWindowClassName,
                _targetWindowProcessName);
            _targetWindow = match.Window;
            _windowResolutionError = match.Status == WindowMatchStatus.Ambiguous
                ? "发现多个符合配置的窗口，无法安全自动重绑，请重新选择目标窗口。"
                : match.Status == WindowMatchStatus.NotFound
                    ? "目标窗口不存在、已最小化或尺寸过小，请重新选择窗口。"
                    : "";
        }

        private void PickWindow()
        {
            var main = Application.Current.MainWindow;
            var excluded = main == null ? IntPtr.Zero : new System.Windows.Interop.WindowInteropHelper(main).Handle;
            var picker = new WindowPickerWindow(excluded) { Owner = main };
            if (picker.ShowDialog() != true || picker.SelectedWindow == null || !ConfirmTargetChange()) return;
            _captureMode = CaptureMode.WindowHandle; _targetWindow = picker.SelectedWindow; _targetWindowTitle = picker.SelectedWindow.Title;
            _targetWindowClassName = picker.SelectedWindow.ClassName; _targetWindowProcessName = picker.SelectedWindow.ProcessName; _windowResolutionError = "";
            _screenRegion = Rectangle.Empty; _windowSubRegion = Rectangle.Empty; ClearMasksInternal(); NotifyTargetChanged();
        }

        private void SelectRegion()
        {
            BitmapSource? background = null;
            var windowMode = _captureMode == CaptureMode.WindowHandle && !string.IsNullOrWhiteSpace(_targetWindowTitle);
            if (windowMode && _targetWindow == null) ResolveWindow();
            if (windowMode && _targetWindow == null) { MessageBox.Show("目标窗口当前不存在，请重新选择窗口或清除目标后选择屏幕区域。", "VisionGuard", MessageBoxButton.OK, MessageBoxImage.Warning); return; }
            try { using var bitmap = windowMode ? WindowCapturer.CaptureWindow(_targetWindow!.Handle, Rectangle.Empty) : ScreenCapturer.CapturePrimaryScreen(); background = MonitorViewModel.ConvertBitmapToSource(bitmap); } catch { }
            var selector = new RegionSelectorWindow(background) { Owner = Application.Current.MainWindow };
            selector.ShowDialog();
            if (!selector.IsConfirmed || !ConfirmTargetChange()) return;
            if (windowMode) _windowSubRegion = selector.SelectedRegion;
            else { _captureMode = CaptureMode.ScreenRegion; _screenRegion = selector.SelectedRegion; _targetWindow = null; _targetWindowTitle = string.Empty; _targetWindowClassName = string.Empty; _targetWindowProcessName = string.Empty; _windowResolutionError = ""; _windowSubRegion = Rectangle.Empty; }
            ClearMasksInternal(); NotifyTargetChanged();
        }

        /// <summary>是否已配置采集目标三件套中的任意一项（窗口 / 选区 / 遮罩）。</summary>
        public bool HasAnyTarget => !string.IsNullOrWhiteSpace(_targetWindowTitle)
            || _screenRegion != Rectangle.Empty
            || _windowSubRegion != Rectangle.Empty
            || MaskRegions.Count > 0;

        /// <summary>
        /// 重置采集目标：窗口、选区、遮罩一起清空，随后立即持久化。
        /// 这三项是一个整体——换了窗口或选区，原来的遮罩坐标就失去意义，所以只提供整体重置。
        /// </summary>
        private void ResetTarget()
        {
            if (!ConfirmTargetChange()) return;
            _captureMode = CaptureMode.ScreenRegion; _targetWindow = null; _targetWindowTitle = string.Empty; _targetWindowClassName = string.Empty; _targetWindowProcessName = string.Empty; _windowResolutionError = "";
            _screenRegion = Rectangle.Empty; _windowSubRegion = Rectangle.Empty; ClearMasksInternal(); NotifyTargetChanged();
        }

        private bool ConfirmTargetChange() => MaskRegions.Count == 0 || MessageBox.Show("更换捕获目标或选区会清除当前遮罩。是否继续？", "VisionGuard", MessageBoxButton.YesNo, MessageBoxImage.Warning) == MessageBoxResult.Yes;

        private void EditMasks()
        {
            try
            {
                using var bitmap = GrabFrame();
                var editor = new MaskEditorWindow(MonitorViewModel.ConvertBitmapToSource(bitmap), MaskRegions) { Owner = Application.Current.MainWindow };
                editor.ShowDialog();
                if (!editor.IsConfirmed) return;
                MaskRegions = editor.ResultMasks; OnPropertyChanged(nameof(MaskInfo)); MarkDirty();
            }
            catch (Exception ex) { MessageBox.Show($"抓图失败：{ex.Message}", "VisionGuard", MessageBoxButton.OK, MessageBoxImage.Error); }
        }

        private Bitmap GrabFrame()
        {
            if (_captureMode == CaptureMode.WindowHandle && _targetWindow != null) return WindowCapturer.CaptureWindow(_targetWindow.Handle, _windowSubRegion);
            if (_captureMode == CaptureMode.ScreenRegion && CaptureSizeConstraints.IsValid(_screenRegion)) return ScreenCapturer.CaptureRegion(_screenRegion);
            throw new InvalidOperationException("尚未配置有效捕获目标。");
        }

        /// <summary>
        /// 把当前配置落盘并让协调器按最新配置重建来源。
        /// 保存按钮已移除：参数改动即持久化，这里只剩「需要立刻重建来源」的场景（启动前、远控改配置、目标三件套变更）。
        /// </summary>
        internal void ApplyAndPersist()
        {
            if (IsMonitoring) throw new InvalidOperationException("请先停止该来源再修改配置。");
            SourceName = string.IsNullOrWhiteSpace(SourceName) ? DisplayIndex : SourceName.Trim();
            PersistCurrent(); SettingsStore.Save(); _saved = CaptureState(); RefreshPendingApply(); _owner.Reconfigure(this);
        }

        internal void CommitSourceNameEdit()
        {
            SourceName = string.IsNullOrWhiteSpace(SourceName) ? DisplayIndex : SourceName.Trim();
            SettingsStore.Set(Prefix + "Name", SourceName);
            SettingsStore.Save();
            _saved = _saved with { SourceName = SourceName };
            RefreshPendingApply();
            if (!HasPendingApply) RefreshIdleStatus();
            _owner.Rename(this);
        }

        internal void CancelSourceNameEdit(string originalName)
        {
            SourceName = originalName;
            RefreshPendingApply();
            if (!HasPendingApply) RefreshIdleStatus();
        }

        internal void PersistCurrent()
        {
            SettingsStore.Set(Prefix + "Initialized", true); SettingsStore.Set(Prefix + "Name", SourceName);
            SettingsStore.Set(Prefix + "CaptureMode", _captureMode.ToString()); SettingsStore.Set(Prefix + "TargetWindowTitle", _targetWindowTitle);
            SettingsStore.Set(Prefix + "TargetWindowClassName", _targetWindowClassName); SettingsStore.Set(Prefix + "TargetWindowProcessName", _targetWindowProcessName);
            SettingsStore.Set(Prefix + "WindowSubRegion", FormatRectangle(_windowSubRegion)); SettingsStore.Set(Prefix + "ScreenRegion", FormatRectangle(_screenRegion));
            SettingsStore.Set(Prefix + "ModelKey", ModelKey); SettingsStore.Set(Prefix + "Targets", Targets);
            SettingsStore.Set(Prefix + "Threshold", ThresholdPercent); SettingsStore.Set(Prefix + "Fps", TargetFps); SettingsStore.Set(Prefix + "Cooldown", Cooldown);
            SettingsStore.Set(Prefix + "Masks", FormatMasks(MaskRegions));
        }

        private SavedState CaptureState() => new(SourceName, ModelKey, Targets, ThresholdPercent, TargetFps, Cooldown, _captureMode, _targetWindowTitle, _targetWindowClassName, _targetWindowProcessName, _screenRegion, _windowSubRegion, new List<RectangleF>(MaskRegions));

        /// <summary>
        /// 比较「当前值」与「已生效配置」，列出需要重新启动才生效的参数名。
        /// 采集目标三件套不参与：它们变更即重建来源、立即生效。
        /// </summary>
        private void RefreshPendingApply()
        {
            var pending = new List<string>();
            if (_saved != null)
            {
                if (ModelKey != _saved.ModelKey) pending.Add("模型");
                if (Targets != _saved.Targets) pending.Add("检测类别");
                if (ThresholdPercent != _saved.ThresholdPercent) pending.Add("阈值");
                if (TargetFps != _saved.TargetFps) pending.Add("频率");
                if (Cooldown != _saved.Cooldown) pending.Add("冷却");
            }

            bool changed = pending.Count != _pendingApply.Count;
            if (!changed)
                for (int i = 0; i < pending.Count; i++)
                    if (pending[i] != _pendingApply[i]) { changed = true; break; }
            if (!changed) return;

            _pendingApply = pending;
            OnPropertyChanged(nameof(PendingApplyParameters));
            OnPropertyChanged(nameof(PendingApplyText));
            OnPropertyChanged(nameof(HasPendingApply));
        }

        private void RefreshIdleStatus()
            => StatusText = IsReady
                ? (PreviewImage == null ? "就绪" : "已停止 · 保留最后画面")
                : (!string.IsNullOrWhiteSpace(_targetWindowTitle) ? (string.IsNullOrWhiteSpace(_windowResolutionError) ? "窗口未找到" : _windowResolutionError) : "未配置");

        private void Start() { try { _owner.Start(this); } catch (Exception ex) { SetError(ex.Message); } }
        internal void MarkStarting() => StatusText = "启动中";
        internal void SetError(string message) => StatusText = string.IsNullOrWhiteSpace(message) ? "异常" : message;
        internal void ApplyStatus(MonitorSourceStatus status)
        {
            IsMonitoring = status.IsMonitoring;
            BackendText = status.ActiveBackend == "Unavailable" ? "后端 —" : $"{status.ActiveBackend} · {status.ActualFps:0.0} FPS";
            StatusText = !string.IsNullOrWhiteSpace(status.Error)
                ? $"异常：{status.Error}"
                : !string.IsNullOrWhiteSpace(status.PerformanceWarning)
                    ? status.PerformanceWarning
                : status.IsMonitoring
                    ? "检测中"
                    : (IsReady ? (PreviewImage == null ? "就绪" : "已停止 · 保留最后画面") : (!string.IsNullOrWhiteSpace(_targetWindowTitle) ? (string.IsNullOrWhiteSpace(_windowResolutionError) ? "窗口未找到" : _windowResolutionError) : "未配置"));
        }

        internal void ApplyFrame(BitmapSource image, List<Detection> detections, long inferenceMs)
        {
            PreviewImage = image; FrameWidth = image.PixelWidth; FrameHeight = image.PixelHeight; Detections.Clear();
            foreach (var d in detections) Detections.Add(new DetectionItem { Left = d.BoundingBox.Left, Top = d.BoundingBox.Top, Width = d.BoundingBox.Width, Height = d.BoundingBox.Height, Label = $"{d.Label} {(int)(d.Confidence * 100)}%" });
            LastFrameText = $"更新 {DateTime.Now:HH:mm:ss}";
            InferenceText = $"推理 {inferenceMs} ms";
        }

        /// <summary>
        /// 未进入实时预览的来源只更新统计文字：帧、检测框与缩放基准都不碰，
        /// 因此不会产生 BitmapSource，也不会有每帧的 UI 通知风暴。
        /// </summary>
        internal void ApplyFrameStatsOnly(long inferenceMs)
        {
            LastFrameText = $"更新 {DateTime.Now:HH:mm:ss}";
            InferenceText = $"推理 {inferenceMs} ms";
        }

        /// <summary>移出实时预览时释放最后一帧：位图是每路数 MB 的常驻内存，留着也不会再显示。</summary>
        internal void ClearPreviewFrame()
        {
            PreviewImage = null;
            Detections.Clear();
            FrameWidth = 0;
            FrameHeight = 0;
            LastFrameText = "已移出实时预览 · 仍在推理";
        }

        internal void ApplyAlert(AlertEvent alert)
        {
            var target = alert.Detections.FirstOrDefault()?.Label ?? "目标";
            LastAlertText = $"最后报警 {DateTime.Now:HH:mm:ss} · {target} ×{alert.Detections.Count}";
        }

        /// <summary>采集目标三件套变更：立即持久化并重建来源（这三项本来就不经过冷改动路径）。</summary>
        private void NotifyTargetChanged()
        {
            PersistCurrent();
            SettingsStore.Save();
            _saved = CaptureState();
            RefreshPendingApply();
            StatusText = IsReady ? "就绪" : "未配置";
            OnPropertyChanged(nameof(TargetInfo));
            OnPropertyChanged(nameof(MaskInfo));
            OnPropertyChanged(nameof(HasAnyTarget));
            OnPropertyChanged(nameof(IsReady));
            OnPropertyChanged(nameof(CanStart));
            RaiseCommandStates();
            _owner.Reconfigure(this);
        }

        private void ClearMasksInternal() { MaskRegions.Clear(); OnPropertyChanged(nameof(MaskInfo)); OnPropertyChanged(nameof(HasAnyTarget)); }

        /// <summary>
        /// 参数改动即自动保存（防抖 500ms，避免文本框逐字符写盘）。
        /// 这里只写设置文件；不需要重建来源的参数改动会在下一次启动时生效，并由 PendingApplyText 提示。
        /// </summary>
        private void MarkDirty()
        {
            RefreshPendingApply();
            if (!IsMonitoring) StatusText = HasPendingApply ? PendingApplyText : (IsReady ? "就绪" : "未配置");
            _autoSaveTimer.Stop();
            _autoSaveTimer.Start();
        }

        private void AutoSaveTick(object? sender, EventArgs e)
        {
            _autoSaveTimer.Stop();
            PersistCurrent();
            SettingsStore.Save();
        }

        private void RaiseCommandStates()
        {
            OnPropertyChanged(nameof(CanEdit)); OnPropertyChanged(nameof(CanStart));
            PickWindowCommand?.RaiseCanExecuteChanged(); SelectRegionCommand?.RaiseCanExecuteChanged(); ResetTargetCommand?.RaiseCanExecuteChanged();
            EditMasksCommand?.RaiseCanExecuteChanged(); StartCommand?.RaiseCanExecuteChanged(); StopCommand?.RaiseCanExecuteChanged();
        }

        private static Rectangle ParseRectangle(string value)
        {
            var p = value.Split(',');
            return p.Length == 4 && int.TryParse(p[0], out var x) && int.TryParse(p[1], out var y) && int.TryParse(p[2], out var w) && int.TryParse(p[3], out var h) && w > 0 && h > 0 ? new Rectangle(x, y, w, h) : Rectangle.Empty;
        }
        private static string FormatRectangle(Rectangle value) => value == Rectangle.Empty ? string.Empty : $"{value.X},{value.Y},{value.Width},{value.Height}";
        private static List<RectangleF> ParseMasks(string value)
        {
            var result = new List<RectangleF>();
            foreach (var item in value.Split(new[] { ';' }, StringSplitOptions.RemoveEmptyEntries))
            {
                var p = item.Split(',');
                if (p.Length == 4 && float.TryParse(p[0], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var x) && float.TryParse(p[1], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var y)
                    && float.TryParse(p[2], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var w) && float.TryParse(p[3], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var h)
                    && x >= 0 && y >= 0 && w > 0 && h > 0 && x + w <= 1 && y + h <= 1) result.Add(new RectangleF(x, y, w, h));
            }
            return result;
        }
        private static string FormatMasks(IEnumerable<RectangleF> masks) => string.Join(";", masks.Select(r => string.Join(",", r.X.ToString("R", System.Globalization.CultureInfo.InvariantCulture), r.Y.ToString("R", System.Globalization.CultureInfo.InvariantCulture), r.Width.ToString("R", System.Globalization.CultureInfo.InvariantCulture), r.Height.ToString("R", System.Globalization.CultureInfo.InvariantCulture))));
        private sealed record SavedState(string SourceName, string ModelKey, string Targets, int ThresholdPercent, int TargetFps, int Cooldown, CaptureMode CaptureMode, string TargetWindowTitle, string TargetWindowClassName, string TargetWindowProcessName, Rectangle ScreenRegion, Rectangle WindowSubRegion, List<RectangleF> Masks);
    }

    public sealed class DetectionClassOption : ViewModelBase
    {
        private readonly Action<DetectionClassOption> _selectionChanged;
        private bool _isSelected;

        public string EnglishName { get; }
        public string ChineseName { get; }
        public string DisplayName => $"{ChineseName} · {EnglishName}";
        public bool IsSelected
        {
            get => _isSelected;
            set
            {
                if (SetProperty(ref _isSelected, value)) _selectionChanged(this);
            }
        }

        public DetectionClassOption(string englishName, string chineseName, Action<DetectionClassOption> selectionChanged)
        {
            EnglishName = englishName;
            ChineseName = chineseName;
            _selectionChanged = selectionChanged;
        }
    }
}
