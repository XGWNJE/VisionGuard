// Form1.Server.cs — 设置持久化 + 服务器推送事件 + 远程配置
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Windows.Forms;
using VisionGuard.Capture;
using VisionGuard.Models;
using VisionGuard.Services;
using VisionGuard.Utils;

namespace VisionGuard
{
    public partial class Form1
    {
        // ════════════════════════════════════════════════════════════
        // 设置持久化
        // ════════════════════════════════════════════════════════════

        private void LoadSettings()
        {
            SettingsStore.Load();
            // 必须在读取任何来源键之前完成“信号 → 来源”的键名迁移。
            EnsureSourceKeyMigration();
            _cpuCapacity = Math.Max(1, Math.Min(MultiSourceMonitorCoordinator.MaximumSourceLimit,
                SettingsStore.GetInt("WinForms.CpuCapacity", MultiSourceMonitorCoordinator.DefaultSourceLimit)));
            if (_numCpuCapacity != null) _numCpuCapacity.Value = _cpuCapacity;
            _sourceId = EnsureSourceId();
            _sourceName = SettingsStore.GetString("WinForms.Source.1.Name", "来源 1");

            // 阈值 / 参数
            _trkThreshold.Value = Math.Max(_trkThreshold.Minimum,
                Math.Min(_trkThreshold.Maximum, SettingsStore.GetInt("ConfidenceThresholdPct", 45)));
            _lblThreshold.Text = $"{_trkThreshold.Value}%";

            _sliderSamplingRate.Value = Math.Max(_sliderSamplingRate.Minimum,
                Math.Min(_sliderSamplingRate.Maximum, SettingsStore.GetInt("TargetFps", 3)));
            _lblSamplingRate.Text = $"{_sliderSamplingRate.Value} 次/秒";

            _sliderCooldown.Value = Math.Max(_sliderCooldown.Minimum,
                Math.Min(_sliderCooldown.Maximum, SettingsStore.GetInt("AlertCooldownSeconds", 5)));
            _lblCooldown.Text = $"{_sliderCooldown.Value} 秒";

            // 监控目标（6 类 CheckBox）
            HashSet<string> watched = SettingsStore.GetStringList("WatchedClasses");
            for (int i = 0; i < _targetClassKeys.Length; i++)
                _targetListBox.SetItemChecked(i, watched.Contains(_targetClassKeys[i]));
            if (watched.Count == 0)
                for (int i = 0; i < _targetClassKeys.Length; i++)
                    _targetListBox.SetItemChecked(i, _targetClassKeys[i] == "person");

            // 捕获模式
            string modeStr = SettingsStore.GetString("CaptureMode", CaptureMode.ScreenRegion.ToString());
            if (Enum.TryParse(modeStr, out CaptureMode mode) && mode == CaptureMode.WindowHandle)
            {
                string title = SettingsStore.GetString("TargetWindowTitle", string.Empty);
                if (!string.IsNullOrEmpty(title))
                {
                    var windows = WindowEnumerator.GetWindows(Handle);
                    List<WindowInfo> matches = windows.Where(w =>
                        w.Title.Equals(title, StringComparison.OrdinalIgnoreCase)).ToList();
                    WindowInfo found = matches.Count == 1 ? matches[0] : null;

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

            // 遮罩区域（相对坐标 JSON 数组）
            _maskRegions = ParseMasksJson(SettingsStore.GetString("MaskRegions", string.Empty));
            UpdateMaskInfoLabel();

            // 服务器页：恢复设备名
            _txtDeviceName.Text = SettingsStore.GetString("DeviceName", Environment.MachineName);

            // 模型选择
            string savedModel = SettingsStore.GetString("SelectedModel", "yolov5nu_320");
            _selectedModel = savedModel;
            string[] modelKeys = { "yolov5nu_320","yolov5nu_640","yolov5su_320","yolov5su_640",
                                   "yolov5mu_320","yolov5mu_640" };
            for (int i = 0; i < modelKeys.Length; i++)
                if (modelKeys[i] == savedModel) { _cmbModel.SelectedIndex = i; break; }

            InitializeMultiSource();

            // 启动时自动连接（服务器地址/Key 已硬编码）
            {
                string deviceId = EnsureDeviceId();
                string serverUrl = ResolveServerUrlForCurrentSystem();
                WireServerPushEvents();
                _serverPushService.Configure(
                    serverUrl,
                    ServerApiKey,
                    deviceId,
                    _txtDeviceName.Text.Trim());
                _log.Info("[Server] 自动连接中…");
            }

            // 启动心跳定时器（3秒）—— 连接建立后立即开始，无论监控是否运行
            _heartbeatTimer = new System.Windows.Forms.Timer { Interval = 3000 };
            _heartbeatTimer.Tick += (s, ev) => UpdateServerHeartbeatParams();
            _heartbeatTimer.Start();
        }

        private string ResolveServerUrlForCurrentSystem()
        {
            string overrideUrl = Environment.GetEnvironmentVariable("VISIONGUARD_SERVER_URL");
            if (!string.IsNullOrWhiteSpace(overrideUrl)) return overrideUrl.TrimEnd('/');
            bool enabled = SettingsStore.GetBool("UseLegacyTlsTunnel", false);
            if (!enabled)
                return ServerUrl;

            if (_legacyTlsTunnelService.TryStart())
            {
                _log.Info("[LegacyTlsTunnel] enabled, server url switched to local tunnel");
                return LegacyTlsTunnelService.LocalServerUrl;
            }

            _log.Warn("[LegacyTlsTunnel] unavailable, fallback to direct server url");
            return ServerUrl;
        }

        private void SaveSettings()
        {
            SettingsStore.Set("ConfidenceThresholdPct", _trkThreshold.Value);
            SettingsStore.Set("TargetFps",              _sliderSamplingRate.Value);
            SettingsStore.Set("AlertCooldownSeconds",   _sliderCooldown.Value);

            // 监控目标
            var watched = new List<string>();
            for (int i = 0; i < _targetClassKeys.Length; i++)
                if (_targetListBox.GetItemChecked(i))
                    watched.Add(_targetClassKeys[i]);
            SettingsStore.Set("WatchedClasses", string.Join(",", watched));

            // 遮罩区域（相对坐标，JSON 数组）
            SettingsStore.Set("MaskRegions", MasksToJson(_maskRegions));

            // 服务器设置：只保存设备名
            SettingsStore.Set("DeviceName", _txtDeviceName.Text.Trim());
            SettingsStore.Set("SelectedModel", _selectedModel);
            SettingsStore.Set("WinForms.CpuCapacity", _cpuCapacity);

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

            SaveAllSourceSettings();

            SettingsStore.Save();
        }

        // ── 服务器设置辅助 ────────────────────────────────────────────

        /// <summary>收集当前已勾选的监控目标类名（逗号分隔）</summary>
        private string GetWatchedClassesString()
        {
            var list = new System.Collections.Generic.List<string>();
            for (int i = 0; i < _targetClassKeys.Length; i++)
                if (_targetListBox.GetItemChecked(i))
                    list.Add(_targetClassKeys[i]);
            return string.Join(",", list);
        }

        /// <summary>首次调用时自动生成 DeviceId 并持久化，用户不可见</summary>
        private string EnsureDeviceId()
        {
            string id = SettingsStore.GetString("DeviceId", string.Empty);
            if (string.IsNullOrEmpty(id))
            {
                id = Guid.NewGuid().ToString();
                SettingsStore.Set("DeviceId", id);
                SettingsStore.Save();
            }
            return id;
        }

        private string EnsureSourceId()
        {
            string id = SettingsStore.GetString("WinForms.Source.1.Id", string.Empty);
            if (string.IsNullOrWhiteSpace(id))
            {
                id = "default";
                SettingsStore.Set("WinForms.Source.1.Id", id);
                SettingsStore.Set("WinForms.Source.1.Name", "来源 1");
                SettingsStore.Save();
            }
            return id;
        }

        private void WireServerPushEvents()
        {
            _serverPushService.ConnectionStateChanged += (s, state) =>
            {
                BeginInvoke(new Action(() =>
                {
                    switch (state)
                    {
                        case "connected":
                            _tsInferMs.Text = "服务器：已连接";
                            _lblConnState.Text      = "● 已连接";
                            _lblConnState.ForeColor = Color.LimeGreen;
                            _lblConnDetail.Text     = "WebSocket 已就绪，报警推送正常";
                            _lblConnDetail.ForeColor = Color.FromArgb(150, 255, 150);
                            _btnRetry.Enabled = true;
                            break;
                        case "connecting":
                            _tsInferMs.Text = "服务器：连接中";
                            _lblConnState.Text      = "◌ 连接中…";
                            _lblConnState.ForeColor = Color.Goldenrod;
                            _lblConnDetail.Text     = "正在连接服务器，请稍候…";
                            _lblConnDetail.ForeColor = Color.Goldenrod;
                            _btnRetry.Enabled = false;
                            break;
                        default:  // disconnected
                            _tsInferMs.Text = "服务器：未连接";
                            _lblConnState.Text      = "● 未连接";
                            _lblConnState.ForeColor = Color.Gray;
                            _lblConnDetail.Text     = "连接断开，将自动重连  ·  点击「手动重试」立即重连";
                            _lblConnDetail.ForeColor = Color.DimGray;
                            _btnRetry.Enabled = true;
                            break;
                    }
                }));
            };

            _serverPushService.CommandReceived += (s, cmd) =>
            {
                BeginInvoke(new Action(() =>
                {
                    if (!string.IsNullOrWhiteSpace(cmd.TargetSourceId) && !HasSource(cmd.TargetSourceId))
                    {
                        _serverPushService.SendCommandAck(cmd.Command, false, "目标来源不存在", cmd.RequestId, cmd.TargetSourceId);
                        return;
                    }
                    // 设备级命令（无来源标识）作用于全部来源，等价于“启动已配置 / 全部停止”。
                    if (string.IsNullOrWhiteSpace(cmd.TargetSourceId))
                    {
                        switch (cmd.Command)
                        {
                            case "pause":
                                StopAllSources();
                                _serverPushService.SendCommandAck(cmd.Command, true, "已向全部来源下发停止，结果见各来源状态", cmd.RequestId);
                                break;

                            case "resume":
                                StartConfiguredSources();
                                _serverPushService.SendCommandAck(cmd.Command, true, "已向全部来源下发启动，结果见各来源状态", cmd.RequestId);
                                break;

                            case "stop-alarm":
                                _serverPushService.SendCommandAck(cmd.Command, false, "检测端无本地报警功能", cmd.RequestId);
                                break;

                            default:
                                _serverPushService.SendCommandAck(cmd.Command, false, "设备不支持该命令", cmd.RequestId);
                                break;
                        }
                        UpdateServerHeartbeatParams();
                        _serverPushService.SendHeartbeatNow();
                        return;
                    }
                    switch (cmd.Command)
                    {
                        case "pause":
                            StopMonitor(remote: true, requestId: cmd.RequestId, targetSourceId: cmd.TargetSourceId);
                            break;

                        case "resume":
                            StartMonitor(remote: true, requestId: cmd.RequestId, targetSourceId: cmd.TargetSourceId);
                            break;

                        case "stop-alarm":
                            _serverPushService.SendCommandAck(cmd.Command, false, "检测端无本地报警功能", cmd.RequestId, cmd.TargetSourceId);
                            break;
                    }
                    UpdateServerHeartbeatParams();
                    _serverPushService.SendHeartbeatNow();
                }));
            };

            _serverPushService.SetConfigReceived += (s, kv) =>
            {
                BeginInvoke(new Action(() =>
                {
                    if (!string.IsNullOrWhiteSpace(kv.TargetSourceId) && !HasSource(kv.TargetSourceId))
                    {
                        _serverPushService.SendCommandAck("set-config:" + kv.Key, false, "目标来源不存在", kv.RequestId, kv.TargetSourceId);
                        return;
                    }
                    ApplyRemoteConfig(kv.Key, kv.Value, kv.RequestId, kv.TargetSourceId);
                    UpdateServerHeartbeatParams();
                    _serverPushService.SendHeartbeatNow();
                }));
            };

            _serverPushService.SourceLimitReceived += (s, limit) =>
            {
                BeginInvoke(new Action(() =>
                {
                    _serverSourceLimit = limit;
                    _monitorCoordinator.UpdateSourceLimit(limit);
                    UpdateSourceButtons();
                    _log.Info($"[Server] 来源数量上限 → {limit}");
                }));
            };
        }

        private void UpdateServerHeartbeatParams()
        {
            CaptureCurrentSourceState();
            IList<MonitorSourceStatus> statuses = _monitorCoordinator.Statuses;
            object[] sources = statuses.Select(status =>
            {
                SourceEditorState state = FindSourceState(status.SourceId);
                return (object)status.ToHeartbeatPayload(state == null ? new MonitorConfig() : state.Config);
            }).ToArray();
            MonitorSourceStatus sourceStatus = CurrentSourceStatus;
            _serverPushService.UpdateHeartbeatParams(
                isMonitoring: statuses.Any(status => status.IsMonitoring),
                isReady: statuses.Any(status => status.IsReady),
                cooldown: _sliderCooldown.Value,
                confidence: _trkThreshold.Value / 100f,
                targets: GetWatchedClassesString(),
                targetSamplingRate: _sliderSamplingRate.Value,
                modelKey: _selectedModel,
                modelOptions: Utils.ModelManager.ModelKeys,
                canSwitchModelWhileMonitoring: false,
                sources: sources);
        }


        /// <summary>
        /// 应用 Android 端下发的参数调整命令（set-config）。
        /// 支持的 key：cooldown / confidence / targets / targetSamplingRate / modelKey
        /// </summary>
        private void ApplyRemoteConfig(string key, string value, string requestId = "", string targetSourceId = "")
        {
            string commandSourceId = ResolveCommandSourceId(targetSourceId, defaultToFirst: true);
            if (!string.Equals(commandSourceId, _sourceId, StringComparison.Ordinal))
            {
                string error;
                bool applied = TryApplyRemoteConfigToSource(commandSourceId, key, value, out error);
                _serverPushService.SendCommandAck("set-config:" + key, applied, error, requestId, targetSourceId);
                if (applied)
                {
                    SaveSettings();
                    _log.Info("[Server] 已更新来源 " + commandSourceId + " 的 " + key);
                }
                return;
            }
            switch (key)
            {
                case "cooldown":
                    if (int.TryParse(value, out int cd) && cd >= 1 && cd <= 300)
                    {
                        _sliderCooldown.Value = Math.Max(_sliderCooldown.Minimum,
                            Math.Min(_sliderCooldown.Maximum, cd));
                        _lblCooldown.Text = $"{_sliderCooldown.Value} 秒";
                        // 如果正在监控，实时更新 MonitorService 的配置
                        if (IsCurrentSourceStarted)
                            UpdateCurrentSourceConfig();
                        _serverPushService.SendCommandAck("set-config:cooldown", true, requestId: requestId, targetSourceId: targetSourceId);
                        _log.Info($"[Server] 远程调整冷却时间 → {cd}s");
                        SaveSettings();
                    }
                    else
                    {
                        _serverPushService.SendCommandAck("set-config:cooldown", false, "值无效（1-300）", requestId, targetSourceId);
                    }
                    break;

                case "confidence":
                    if (float.TryParse(value,
                        System.Globalization.NumberStyles.Float,
                        System.Globalization.CultureInfo.InvariantCulture,
                        out float conf) && conf >= 0.1f && conf <= 0.95f)
                    {
                        int pct = (int)(conf * 100);
                        _trkThreshold.Value = Math.Max(_trkThreshold.Minimum,
                                              Math.Min(_trkThreshold.Maximum, pct));
                        if (IsCurrentSourceStarted)
                            UpdateCurrentSourceConfig();
                        _serverPushService.SendCommandAck("set-config:confidence", true, requestId: requestId, targetSourceId: targetSourceId);
                        _log.Info($"[Server] 远程调整置信度 → {pct}%");
                        SaveSettings();
                    }
                    else
                    {
                        _serverPushService.SendCommandAck("set-config:confidence", false, "值无效（0.1-0.95）", requestId, targetSourceId);
                    }
                    break;

                case "targets":
                    // value 为逗号分隔的类名，空字符串 = 全部
                    var classes = new System.Collections.Generic.HashSet<string>(
                        StringComparer.OrdinalIgnoreCase);
                    if (!string.IsNullOrWhiteSpace(value))
                        foreach (var cls in value.Split(','))
                        {
                            string trimmed = cls.Trim();
                            if (!string.IsNullOrEmpty(trimmed))
                                classes.Add(trimmed);
                        }
                    for (int i = 0; i < _targetClassKeys.Length; i++)
                        _targetListBox.SetItemChecked(i, classes.Contains(_targetClassKeys[i]));
                    if (IsCurrentSourceStarted)
                        UpdateCurrentSourceConfig();
                    _serverPushService.SendCommandAck("set-config:targets", true, requestId: requestId, targetSourceId: targetSourceId);
                    _log.Info($"[Server] 远程调整监控目标 → {(classes.Count == 0 ? "全部" : string.Join(",", classes))}");
                    SaveSettings();
                    break;

                case "targetSamplingRate":
                    if (int.TryParse(value, out int rate) && rate >= 1 && rate <= 5)
                    {
                        _sliderSamplingRate.Value = Math.Max(_sliderSamplingRate.Minimum,
                            Math.Min(_sliderSamplingRate.Maximum, rate));
                        _lblSamplingRate.Text = $"{_sliderSamplingRate.Value} 次/秒";
                        if (IsCurrentSourceStarted)
                            UpdateCurrentSourceConfig();
                        _serverPushService.SendCommandAck("set-config:targetSamplingRate", true, requestId: requestId, targetSourceId: targetSourceId);
                        _log.Info($"[Server] 远程调整采样率 → {rate} 次/秒");
                        SaveSettings();
                    }
                    else
                    {
                        _serverPushService.SendCommandAck("set-config:targetSamplingRate", false, "值无效（1-5）", requestId, targetSourceId);
                    }
                    break;

                case "modelKey":
                    if (IsCurrentSourceStarted)
                    {
                        _serverPushService.SendCommandAck("set-config:modelKey", false, "请先停止监控再切换模型", requestId, targetSourceId);
                        break;
                    }
                    int modelIndex = Array.IndexOf(Utils.ModelManager.ModelKeys, value);
                    if (modelIndex < 0)
                    {
                        _serverPushService.SendCommandAck("set-config:modelKey", false, "模型不支持", requestId, targetSourceId);
                        break;
                    }
                    _selectedModel = value;
                    _cmbModel.SelectedIndex = modelIndex;
                    _serverPushService.SendCommandAck("set-config:modelKey", true, requestId: requestId, targetSourceId: targetSourceId);
                    _log.Info($"[Server] 远程切换模型 → {value}");
                    SaveSettings();
                    break;

                default:
                    _serverPushService.SendCommandAck($"set-config:{key}", false, $"未知配置项：{key}", requestId, targetSourceId);
                    break;
            }
        }

        // ════════════════════════════════════════════════════════════
        // 遮罩区域持久化辅助
        // ════════════════════════════════════════════════════════════

        /// <summary>遮罩 JSON DTO，使用 left/top/right/bottom 字段，与 Android 端格式对齐。</summary>
        private class MaskRegionDto
        {
            public float left   { get; set; }
            public float top    { get; set; }
            public float right  { get; set; }
            public float bottom { get; set; }
        }

        /// <summary>把 List&lt;RectangleF&gt; 序列化为 JSON 数组字符串。</summary>
        private static string MasksToJson(List<RectangleF> masks)
        {
            if (masks == null || masks.Count == 0) return "[]";
            var dtos = new List<MaskRegionDto>(masks.Count);
            foreach (var r in masks)
            {
                dtos.Add(new MaskRegionDto
                {
                    left   = r.X,
                    top    = r.Y,
                    right  = r.X + r.Width,
                    bottom = r.Y + r.Height,
                });
            }
            return Utils.SimpleJson.ToJson(dtos);
        }

        /// <summary>把 JSON 数组字符串解析为 List&lt;RectangleF&gt;，失败返回空列表。</summary>
        private static List<RectangleF> ParseMasksJson(string json)
        {
            if (string.IsNullOrWhiteSpace(json)) return new List<RectangleF>();
            var dtos = Utils.SimpleJson.Deserialize<List<MaskRegionDto>>(json);
            var list = new List<RectangleF>();
            if (dtos == null) return list;
            foreach (var d in dtos)
            {
                float x = Math.Max(0f, Math.Min(1f, d.left));
                float y = Math.Max(0f, Math.Min(1f, d.top));
                float w = Math.Max(0f, Math.Min(1f, d.right))  - x;
                float h = Math.Max(0f, Math.Min(1f, d.bottom)) - y;
                if (w <= 0f || h <= 0f) continue;
                list.Add(new RectangleF(x, y, w, h));
            }
            return list;
        }
    }
}
