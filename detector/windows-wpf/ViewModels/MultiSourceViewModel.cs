using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Media.Imaging;
using VisionGuard.Detector.Windows.Capture;
using VisionGuard.Detector.Windows.Data;
using VisionGuard.Detector.Windows.Inference;
using VisionGuard.Detector.Windows.Models;
using VisionGuard.Detector.Windows.Services;
using VisionGuard.Detector.Windows.Utils;
using VisionGuard.Detector.Windows.Views;
using VisionGuard.Detector.Windows.Runtime;

namespace VisionGuard.Detector.Windows.ViewModels
{
    public sealed class MultiSourceViewModel : ViewModelBase, IDisposable
    {
        private readonly MultiSourceMonitorCoordinator _coordinator;
        private readonly ServerPushService _server;
        private readonly SettingsViewModel _settings;
        private SourceViewModel? _selectedSource;
        private string _sourceLimitWarning = "";
        private DateTime _lastPerformanceAlertUtc = DateTime.MinValue;
        private int _sourceLimit = MultiSourceMonitorCoordinator.MaximumSourceLimit;
        private int _viewGeneration;
        public ObservableCollection<SourceViewModel> Sources { get; } = new();
        public IReadOnlyList<MonitorSourceStatus> Statuses => _coordinator.Statuses;
        public SourceViewModel? SelectedSource
        {
            get => _selectedSource;
            set
            {
                if (ReferenceEquals(_selectedSource, value)) return;
                if (_selectedSource != null) _selectedSource.IsSelected = false;
                if (SetProperty(ref _selectedSource, value) && value != null) value.IsSelected = true;
                OnPropertyChanged(nameof(HasSelectedSource));
            }
        }
        public bool HasSelectedSource => SelectedSource != null;
        public bool HasSources => Sources.Count > 0;
        public string SourceCountText => $"来源 · {Sources.Count}";
        public string SourceLimitText => $"最多 {_sourceLimit} 路来源";
        public string SourceNotice => _sourceLimitWarning;
        public bool HasSourceNotice => !string.IsNullOrEmpty(SourceNotice);
        public RelayCommand AddSourceCommand { get; private set; } = null!;

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
            _coordinator = new MultiSourceMonitorCoordinator();
            AddSourceCommand = new RelayCommand(AddSource, () => CanAddSource);
            EnsureLegacyBackupAndMigration();
            EnsureSourceKeyMigration();
            LoadConfiguredSources();
            // 独立配置探针允许不连接服务。
            if (_server != null)
            {
                _server.SourceLimitReceived += (_, limit) => Application.Current.Dispatcher.Invoke(() => ApplySourceLimit(limit));
                _server.StreamsReceived += (_, streams) =>
                {
                    int generation = _viewGeneration;
                    Dispatch(() => { if (generation == _viewGeneration) ApplyStreams(streams); });
                };
            }

            // 状态/帧事件可能在来源已被移除之后才送达（重建来源就是在 Remove 之后 Add，
            // 协调器在 Add 里立刻 RaiseStatus）；这里必须按“找不到就丢弃”处理，
            // 不允许已移除来源的迟到事件重新进入界面。
            _coordinator.AlertTriggered += (_, alert) =>
            {
                _server?.PushAlert(alert);
                int generation = _viewGeneration;
                Dispatch(() => { if (generation == _viewGeneration) Sources.FirstOrDefault(source => source.SourceId == alert.SourceId)?.ApplyAlert(alert); });
            };
            _coordinator.StatusChanged += (_, status) =>
            {
                int generation = _viewGeneration;
                Dispatch(() => {
                if (generation != _viewGeneration) return;
                var slot = Sources.FirstOrDefault(s => s.SourceId == status.SourceId);
                if (slot == null) return;
                slot.ApplyStatus(status);
                RefreshSummary();
                RaisePerformanceAlertIfNeeded();
                });
            };
            _coordinator.FrameProcessed += (_, e) =>
            {
                if (e.Frame.HasError) { e.Frame.Frame?.Dispose(); return; }
                int generation = _viewGeneration;
                Dispatch(() =>
                {
                    if (generation != _viewGeneration) { e.Frame.Frame?.Dispose(); return; }
                    var slot = Sources.FirstOrDefault(s => s.SourceId == e.SourceId);
                    if (slot == null) { e.Frame.Frame?.Dispose(); return; }
                    using (e.Frame.Frame)
                    {
                        // 可见来源与主画面刷新位图；滚出列表的来源继续推理，仅更新统计。
                        if (!slot.IsSelected && !slot.IsPreviewVisible)
                        {
                            slot.ApplyFrameStatsOnly(e.Frame.InferenceMs);
                            return;
                        }
                        slot.ApplyFrame(BitmapSourceConverter.Convert(e.Frame.Frame), e.Frame.Detections, e.Frame.InferenceMs);
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
        public void PrepareAccountChange()
        {
            _viewGeneration++;
            StopAll();
            foreach (var slot in Sources) slot.FinishEditing();
            Save();
        }
        public void ReloadAccount()
        {
            _viewGeneration++;
            foreach (var slot in Sources.ToArray()) { slot.ReleaseForAccountSwitch(); _coordinator.Remove(slot.SourceId); }
            Sources.Clear(); SelectedSource = null;
            RemoteFrameStore.Shared.Clear();
            LoadConfiguredSources(); RefreshSummary();
        }
        private void LoadConfiguredSources()
        {
            foreach (int index in ResolveInitialSourceIndexes())
            {
                var slot = new SourceViewModel(index, this);
                if (!slot.IsTargetBound) { slot.DiscardEditing(); continue; }
                Sources.Add(slot); _coordinator.Add(slot.BuildSource());
            }
            SelectedSource = Sources.FirstOrDefault();
            PersistSourceIndexes();
        }
        private void ApplyStreams(IReadOnlyList<RemoteStreamInfo> streams)
        {
            var bound = streams.Where(s => s.targetDeviceId == AppConfig.DeviceId && !string.IsNullOrWhiteSpace(s.sourceId)).ToArray();
            foreach (var removed in Sources.Where(s => s.IsRemoteStream && !bound.Any(b => b.sourceId == s.SourceId)).ToArray())
            {
                if (IsRunning(removed)) Stop(removed);
                removed.FinishEditing(); _coordinator.Remove(removed.SourceId); removed.ForgetRemoteBinding(); Sources.Remove(removed);
            }
            foreach (var stream in bound)
            {
                var slot = Sources.FirstOrDefault(s => s.SourceId == stream.sourceId);
                if (slot == null)
                {
                    int index = 1; while (Sources.Any(s => s.Index == index)) index++;
                    if (Sources.Count >= _sourceLimit) { _sourceLimitWarning = "远程镜头超过来源上限。"; continue; }
                    slot = new SourceViewModel(index, this, fresh: true); Sources.Add(slot);
                    slot.BindRemote(stream);
                    _coordinator.Add(slot.BuildSource()); PersistSourceIndexes(); SelectedSource ??= slot;
                }
                if (!stream.isStreaming && RemoteFrameStore.Shared.IsExpectedStop(stream.streamId))
                {
                    if (IsRunning(slot)) Stop(slot);
                    slot.SetError("镜头已停止推流");
                }
                else if (!stream.isStreaming) slot.SetError("镜头已断流，等待恢复");
                slot.RefreshRemoteAvailability();
            }
            PersistSourceIndexes();
            if (SelectedSource == null || !Sources.Contains(SelectedSource)) SelectedSource = Sources.FirstOrDefault();
            RefreshSummary();
        }

        internal bool CanAddSource => Sources.Count < _sourceLimit;
        internal bool CanRemoveSource(SourceViewModel slot)
            => Sources.Contains(slot) && !IsRunning(slot) && !slot.IsRemoteStream;

        internal void AddSource()
        {
            if (!CanAddSource) return;
            int index = 1; while (Sources.Any(source => source.Index == index)) index++;
            var draft = new SourceViewModel(index, this, fresh: true);
            try
            {
                var dialog = new AddSourceWindow(draft) { Owner = Application.Current.MainWindow };
                if (dialog.ShowDialog() != true || !draft.IsReady) return;
                if (!CanAddSource) throw new InvalidOperationException("已达服务端来源上限，请重新添加。");
                draft.PersistCurrent();
                _coordinator.Add(draft.BuildSource());
                Sources.Add(draft); PersistSourceIndexes(); SelectedSource = draft;
                _sourceLimitWarning = "";
            }
            catch (Exception error)
            {
                _coordinator.Remove(draft.SourceId); Sources.Remove(draft);
                SelectedSource = Sources.FirstOrDefault();
                try { PersistSourceIndexes(); } catch { }
                _sourceLimitWarning = "添加失败：" + error.Message;
            }
            finally { draft.DiscardEditing(); RefreshSummary(); }
        }

        internal void RemoveSource(SourceViewModel slot)
        {
            if (!CanRemoveSource(slot)) return;
            if (ThemedMessageBox.Show($"删除来源“{slot.SourceName}”？", "删除来源",
                    MessageBoxButton.YesNo, MessageBoxImage.Warning, "删除来源", destructive: true) != MessageBoxResult.Yes) return;
            slot.DiscardEditing(); _coordinator.Remove(slot.SourceId); Sources.Remove(slot);
            if (ReferenceEquals(SelectedSource, slot)) SelectedSource = Sources.FirstOrDefault();
            PersistSourceIndexes(); RefreshSummary();
        }

        internal InferenceBackend PreferredBackend => _settings.PreferredBackend;
        internal void Select(SourceViewModel slot) => SelectedSource = slot;

        internal void Rename(SourceViewModel slot) => _coordinator.Rename(slot.SourceId, slot.SourceName);

        private bool IsRunning(SourceViewModel slot) =>
            _coordinator.Statuses.Any(status => status.SourceId == slot.SourceId && status.IsMonitoring);

        internal void Reconfigure(SourceViewModel slot)
        {
            if (IsRunning(slot)) throw new InvalidOperationException("请先停止该来源再修改配置。");
            _coordinator.Remove(slot.SourceId);
            _coordinator.Add(slot.BuildSource());
            slot.ApplyStatus(_coordinator.Statuses.First(s => s.SourceId == slot.SourceId));
            RefreshSummary();
        }

        internal void Start(SourceViewModel slot)
        {
            if (IsRunning(slot)) return;
            // 先做不会改变运行时状态的前置检查。若模型不存在，不能先重建来源：
            // 重建时排队的“就绪”状态会在异常提示之后送达，从而把实际错误伪装成“无响应”。
            var modelPath = ModelManager.GetModelPath(slot.ModelKey);
            if (!File.Exists(modelPath))
                throw new FileNotFoundException($"模型 {slot.ModelKey} 未下载，请在“全局设置 → 推理与模型”中下载后重试。", modelPath);

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
                    if (command == "resume") StartConfigured(throwOnError: true);
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
                if (IsRunning(slot)) throw new InvalidOperationException("请先停止该来源再修改配置。");
                if (key == "confidence") {
                    if (!double.TryParse(value, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var confidence) || confidence < .1 || confidence > .95 || Math.Abs(confidence * 100 - Math.Round(confidence * 100)) > .000001) throw new ArgumentException("置信度须为 10–95%，步长 1%。");
                    value = ((int)Math.Round(confidence * 100)).ToString();
                }
                slot.CommitParameter(key, value);
                _server.SendCommandAck(command, true, requestId: requestId, targetSourceId: ackSourceId);
                return true;
            }
            catch (Exception ex) { _server.SendCommandAck(command, false, ex.Message, requestId, ackSourceId); return false; }
        }

        public void RefreshSummary()
        {
            AddSourceCommand.RaiseCanExecuteChanged();
            OnPropertyChanged(nameof(SourceLimitText));
            OnPropertyChanged(nameof(HasSources)); OnPropertyChanged(nameof(SourceCountText));
            OnPropertyChanged(nameof(SourceNotice)); OnPropertyChanged(nameof(HasSourceNotice));
            foreach (var source in Sources) source.RaiseSourceActionStates();
        }

        /// <summary>
        /// 实测帧率持续低于目标时弹一次提醒（owner 口径：任一路不足即提醒，弹窗冷却 10 分钟）。
        ///
        /// 卡片上的文字提示由 <see cref="PerformanceWarning"/> 持续表达，弹窗只负责“打断一次”，
        /// 所以这里用冷却时间而不是状态去重：持续不足时每 10 分钟再提醒一次，避免被忽略。
        /// 无界面宿主（验证探针）不弹窗，只保留状态字段供断言。
        /// </summary>
        private void RaisePerformanceAlertIfNeeded()
        {
            var insufficient = Sources.Where(source => source.IsPerformanceInsufficient).ToArray();
            if (insufficient.Length == 0) return;

            var now = DateTime.UtcNow;
            if (now - _lastPerformanceAlertUtc < TimeSpan.FromMinutes(PerformanceWatchdog.AlertCooldownMinutes)) return;
            // 先记时间再排队：状态事件每帧都会到，不先记会排队弹出多个对话框。
            _lastPerformanceAlertUtc = now;

            string detail = string.Join(Environment.NewLine, insufficient.Select(source =>
                $"{source.DisplayIndex}：目标 {source.TargetFps:0.0} FPS，实际 {source.ActualFps:0.0} FPS"));
            var dispatcher = Application.Current?.Dispatcher;
            if (dispatcher == null) return;
            dispatcher.BeginInvoke(new Action(() =>
            {
                // 应用已在关闭过程中时不能弹窗（MessageBox 自己也是窗口，会抛“窗口正在关闭”）。
                if (dispatcher.HasShutdownStarted || dispatcher.HasShutdownFinished) return;
                VisionGuard.Detector.Windows.Views.ThemedMessageBox.Show(
                    "当前设备的推理性能已达不到设定的检测频率：" + Environment.NewLine + Environment.NewLine +
                    detail + Environment.NewLine + Environment.NewLine +
                    "采集、推理与报警仍在继续，不会自动减路或降帧。" + Environment.NewLine +
                    "建议降低检测频率、减少同时运行的来源，或改用更小的模型。",
                    "视觉推理节点 · 推理性能不足", MessageBoxButton.OK, MessageBoxImage.Warning);
            }));
        }

        private void ApplySourceLimit(int limit)
        {
            // 上限只是上界：不超过上限时不动用户的来源数量，超出的部分才需要裁掉。
            _sourceLimit = Net472Compat.Clamp(limit, 1, MultiSourceMonitorCoordinator.MaximumSourceLimit);
            var overLimit = Sources.OrderBy(source => source.Index).Skip(_sourceLimit).ToArray();
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
                if (ReferenceEquals(SelectedSource, slot)) SelectedSource = Sources.FirstOrDefault(source => !overLimit.Contains(source));
                _coordinator.Remove(slot.SourceId);
                Sources.Remove(slot);
            }
            PersistSourceIndexes();
            RefreshSummary();
        }

        private void StartConfigured(bool throwOnError = false)
        {
            // 运行状态以协调器为准，界面上的 IsMonitoring 可能还在等待 Dispatcher 刷新。
            foreach (var slot in Sources.Where(s => s.IsReady).ToArray())
            {
                try { Start(slot); }
                catch (Exception ex)
                {
                    slot.SetError(ex.Message);
                    if (throwOnError) throw;
                    break;
                }
            }
            RefreshSummary();
        }

        private void StopAll()
        {
            foreach (var slot in Sources.ToArray()) Stop(slot);
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
            if (saved == null)
            {
                // 首次运行或从早期版本升级：按已有的来源键推断数量，一个都没有就是一个来源。
                int highest = 0;
                for (int i = 1; i <= MultiSourceMonitorCoordinator.MaximumSourceLimit; i++)
                    if (SettingsStore.GetString($"Source.{i}.Name", null) != null) highest = i;
                for (int i = 1; i <= highest; i++) indexes.Add(i);
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
        private bool _isMonitoring, _isSelected, _syncingTargetOptions;
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
        private string _lastFrameText = "尚无画面", _inferenceText = "— ms", _lastAlertText = "—", _backendText = "—";
        private string _statusToolTip = "";
        private double _actualFps;
        private bool _isPerformanceInsufficient;

        public string SourceId { get; private set; }
        private string _remoteStreamId = "", _remotePublisherName = "";
        public bool IsRemoteStream => _captureMode == CaptureMode.RemoteStream;
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

                return available.ToArray();
            }
        }

        /// <summary>本机没有任何已下载模型时不可选择：先到「全局设定」下载。</summary>
        public bool CanPickModel => ModelManager.ModelKeys.Any(ModelManager.IsDownloaded);

        /// <summary>模型下拉为空：界面显示一行原因，而不是留一个空控件让人猜。</summary>
        public bool HasNoModel => !CanPickModel;

        /// <summary>模型下拉为空时的提示原因。</summary>
        public string NoModelHint => !ModelManager.IsSupported(ModelKey) ? "当前模型未知，请选择已下载模型。" : !ModelManager.IsDownloaded(ModelKey) ? "当前模型不可用，请在全局设置下载或重新选择。" : "";

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
        public string SourceName { get => _sourceName; set { DisplayNamePolicy.Normalize(value); if (SetProperty(ref _sourceName, value)) MarkDirty(); } }
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
        public string ParameterSummary
        {
            get
            {
                var selected = TargetOptions.Where(x => x.IsSelected).ToList();
                string targets = string.Join("、", selected.Take(2).Select(x => x.ChineseName));
                if (selected.Count > 2) targets += $"等 {selected.Count} 类";
                return $"{SourceParametersEditorViewModel.ModelLabel(ModelKey)} · {targets} · {TargetFps} FPS";
            }
        }
        public string ParameterDetails => $"置信度 {ThresholdPercent}% · 冷却 {Cooldown} 秒";
        public string SourceTypeText => IsRemoteStream ? "远程镜头" : _captureMode == CaptureMode.WindowHandle ? "窗口采集" : "屏幕选区";
        public string ModelDisplayText => SourceParametersEditorViewModel.ModelLabel(ModelKey);
        public string TargetsDisplayText => string.Join("、", Targets.Split(',').Select(label => CocoClassMap.EnZh.TryGetValue(label, out var chinese) ? chinese : label));
        public string EditActionHint => IsMonitoring ? "请先停止此来源再修改配置" : "修改当前来源配置";
        public string StartActionHint => IsMonitoring ? "正在检测" : !IsReady ? "采集目标当前不可用，请核对目标或等待镜头恢复" : !ModelManager.IsDownloaded(ModelKey) ? "请先在全局设置中下载所选模型" : "开始检测";
        public string DeleteActionHint => IsRemoteStream ? "远程镜头的绑定由控制台管理" : IsMonitoring ? "请先停止此来源再删除" : "删除此来源";
        public bool IsPreviewVisible { get; internal set; }
        public int ThresholdPercent { get => _thresholdPercent; set { if (SetProperty(ref _thresholdPercent, Net472Compat.Clamp(value, 10, 95))) MarkDirty(); } }
        public int TargetFps { get => _targetFps; set { if (SetProperty(ref _targetFps, Net472Compat.Clamp(value, 1, 5))) MarkDirty(); } }
        public int Cooldown { get => _cooldown; set { if (SetProperty(ref _cooldown, Net472Compat.Clamp(value, 1, 300))) MarkDirty(); } }
        public bool IsMonitoring { get => _isMonitoring; private set { if (SetProperty(ref _isMonitoring, value)) { OnPropertyChanged(nameof(FpsText)); RaiseCommandStates(); } } }
        public bool IsSelected { get => _isSelected; internal set => SetProperty(ref _isSelected, value); }

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
        public bool CanStart => !IsMonitoring && IsReady && ModelManager.IsSupported(ModelKey) && ModelManager.IsDownloaded(ModelKey);
        public bool IsReady => IsRemoteStream ? !string.IsNullOrWhiteSpace(_remoteStreamId) && RemoteFrameStore.Shared.IsBound(_remoteStreamId) : _captureMode == CaptureMode.WindowHandle
            ? _targetWindow != null && CaptureSizeConstraints.IsValid(_targetWindow.Bounds)
            : CaptureSizeConstraints.IsValid(_screenRegion);
        /// <summary>已选择采集目标；窗口暂时失联时仍视为已绑定。</summary>
        public bool IsTargetBound => IsRemoteStream ? !string.IsNullOrWhiteSpace(_remoteStreamId) : _captureMode == CaptureMode.WindowHandle
            ? !string.IsNullOrWhiteSpace(_targetWindowTitle)
            : CaptureSizeConstraints.IsValid(_screenRegion);
        private bool _hasStatusError;
        public bool HasStatusError { get => _hasStatusError; private set => SetProperty(ref _hasStatusError, value); }
        public string StatusText { get => _statusText; private set { SetProperty(ref _statusText, value); HasStatusError = false; StatusToolTip = value; } }
        public string TargetInfo => IsRemoteStream ? "远程镜头：" + _remotePublisherName : _captureMode == CaptureMode.WindowHandle
            ? (string.IsNullOrWhiteSpace(_targetWindowTitle) ? "未选择窗口" : $"窗口：{_targetWindowTitle}{(_windowSubRegion == Rectangle.Empty ? "" : $" · 选区 {_windowSubRegion.Width}×{_windowSubRegion.Height}")}")
            : (CaptureSizeConstraints.IsValid(_screenRegion) ? $"屏幕选区：{_screenRegion.X},{_screenRegion.Y} {_screenRegion.Width}×{_screenRegion.Height}" : "未选择屏幕区域");

        /// <summary>
        /// 选区比例越极端，等比缩放到方形模型输入后被黑边占用的面积越多。
        /// 这只是一条选择建议：用户仍可按场景使用任意有效尺寸的来源。
        /// </summary>
        public string AspectRatioWarning
        {
            get
            {
                Rectangle target = GetConfiguredCaptureBounds();
                if (!CaptureSizeConstraints.IsValid(target)) return string.Empty;

                int longer = Math.Max(target.Width, target.Height);
                int shorter = Math.Min(target.Width, target.Height);
                if (longer <= shorter * 2) return string.Empty;

                double effectiveArea = shorter / (double)longer;
                string ratio = target.Width >= target.Height
                    ? $"{target.Width / (double)target.Height:0.#}:1"
                    : $"1:{target.Height / (double)target.Width:0.#}";
                return $"选区 {ratio}，模型有效画面约 {effectiveArea * 100:0}%；1:1 最佳，比例越极端识别越弱。";
            }
        }

        public bool HasAspectRatioWarning => !string.IsNullOrWhiteSpace(AspectRatioWarning);
        public string MaskInfo => MaskRegions.Count == 0 ? "无遮罩" : $"{MaskRegions.Count} 个遮罩";
        public BitmapSource? PreviewImage { get => _previewImage; private set => SetProperty(ref _previewImage, value); }
        public double FrameWidth { get => _frameWidth; private set => SetProperty(ref _frameWidth, value); }
        public double FrameHeight { get => _frameHeight; private set => SetProperty(ref _frameHeight, value); }
        public string LastFrameText { get => _lastFrameText; private set => SetProperty(ref _lastFrameText, value); }
        public string InferenceText { get => _inferenceText; private set => SetProperty(ref _inferenceText, value); }
        public string LastAlertText { get => _lastAlertText; private set => SetProperty(ref _lastAlertText, value); }
        public string BackendText { get => _backendText; private set => SetProperty(ref _backendText, value); }

        /// <summary>实测推理帧率（10 秒滚动窗口），由协调器的状态推送。</summary>
        public double ActualFps { get => _actualFps; private set { if (SetProperty(ref _actualFps, value)) OnPropertyChanged(nameof(FpsText)); } }
        public string FpsText => IsMonitoring ? $"{ActualFps:0.0} FPS" : "0.0 FPS";

        /// <summary>实测帧率已持续低于目标（看门狗确认）：卡片显示短提示，宿主据此弹窗。</summary>
        public bool IsPerformanceInsufficient
        {
            get => _isPerformanceInsufficient;
            private set => SetProperty(ref _isPerformanceInsufficient, value);
        }

        /// <summary>状态文本的 ToolTip：性能不足时给出完整说明，而不是标题行那句短提示。</summary>
        public string StatusToolTip { get => _statusToolTip; private set => SetProperty(ref _statusToolTip, value); }

        public RelayCommand SelectCommand { get; }
        public RelayCommand PickWindowCommand { get; }
        public RelayCommand SelectRegionCommand { get; }
        public RelayCommand EditMasksCommand { get; }
        public RelayCommand StartCommand { get; }
        public RelayCommand StopCommand { get; }
        public RelayCommand RemoveSourceCommand { get; }

        internal SourceViewModel(int index, MultiSourceViewModel owner, bool fresh = false)
        {
            _owner = owner; _index = index; SourceId = SettingsStore.GetString(Prefix + "SourceId", index == 1 ? "default" : $"signal-{index}");
            if (fresh)
            {
                SourceId = index == 1 ? "default" : $"signal-{index}";
                _sourceName = $"来源 {index}"; _modelKey = ModelManager.DefaultModelKey; _targets = "person";
                _thresholdPercent = 45; _targetFps = 3; _cooldown = 5; _captureMode = CaptureMode.ScreenRegion;
                MaskRegions = new List<RectangleF>();
            }
            else Load();
            InitializeTargetOptions();
            _saved = CaptureState();
            _autoSaveTimer.Tick += AutoSaveTick;
            SelectCommand = new RelayCommand(() => _owner.Select(this));
            PickWindowCommand = new RelayCommand(PickWindow, () => CanEdit && !IsRemoteStream);
            SelectRegionCommand = new RelayCommand(SelectRegion, () => CanEdit && !IsRemoteStream);
            EditMasksCommand = new RelayCommand(EditMasks, () => CanEdit && IsReady);
            StartCommand = new RelayCommand(Start, () => CanStart);
            StopCommand = new RelayCommand(() => _owner.Stop(this), () => IsMonitoring);
            RemoveSourceCommand = new RelayCommand(() => _owner.RemoveSource(this), () => _owner.CanRemoveSource(this));
            RefreshIdleStatus();
        }

        internal void RaiseSourceActionStates()
        {
            RemoveSourceCommand.RaiseCanExecuteChanged();
        }

        private string Prefix => $"Source.{_index}.";

        private void Load()
        {
            _sourceName = SettingsStore.GetString(Prefix + "Name", $"来源 {_index}");
            // 早期的默认名是“信号 N”，统一改成“来源 N”；用户自己起过的名字不动。
            if (_sourceName != null && _sourceName.Trim() == $"信号 {_index}") _sourceName = $"来源 {_index}";
            _modelKey = SettingsStore.GetString(Prefix + "ModelKey", ModelManager.DefaultModelKey);
            _targets = NormalizeTargets(SettingsStore.GetString(Prefix + "Targets", "person"));
            _thresholdPercent = Net472Compat.Clamp(SettingsStore.GetInt(Prefix + "Threshold", 45), 10, 95);
            _targetFps = Net472Compat.Clamp(SettingsStore.GetInt(Prefix + "Fps", 3), 1, 5);
            _cooldown = Net472Compat.Clamp(SettingsStore.GetInt(Prefix + "Cooldown", 5), 1, 300);
            _captureMode = Enum.TryParse<CaptureMode>(SettingsStore.GetString(Prefix + "CaptureMode", CaptureMode.ScreenRegion.ToString()), out var mode) && Enum.IsDefined(typeof(CaptureMode), mode) ? mode : CaptureMode.ScreenRegion;
            _remoteStreamId = SettingsStore.GetString(Prefix + "RemoteStreamId", "");
            _remotePublisherName = SettingsStore.GetString(Prefix + "RemotePublisherName", "");
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
                RemoteStreamId = _remoteStreamId,
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
            if (_captureMode == CaptureMode.WindowHandle)
            {
                ResolveWindow();
                OnPropertyChanged(nameof(AspectRatioWarning));
                OnPropertyChanged(nameof(HasAspectRatioWarning));
                if (_targetWindow == null) { StatusText = _windowResolutionError; OnPropertyChanged(nameof(IsReady)); return false; }
            }
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
            var picker = new WindowPickerWindow(excluded) { Owner = Application.Current.Windows.Cast<Window>().FirstOrDefault(window => window.IsActive) ?? main };
            if (picker.ShowDialog() != true || picker.SelectedWindow == null || !ConfirmTargetChange()) return;
            ChangeCaptureTarget(() => {
                _captureMode = CaptureMode.WindowHandle; _targetWindow = picker.SelectedWindow; _targetWindowTitle = picker.SelectedWindow.Title;
                _targetWindowClassName = picker.SelectedWindow.ClassName; _targetWindowProcessName = picker.SelectedWindow.ProcessName; _windowResolutionError = "";
                _screenRegion = Rectangle.Empty; _windowSubRegion = Rectangle.Empty; ClearMasksInternal();
            });
        }

        private void SelectRegion()
        {
            BitmapSource? background = null;
            var windowMode = _captureMode == CaptureMode.WindowHandle && !string.IsNullOrWhiteSpace(_targetWindowTitle);
            if (windowMode && _targetWindow == null) ResolveWindow();
            if (windowMode && _targetWindow == null) { VisionGuard.Detector.Windows.Views.ThemedMessageBox.Show("目标窗口当前不存在，请重新选择窗口或清除目标后选择屏幕区域。", "视觉推理节点", MessageBoxButton.OK, MessageBoxImage.Warning); return; }
            try { using var bitmap = windowMode ? WindowCapturer.CaptureWindow(_targetWindow!.Handle, Rectangle.Empty) : ScreenCapturer.CapturePrimaryScreen(); background = BitmapSourceConverter.Convert(bitmap); } catch { }
            var selector = new RegionSelectorWindow(background) { Owner = Application.Current.MainWindow };
            selector.ShowDialog();
            if (!selector.IsConfirmed || !ConfirmTargetChange()) return;
            ChangeCaptureTarget(() => {
                if (windowMode) _windowSubRegion = selector.SelectedRegion;
                else { _captureMode = CaptureMode.ScreenRegion; _screenRegion = selector.SelectedRegion; _targetWindow = null; _targetWindowTitle = string.Empty; _targetWindowClassName = string.Empty; _targetWindowProcessName = string.Empty; _windowResolutionError = ""; _windowSubRegion = Rectangle.Empty; }
                ClearMasksInternal();
            });
        }

        internal void ChangeCaptureTarget(Action change)
        {
            if (!CanEdit) throw new InvalidOperationException("请先停止此来源。");
            var previous = CaptureState(); var previousWindow = _targetWindow; var previousSaved = _saved;
            try { change(); if (!IsReady) throw new InvalidOperationException("采集配置无效。"); NotifyTargetChanged(); }
            catch
            {
                _captureMode = previous.CaptureMode; _targetWindow = previousWindow; _targetWindowTitle = previous.TargetWindowTitle;
                _targetWindowClassName = previous.TargetWindowClassName; _targetWindowProcessName = previous.TargetWindowProcessName;
                _screenRegion = previous.ScreenRegion; _windowSubRegion = previous.WindowSubRegion; MaskRegions = previous.Masks.ToList(); _saved = previousSaved;
                if (_owner.Sources.Contains(this)) { PersistCurrent(); try { SettingsStore.Save(); _owner.Reconfigure(this); } catch { } }
                OnPropertyChanged(nameof(TargetInfo)); OnPropertyChanged(nameof(MaskInfo)); OnPropertyChanged(nameof(SourceTypeText));
                throw;
            }
        }

        /// <summary>是否已配置采集目标三件套中的任意一项（窗口 / 选区 / 遮罩）。</summary>
        public bool HasAnyTarget => !string.IsNullOrWhiteSpace(_targetWindowTitle)
            || _screenRegion != Rectangle.Empty
            || _windowSubRegion != Rectangle.Empty
            || MaskRegions.Count > 0;

        private bool ConfirmTargetChange() => MaskRegions.Count == 0 || VisionGuard.Detector.Windows.Views.ThemedMessageBox.Show("更换捕获目标或选区会清除当前遮罩。是否继续？", "视觉推理节点", MessageBoxButton.YesNo, MessageBoxImage.Warning, "更换目标", destructive: true) == MessageBoxResult.Yes;

        private void EditMasks()
        {
            try
            {
                using var bitmap = GrabFrame();
                var editor = new MaskEditorWindow(BitmapSourceConverter.Convert(bitmap), MaskRegions) { Owner = Application.Current.MainWindow };
                editor.ShowDialog();
                if (!editor.IsConfirmed) return;
                MaskRegions = editor.ResultMasks; OnPropertyChanged(nameof(MaskInfo)); MarkDirty();
            }
            catch (Exception ex) { VisionGuard.Detector.Windows.Views.ThemedMessageBox.Show($"抓图失败：{ex.Message}", "视觉推理节点", MessageBoxButton.OK, MessageBoxImage.Error); }
        }

        private Bitmap GrabFrame()
        {
            if (IsRemoteStream) return RemoteFrameStore.Shared.Peek(_remoteStreamId);
            if (_captureMode == CaptureMode.WindowHandle && _targetWindow != null) return WindowCapturer.CaptureWindow(_targetWindow.Handle, _windowSubRegion);
            if (_captureMode == CaptureMode.ScreenRegion && CaptureSizeConstraints.IsValid(_screenRegion)) return ScreenCapturer.CaptureRegion(_screenRegion);
            throw new InvalidOperationException("尚未配置有效捕获目标。");
        }

        /// <summary>
        /// 把当前配置落盘并让协调器按最新配置重建来源。
        /// 参数编辑统一保存整组草稿；该入口也用于远控的单项配置。
        /// </summary>
        internal void ApplyAndPersist()
        {
            if (IsMonitoring) throw new InvalidOperationException("请先停止该来源再修改配置。");
            SourceName = DisplayNamePolicy.Normalize(SourceName);
            PersistCurrent(); SettingsStore.Save(); _saved = CaptureState(); RefreshPendingApply(); _owner.Reconfigure(this);
        }

        internal void CommitParameter(string key, string value)
        {
            if (!CanEdit) throw new InvalidOperationException("请先暂停当前来源。");
            string previous = key switch { "modelKey" => ModelKey, "targets" => Targets, "confidence" => ThresholdPercent.ToString(), "cooldown" => Cooldown.ToString(), "targetSamplingRate" => TargetFps.ToString(), _ => throw new ArgumentException("配置项无效") };
            var saved = _saved;
            void Assign(string next) {
                switch (key) {
                    case "modelKey": ModelKey = next; break;
                    case "targets": Targets = next; break;
                    case "confidence": ThresholdPercent = int.Parse(next); break;
                    case "cooldown": Cooldown = int.Parse(next); break;
                    case "targetSamplingRate": TargetFps = int.Parse(next); break;
                }
            }
            if (key == "modelKey" && (!ModelManager.IsSupported(value) || !ModelManager.IsDownloaded(value))) throw new ArgumentException("模型不可用，请先下载。");
            if (key == "targets" && (!ModelManager.IsSupported(ModelKey) || value.Length > 4096 || value.Split(',').Any(label => !CocoClassMap.EnglishNames.Contains(label)))) throw new ArgumentException("至少选择一个当前模型中的目标。");
            if (key != "targets" && key != "modelKey" && (!int.TryParse(value, out var number) || number < (key == "confidence" ? 10 : 1) || number > (key == "confidence" ? 95 : key == "cooldown" ? 300 : 5))) throw new ArgumentException("参数范围无效。");
            try { Assign(value); _autoSaveTimer.Stop(); ApplyAndPersist(); }
            catch {
                Assign(previous); _autoSaveTimer.Stop(); _saved = saved; PersistCurrent(); RefreshPendingApply();
                try { SettingsStore.Save(); _owner.Reconfigure(this); } catch (Exception error) { LogManager.StaticWarn("[Parameter] 还原失败：" + error.Message); }
                throw;
            }
        }

        internal void CommitParameters(string model, string targets, int confidence, int fps, int cooldown)
        {
            if (!CanEdit) throw new InvalidOperationException("请先停止当前来源。");
            if (!ModelManager.IsSupported(model) || !ModelManager.IsDownloaded(model)) throw new ArgumentException("模型不可用，请先下载。");
            if (string.IsNullOrWhiteSpace(targets) || targets.Length > 4096 || targets.Split(',').Any(label => !CocoClassMap.EnglishNames.Contains(label))) throw new ArgumentException("至少选择一个当前模型中的目标。");
            if (confidence < 10 || confidence > 95 || fps < 1 || fps > 5 || cooldown < 1 || cooldown > 300) throw new ArgumentException("参数范围无效。");
            var previous = CaptureState(); var saved = _saved;
            void Assign(string nextModel, string nextTargets, int nextConfidence, int nextFps, int nextCooldown)
            { ModelKey = nextModel; Targets = nextTargets; ThresholdPercent = nextConfidence; TargetFps = nextFps; Cooldown = nextCooldown; }
            try { Assign(model, targets, confidence, fps, cooldown); _autoSaveTimer.Stop(); ApplyAndPersist(); }
            catch
            {
                Assign(previous.ModelKey, previous.Targets, previous.ThresholdPercent, previous.TargetFps, previous.Cooldown);
                _autoSaveTimer.Stop(); _saved = saved; PersistCurrent(); RefreshPendingApply();
                try { SettingsStore.Save(); _owner.Reconfigure(this); } catch (Exception error) { LogManager.StaticWarn("[Parameter] 还原失败：" + error.Message); }
                throw;
            }
        }

        internal void CommitSourceNameEdit()
        {
            SourceName = DisplayNamePolicy.Normalize(SourceName);
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
            SettingsStore.Set(Prefix + "SourceId", SourceId);
            SettingsStore.Set(Prefix + "RemoteStreamId", _remoteStreamId);
            SettingsStore.Set(Prefix + "RemotePublisherName", _remotePublisherName);
            SettingsStore.Set(Prefix + "Initialized", true); SettingsStore.Set(Prefix + "Name", DisplayNamePolicy.IsValid(SourceName) ? SourceName.Trim() : _saved?.SourceName ?? DisplayIndex);
            SettingsStore.Set(Prefix + "CaptureMode", _captureMode.ToString()); SettingsStore.Set(Prefix + "TargetWindowTitle", _targetWindowTitle);
            SettingsStore.Set(Prefix + "TargetWindowClassName", _targetWindowClassName); SettingsStore.Set(Prefix + "TargetWindowProcessName", _targetWindowProcessName);
            SettingsStore.Set(Prefix + "WindowSubRegion", FormatRectangle(_windowSubRegion)); SettingsStore.Set(Prefix + "ScreenRegion", FormatRectangle(_screenRegion));
            SettingsStore.Set(Prefix + "ModelKey", ModelKey); SettingsStore.Set(Prefix + "Targets", Targets);
            SettingsStore.Set(Prefix + "Threshold", ThresholdPercent); SettingsStore.Set(Prefix + "Fps", TargetFps); SettingsStore.Set(Prefix + "Cooldown", Cooldown);
            SettingsStore.Set(Prefix + "Masks", FormatMasks(MaskRegions));
        }

        private SavedState CaptureState() => new(SourceName, ModelKey, Targets, ThresholdPercent, TargetFps, Cooldown, _captureMode, _targetWindowTitle, _targetWindowClassName, _targetWindowProcessName, _screenRegion, _windowSubRegion, new List<RectangleF>(MaskRegions));
        internal void BindRemote(RemoteStreamInfo stream)
        {
            SourceId = stream.sourceId; _remoteStreamId = stream.streamId; _remotePublisherName = stream.publisherName;
            _captureMode = CaptureMode.RemoteStream; _sourceName = string.IsNullOrWhiteSpace(stream.sourceName) ? stream.publisherName : stream.sourceName;
            _targetWindow = null; _screenRegion = Rectangle.Empty; _windowSubRegion = Rectangle.Empty; ClearMasksInternal();
            PersistCurrent(); SettingsStore.Save(); _saved = CaptureState();
            OnPropertyChanged(nameof(SourceId)); OnPropertyChanged(nameof(SourceName)); OnPropertyChanged(nameof(TargetInfo));
            OnPropertyChanged(nameof(IsRemoteStream)); OnPropertyChanged(nameof(IsReady)); OnPropertyChanged(nameof(CanStart));
            RaiseCommandStates();
        }
        internal void DiscardEditing() { _autoSaveTimer.Stop(); ClearPreviewFrame(); }
        internal void FinishEditing() { _autoSaveTimer.Stop(); PersistCurrent(); SettingsStore.Save(); ClearPreviewFrame(); }
        internal void ReleaseForAccountSwitch() { _autoSaveTimer.Stop(); ClearPreviewFrame(); }
        internal void ForgetRemoteBinding()
        {
            SettingsStore.Set(Prefix + "SourceId", _index == 1 ? "default" : $"signal-{_index}");
            SettingsStore.Set(Prefix + "RemoteStreamId", ""); SettingsStore.Set(Prefix + "RemotePublisherName", "");
            SettingsStore.Set(Prefix + "CaptureMode", CaptureMode.ScreenRegion.ToString()); SettingsStore.Set(Prefix + "ScreenRegion", "");
            SettingsStore.Set(Prefix + "Name", $"来源 {_index}"); SettingsStore.Set(Prefix + "Masks", ""); SettingsStore.Save();
        }
        internal void RefreshRemoteAvailability()
        {
            OnPropertyChanged(nameof(IsReady)); OnPropertyChanged(nameof(CanStart)); RaiseCommandStates();
            if (!IsMonitoring && RemoteFrameStore.Shared.IsBound(_remoteStreamId) && !RemoteFrameStore.Shared.IsExpectedStop(_remoteStreamId)) StatusText = "远程镜头已绑定 · 可开始推理";
        }

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
                : (IsRemoteStream && IsTargetBound ? "镜头暂不可用 · 等待恢复" : !string.IsNullOrWhiteSpace(_targetWindowTitle) ? (string.IsNullOrWhiteSpace(_windowResolutionError) ? "窗口未找到" : _windowResolutionError) : "未配置");

        private void Start() { try { _owner.Start(this); } catch (Exception ex) { SetError(ex.Message); } }
        internal void MarkStarting() => StatusText = "启动中";
        internal void SetError(string message) { StatusText = string.IsNullOrWhiteSpace(message) ? "异常" : message; HasStatusError = true; }
        internal void ApplyStatus(MonitorSourceStatus status)
        {
            IsMonitoring = status.IsMonitoring;
            ActualFps = status.ActualFps;
            // 只有“持续不足”达到看门狗的持续时间阈值后 PerformanceWarning 才有文案，
            // 因此直接用它作为“已确认性能不足”的标记，卡片与弹窗共用同一个判据。
            IsPerformanceInsufficient = !string.IsNullOrWhiteSpace(status.PerformanceWarning);
            BackendText = status.ActiveBackend == "Unavailable" ? "—" : status.ActiveBackend;
            StatusText = !string.IsNullOrWhiteSpace(status.Error)
                ? $"异常：{status.Error}"
                : IsPerformanceInsufficient
                    // 标题行空间有限，这里只放短提示；完整说明进 ToolTip 与弹窗。
                    ? $"性能不足 {status.ActualFps:0.0}/{status.TargetFps:0.0} FPS"
                : status.IsMonitoring
                    ? "检测中"
                    : (IsReady ? (PreviewImage == null ? "就绪" : "已停止 · 保留最后画面") : (IsRemoteStream && IsTargetBound ? "镜头暂不可用 · 等待恢复" : !string.IsNullOrWhiteSpace(_targetWindowTitle) ? (string.IsNullOrWhiteSpace(_windowResolutionError) ? "窗口未找到" : _windowResolutionError) : "未配置"));
            HasStatusError = !string.IsNullOrWhiteSpace(status.Error);
            StatusToolTip = HasStatusError ? StatusText : IsPerformanceInsufficient ? status.PerformanceWarning : StatusText;
        }

        internal void ApplyFrame(BitmapSource image, List<Detection> detections, long inferenceMs)
        {
            PreviewImage = image; FrameWidth = image.PixelWidth; FrameHeight = image.PixelHeight; Detections.Clear();
            foreach (var d in detections) Detections.Add(new DetectionItem { Left = d.BoundingBox.Left, Top = d.BoundingBox.Top, Width = d.BoundingBox.Width, Height = d.BoundingBox.Height, Label = $"{d.Label} {(int)(d.Confidence * 100)}%" });
            LastFrameText = $"更新 {DateTime.Now:HH:mm:ss}";
            InferenceText = $"{inferenceMs} ms";
        }

        /// <summary>
        /// 滚出列表的非选中来源只更新统计文字：帧、检测框与缩放基准都不碰，
        /// 因此不会产生 BitmapSource，也不会有每帧的 UI 通知风暴。
        /// </summary>
        internal void ApplyFrameStatsOnly(long inferenceMs)
        {
            LastFrameText = $"更新 {DateTime.Now:HH:mm:ss}";
            InferenceText = $"{inferenceMs} ms";
        }

        /// <summary>配置、账号或来源生命周期结束时释放画面。</summary>
        internal void ClearPreviewFrame()
        {
            PreviewImage = null;
            Detections.Clear();
            FrameWidth = 0;
            FrameHeight = 0;
            LastFrameText = "暂无画面";
        }

        internal void ApplyAlert(AlertEvent alert)
        {
            var target = alert.Detections.FirstOrDefault()?.Label ?? "目标";
            LastAlertText = $"{DateTime.Now:HH:mm:ss} · {target} ×{alert.Detections.Count}";
        }

        /// <summary>采集目标三件套变更：立即持久化并重建来源（这三项本来就不经过冷改动路径）。</summary>
        private void NotifyTargetChanged()
        {
            bool registered = _owner.Sources.Contains(this);
            if (registered) { PersistCurrent(); SettingsStore.Save(); }
            _saved = CaptureState();
            RefreshPendingApply();
            StatusText = IsReady ? "就绪" : "未配置";
            OnPropertyChanged(nameof(SourceTypeText));
            OnPropertyChanged(nameof(TargetInfo));
            OnPropertyChanged(nameof(IsRemoteStream));
            OnPropertyChanged(nameof(AspectRatioWarning));
            OnPropertyChanged(nameof(HasAspectRatioWarning));
            OnPropertyChanged(nameof(MaskInfo));
            OnPropertyChanged(nameof(HasAnyTarget));
            OnPropertyChanged(nameof(IsReady));
            OnPropertyChanged(nameof(CanStart));
            RaiseCommandStates();
            if (registered) _owner.Reconfigure(this);
        }

        private void ClearMasksInternal() { MaskRegions.Clear(); OnPropertyChanged(nameof(MaskInfo)); OnPropertyChanged(nameof(HasAnyTarget)); }

        private Rectangle GetConfiguredCaptureBounds()
        {
            if (_captureMode == CaptureMode.WindowHandle)
                return _windowSubRegion != Rectangle.Empty ? _windowSubRegion : _targetWindow?.Bounds ?? Rectangle.Empty;
            return _screenRegion;
        }

        /// <summary>
        /// 参数改动即自动保存（防抖 500ms，避免文本框逐字符写盘）。
        /// 这里只写设置文件；不需要重建来源的参数改动会在下一次启动时生效，并由 PendingApplyText 提示。
        /// </summary>
        private void MarkDirty()
        {
            if (!_owner.Sources.Contains(this)) return;
            OnPropertyChanged(nameof(ParameterSummary));
            OnPropertyChanged(nameof(ParameterDetails));
            OnPropertyChanged(nameof(ModelDisplayText)); OnPropertyChanged(nameof(TargetsDisplayText));
            OnPropertyChanged(nameof(StartActionHint));
            OnPropertyChanged(nameof(EditActionHint)); OnPropertyChanged(nameof(DeleteActionHint));
            RefreshPendingApply();
            if (!IsMonitoring) StatusText = HasPendingApply ? PendingApplyText : (IsReady ? "就绪" : "未配置");
            _autoSaveTimer.Stop();
            _autoSaveTimer.Start();
        }

        private void AutoSaveTick(object? sender, EventArgs e)
        {
            _autoSaveTimer.Stop();
            if (!_owner.Sources.Contains(this)) { RefreshIdleStatus(); return; }
            PersistCurrent();
            SettingsStore.Save();
        }

        private void RaiseCommandStates()
        {
            OnPropertyChanged(nameof(CanEdit)); OnPropertyChanged(nameof(CanStart));
            OnPropertyChanged(nameof(StartActionHint));
            OnPropertyChanged(nameof(EditActionHint)); OnPropertyChanged(nameof(DeleteActionHint));
            PickWindowCommand?.RaiseCanExecuteChanged(); SelectRegionCommand?.RaiseCanExecuteChanged();
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
