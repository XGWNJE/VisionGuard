// ┌─────────────────────────────────────────────────────────┐
// │ ServerPushService.cs                                    │
// │ 架构：单状态源 + 单事件循环 + Session 隔离              │
// │   • 所有状态变更通过 _events 队列串行处理               │
// │   • 每次连接一个独立 Session，Shutdown 后子线程退出     │
// │   • lastMessageAt 只由真实消息更新，心跳不自我喂食      │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;
using System.Net.NetworkInformation;
using System.Net.Security;
using System.Security.Cryptography.X509Certificates;
using System.Threading;
using System.Threading.Tasks;
using WebSocketSharp;
using VisionGuard.Models;
using VisionGuard.Utils;

namespace VisionGuard.Services
{
    public enum WsState { Disconnected, Connecting, Connected, AuthFailed }

    public sealed class ServerPushService : IDisposable
    {
        // ── 对外事件 ─────────────────────────────────────────────────
        /// <summary>"connected" / "connecting" / "disconnected"</summary>
        public event EventHandler<string> ConnectionStateChanged;

        /// <summary>"pause" / "resume" / "stop-alarm"</summary>
        public event EventHandler<string> CommandReceived;

        /// <summary>set-config 命令：key=配置项名, value=新值</summary>
        public event EventHandler<KeyValuePair<string, string>> SetConfigReceived;

        // ── 常量 ─────────────────────────────────────────────────────
        private const int HEARTBEAT_INTERVAL_MS = 3_000;
        private const int GHOST_THRESHOLD_MS = 45_000;
        private const int AUTH_TIMEOUT_MS = 12_000;
        private const int SEND_TIMEOUT_MS = 5_000;
        private static readonly int[] BackoffSeconds = { 1, 2, 3, 5, 10, 20 };

        // ── 配置（仅事件循环访问） ───────────────────────────────────
        private string _serverUrl = "";
        private string _apiKey = "";
        private string _deviceId = "";
        private string _deviceName = "";
        private bool _shouldReconnect;
        private int _attempt;

        // ── 状态（仅事件循环访问） ──────────────────���────────────────
        private WsState _state = WsState.Disconnected;
        private Session _session;
        private Timer _backoffTimer;

        // ── 事件循环 ────────────────────────────────────────────────
        private readonly BlockingCollection<Action> _events = new BlockingCollection<Action>();
        private readonly Thread _loopThread;

        // ── 心跳参数（UI 写，Session 读） ────────────────────────────
        private readonly object _hbParamsLock = new object();
        private bool _hbIsMonitoring, _hbIsReady;
        private int _hbCooldown = 5;
        private float _hbConfidence = 0.45f;
        private string _hbTargets = "";
        private int _hbTargetSamplingRate = 3;
        private string _hbModelKey = "";
        private string[] _hbModelOptions = new string[0];
        private bool _hbCanSwitchModelWhileMonitoring;

        private bool _disposed;

        // 网络变化防抖：30 秒内只处理一次，且只在从"无网络"变为"有网络"时才重连
        private DateTime _lastNetworkChangeHandled = DateTime.MinValue;
        private bool _lastNetworkWasAvailable = false;
        private static readonly TimeSpan NetworkChangeDebounce = TimeSpan.FromSeconds(30);

        public bool IsConnected => _state == WsState.Connected;

        public ServerPushService()
        {
            _loopThread = new Thread(EventLoop) { IsBackground = true, Name = "VG_WsEventLoop" };
            _loopThread.Start();

            // 监听系统网络变化 → 立即重连（避免等 60s 幽灵超时）
            NetworkChange.NetworkAddressChanged += OnSystemNetworkChanged;
        }

        private void OnSystemNetworkChanged(object sender, EventArgs e)
        {
            if (_disposed || !_shouldReconnect) return;

            // 防抖：30 秒内不重复处理
            var now = DateTime.Now;
            if (now - _lastNetworkChangeHandled < NetworkChangeDebounce)
                return;

            // 检查是否有可用网络
            bool hasNetwork;
            try { hasNetwork = NetworkInterface.GetIsNetworkAvailable(); }
            catch { hasNetwork = false; }

            if (hasNetwork && !_lastNetworkWasAvailable)
            {
                // 从"无网络"变为"有网络"：触发重连
                _lastNetworkChangeHandled = now;
                _lastNetworkWasAvailable = true;
                LogManager.StaticInfo("[Server] 网络恢复 → 立即重连");
                Post(OnNetworkChanged);
            }
            else if (!hasNetwork && _lastNetworkWasAvailable)
            {
                // 从"有网络"变为"无网络"：关闭会话
                _lastNetworkChangeHandled = now;
                _lastNetworkWasAvailable = false;
                LogManager.StaticInfo("[Server] 网络断开 → 关闭当前会话");
                Post(OnNetworkLost);
            }
            // 状态未变化：静默忽略（避免日志刷屏）
        }

        private void Post(Action action)
        {
            if (_disposed) return;
            try { _events.Add(action); } catch { /* 已关闭 */ }
        }

        private void EventLoop()
        {
            foreach (var action in _events.GetConsumingEnumerable())
            {
                try { action(); }
                catch (Exception ex) { LogManager.StaticWarn($"[Server] 事件异常: {ex.Message}"); }
            }
        }

        // ════════════════════════════════════════════════════════════
        // 公开 API（线程安全，通过事件队列路由）
        // ════════════════════════════════════════════════════════════

        public void Configure(string serverUrl, string apiKey, string deviceId, string deviceName)
        {
            var url = (serverUrl ?? "").TrimEnd('/');
            var key = apiKey ?? "";
            var did = deviceId ?? "";
            var dname = string.IsNullOrWhiteSpace(deviceName) ? Environment.MachineName : deviceName;
            Post(() => OnConnect(url, key, did, dname));
        }

        public void Disconnect() => Post(OnDisconnect);

        public void UpdateHeartbeatParams(bool isMonitoring, bool isReady,
            int cooldown, float confidence, string targets,
            int targetSamplingRate = 3, string modelKey = "",
            string[] modelOptions = null, bool canSwitchModelWhileMonitoring = false)
        {
            lock (_hbParamsLock)
            {
                _hbIsMonitoring = isMonitoring;
                _hbIsReady = isReady;
                _hbCooldown = cooldown;
                _hbConfidence = confidence;
                _hbTargets = targets ?? "";
                _hbTargetSamplingRate = Math.Max(1, Math.Min(5, targetSamplingRate));
                _hbModelKey = modelKey ?? "";
                _hbModelOptions = modelOptions == null ? new string[0] : (string[])modelOptions.Clone();
                _hbCanSwitchModelWhileMonitoring = canSwitchModelWhileMonitoring;
            }
        }

        public void PushAlert(AlertEvent alert)
        {
            if (alert == null) return;
            var s = _session;
            if (s == null || _state != WsState.Connected)
            {
                LogManager.StaticWarn($"[Server] WS 未连接，报警丢弃: {alert.AlertId}");
                return;
            }
            DoPushAlert(s, alert);
        }

        private void DoPushAlert(Session s, AlertEvent alert)
        {
            var msg = new Dictionary<string, object>
            {
                ["type"] = "alert",
                ["alertId"] = alert.AlertId,
                ["deviceId"] = _deviceId,
                ["deviceName"] = _deviceName,
                ["timestamp"] = alert.Timestamp.ToString("o"),
                ["detections"] = BuildDetectionsPayload(alert.Detections),
                ["timings"] = alert.Timings,
                ["capturedAt"] = NtpSync.UtcNow.ToString("o"),
            };

            // 协议分离: alert 元数据先发(最高优先级)
            var labels = string.Join(",", alert.Detections.Select(d => d.Label));
            var totalMs = alert.Timings.TryGetValue("totalProcessMs", out var t) ? $"{t}ms" : "N/A";
            if (s.SendJson(msg))
            {
                LogManager.StaticInfo($"[Server] 报警已推送: alertId={alert.AlertId}, targets={alert.Detections.Count}, [{labels}], total={totalMs}");
                // 截图独立异步推送(不阻塞 alert,编码失败不影响通知到达)
                ThreadPool.QueueUserWorkItem(_ => PushScreenshotData(s, alert.AlertId));
            }
            else
            {
                LogManager.StaticWarn($"[Server] 报警推送失败 alertId={alert.AlertId}");
            }
        }

        private void PushScreenshotData(Session s, string alertId)
        {
            try
            {
                string path = AlertService.GetSnapshotPath(alertId);
                if (!File.Exists(path)) return;
                string base64;
                using (var bmp = new Bitmap(path))
                {
                    base64 = EncodeScreenshotBase64(bmp);
                }
                var msg = new Dictionary<string, object>
                {
                    ["type"] = "screenshot-data",
                    ["alertId"] = alertId,
                    ["deviceId"] = _deviceId,
                    ["imageBase64"] = base64,
                };
                if (s.SendJson(msg))
                    LogManager.StaticInfo($"[Server] 截图已推送: alertId={alertId} size={base64.Length}");
                else
                    LogManager.StaticWarn($"[Server] 截图推送失败 alertId={alertId}");
            }
            catch (Exception ex)
            {
                LogManager.StaticWarn($"[Server] 截图编码异常 alertId={alertId}: {ex.Message}");
            }
        }

        private static string EncodeScreenshotBase64(Bitmap bmp)
        {
            const int MaxW = 960;
            using (var toSend = bmp.Width > MaxW
                ? new Bitmap(bmp, new Size(MaxW, (int)(bmp.Height * (MaxW / (double)bmp.Width))))
                : null)
            {
                var target = toSend ?? bmp;
                using (var ms = new MemoryStream())
                {
                    var jpegParams = new EncoderParameters(1);
                    jpegParams.Param[0] = new EncoderParameter(Encoder.Quality, 65L);
                    var jpegCodec = ImageCodecInfo.GetImageEncoders()
                        .FirstOrDefault(c => c.MimeType == "image/jpeg");
                    if (jpegCodec != null)
                        target.Save(ms, jpegCodec, jpegParams);
                    else
                        target.Save(ms, ImageFormat.Jpeg);
                    return Convert.ToBase64String(ms.ToArray());
                }
            }
        }

        public void SendCommandAck(string command, bool success, string reason = "")
        {
            _session?.SendJson(new Dictionary<string, object>
            {
                ["type"] = "command-ack",
                ["command"] = command,
                ["success"] = success,
                ["reason"] = reason ?? "",
            });
        }

        public void SendHeartbeatNow()
        {
            var s = _session;
            if (s == null || _state != WsState.Connected) return;
            bool isMonitoring, isReady;
            int cooldown;
            float confidence;
            string targets;
            int targetSamplingRate;
            string modelKey;
            string[] modelOptions;
            bool canSwitchModelWhileMonitoring;
            lock (_hbParamsLock)
            {
                isMonitoring = _hbIsMonitoring;
                isReady = _hbIsReady;
                cooldown = _hbCooldown;
                confidence = _hbConfidence;
                targets = _hbTargets;
                targetSamplingRate = _hbTargetSamplingRate;
                modelKey = _hbModelKey;
                modelOptions = (string[])_hbModelOptions.Clone();
                canSwitchModelWhileMonitoring = _hbCanSwitchModelWhileMonitoring;
            }
            s.SendJson(new Dictionary<string, object>
            {
                ["type"] = "heartbeat",
                ["deviceId"] = _deviceId,
                ["deviceName"] = _deviceName,
                ["isMonitoring"] = isMonitoring,
                ["isReady"] = isReady,
                ["cooldown"] = cooldown,
                ["confidence"] = confidence,
                ["targets"] = targets,
                ["targetSamplingRate"] = targetSamplingRate,
                ["modelKey"] = modelKey,
                ["modelOptions"] = modelOptions,
                ["canSwitchModelWhileMonitoring"] = canSwitchModelWhileMonitoring,
            });
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            NetworkChange.NetworkAddressChanged -= OnSystemNetworkChanged;
            try
            {
                _events.Add(() =>
                {
                    _shouldReconnect = false;
                    CancelBackoffTimer();
                    _session?.Shutdown("dispose");
                    _session = null;
                });
            }
            catch { }
            _events.CompleteAdding();
            try { _loopThread?.Join(1500); } catch { }
            _events.Dispose();
        }

        // ════════════════════════════════════════════════════════════
        // 事件处理（仅事件循环线程调用）
        // ════════════════════════════════════════════════════════════

        private void OnConnect(string url, string key, string did, string dname)
        {
            if (string.IsNullOrWhiteSpace(url))
            {
                LogManager.StaticInfo("[Server] Configure: ServerUrl 为空，静默不启动");
                _shouldReconnect = false;
                CancelBackoffTimer();
                _session?.Shutdown("reconfigure-empty");
                _session = null;
                SetState(WsState.Disconnected);
                return;
            }

            if (url == _serverUrl && key == _apiKey && did == _deviceId && dname == _deviceName
                && _state == WsState.Connected)
            {
                LogManager.StaticInfo("[Server] Configure: 参数未变且已连接，跳过");
                return;
            }

            _serverUrl = url;
            _apiKey = key;
            _deviceId = did;
            _deviceName = dname;
            _shouldReconnect = true;
            _attempt = 0;

            CancelBackoffTimer();
            _session?.Shutdown("reconfigure");
            _session = null;

            LogManager.StaticInfo($"[Server] connect → {_serverUrl} deviceId={_deviceId}");
            StartNewSession();
        }

        private void OnDisconnect()
        {
            LogManager.StaticInfo("[Server] disconnect 用户主动断开");
            _shouldReconnect = false;
            CancelBackoffTimer();
            _session?.Shutdown("user-close");
            _session = null;
            SetState(WsState.Disconnected);
        }

        private void OnNetworkChanged()
        {
            if (!_shouldReconnect || string.IsNullOrWhiteSpace(_serverUrl)) return;

            switch (_state)
            {
                case WsState.Connected:
                    LogManager.StaticInfo("[Server] 网络变化且已连接 → 断开旧连接立即重连");
                    _session?.Shutdown("network-changed");
                    _session = null;
                    break;
                case WsState.Connecting:
                    LogManager.StaticInfo("[Server] 网络变化且正在连接 → 中断后重试");
                    _session?.Shutdown("network-changed");
                    _session = null;
                    break;
            }
            CancelBackoffTimer();
            _attempt = 0;
            StartNewSession();
        }

        private void OnNetworkLost()
        {
            if (_session == null) return;
            LogManager.StaticInfo("[Server] 网络断开 → 关闭当前会话等待恢复");
            _session?.Shutdown("network-lost");
            _session = null;
            CancelBackoffTimer();
            SetState(WsState.Disconnected);
        }

        private void OnWsOpened(Session s)
        {
            if (s != _session) return;
            LogManager.StaticInfo("[Server] WS OnOpen → 发送认证");
            s.SendJson(new Dictionary<string, object>
            {
                ["type"] = "auth",
                ["apiKey"] = _apiKey,
                ["role"] = "windows",
                ["deviceId"] = _deviceId,
                ["deviceName"] = _deviceName,
                ["version"] = "4.4.1",
            });
        }

        private void OnAuthResult(Session s, bool success, string reason)
        {
            if (s != _session) return;
            if (success)
            {
                LogManager.StaticInfo("[Server] WS 认证成功");
                _attempt = 0;
                SetState(WsState.Connected);
                s.StartHeartbeat();
            }
            else
            {
                LogManager.StaticWarn($"[Server] WS 认证失败: {reason}");
                SetState(WsState.AuthFailed);
                s.Shutdown("auth-failed");
                _session = null;
                if (IsPermanentAuthFailure(reason))
                {
                    _shouldReconnect = false;
                    LogManager.StaticWarn("[Server] 认证为永久失败，已停止自动重连");
                    return;
                }
                if (_shouldReconnect) ScheduleReconnect();
            }
        }

        private static bool IsPermanentAuthFailure(string reason)
        {
            var r = (reason ?? "").ToLowerInvariant();
            return r.Contains("invalid api key")
                || r.Contains("invalid deviceid")
                || r.Contains("invalid role")
                || r.Contains("needs-update")
                || r.Contains("version too old");
        }

        private void OnWsFailed(Session s, ushort code, string reason)
        {
            if (s != _session) return;
            LogManager.StaticWarn($"[Server] WS 失败 code={code} reason={reason}");
            s.Shutdown("failed");
            _session = null;
            SetState(WsState.Disconnected);
            if (_shouldReconnect) ScheduleReconnect();
        }

        private void OnGhostDetected(Session s)
        {
            if (s != _session) return;
            LogManager.StaticWarn("[Server] 幽灵检测触发，关闭连接");
            s.Shutdown("ghost");
            _session = null;
            SetState(WsState.Disconnected);
            if (_shouldReconnect) ScheduleReconnect();
        }

        private void OnKicked(Session s, string reason)
        {
            if (s != _session) return;
            LogManager.StaticWarn($"[Server] WS 被踢: {reason}");
            s.Shutdown("kicked");
            _session = null;
            SetState(WsState.Disconnected);
            if (_shouldReconnect) ScheduleReconnect();
        }

        private void StartNewSession()
        {
            CancelBackoffTimer();
            SetState(WsState.Connecting);
            string wsUrl = _serverUrl.Replace("https://", "wss://").Replace("http://", "ws://") + "/ws";
            LogManager.StaticInfo($"[Server] 启动新会话 attempt={_attempt + 1} url={wsUrl}");
            var s = new Session(this, wsUrl);
            _session = s;
            s.Start();
        }

        private void ScheduleReconnect()
        {
            if (!_shouldReconnect) return;
            int waitSec = BackoffSeconds[Math.Min(_attempt, BackoffSeconds.Length - 1)];
            _attempt++;
            LogManager.StaticInfo($"[Server] {waitSec}s 后重连（第 {_attempt} 次）");
            CancelBackoffTimer();
            _backoffTimer = new Timer(_ =>
            {
                Post(() =>
                {
                    if (_shouldReconnect && _state != WsState.Connected && _state != WsState.Connecting)
                        StartNewSession();
                });
            }, null, waitSec * 1000, Timeout.Infinite);
        }

        private void CancelBackoffTimer()
        {
            _backoffTimer?.Dispose();
            _backoffTimer = null;
        }

        private void SetState(WsState newState)
        {
            if (_state == newState) return;
            _state = newState;
            string name;
            switch (newState)
            {
                case WsState.Connected: name = "connected"; break;
                case WsState.Connecting: name = "connecting"; break;
                default: name = "disconnected"; break;
            }
            LogManager.StaticInfo($"[Server] 状态 → {name} deviceId={_deviceId}");
            try { ConnectionStateChanged?.Invoke(this, name); } catch { }
        }

        // ════════════════════════════════════════════════════════════
        // Session — 每次连接一个实例，彻底隔离
        // ════════════════════════════════════════════════════════════

        private sealed class Session
        {
            private readonly ServerPushService _parent;
            private readonly string _wsUrl;
            private readonly string _expectedHost;
            private WebSocket _ws;
            private readonly object _sendLock = new object();
            private Thread _heartbeatThread;
            private Thread _authTimeoutThread;
            private readonly CancellationTokenSource _cts = new CancellationTokenSource();
            private long _lastMessageAtTicks = DateTime.UtcNow.Ticks;
            private volatile bool _shutdown;

            public Session(ServerPushService parent, string wsUrl)
            {
                _parent = parent;
                _wsUrl = wsUrl;
                _expectedHost = new Uri(wsUrl).Host;
            }

            public void Start()
            {
                _ws = new WebSocket(_wsUrl);
                _ws.ServerCertificateValidationCallback = ValidateServerCertificate;
                _ws.OnOpen += (_, __) =>
                {
                    if (_shutdown) return;
                    Interlocked.Exchange(ref _lastMessageAtTicks, DateTime.UtcNow.Ticks);
                    _parent.Post(() => _parent.OnWsOpened(this));
                };
                _ws.OnMessage += (_, e) =>
                {
                    if (_shutdown) return;
                    Interlocked.Exchange(ref _lastMessageAtTicks, DateTime.UtcNow.Ticks);
                    HandleMessage(e.Data);
                };
                _ws.OnClose += (_, e) =>
                {
                    if (_shutdown) return;
                    _parent.Post(() => _parent.OnWsFailed(this, e.Code, $"closed:{e.Reason}"));
                };
                _ws.OnError += (_, e) =>
                {
                    if (_shutdown) return;
                    _parent.Post(() => _parent.OnWsFailed(this, 0, $"error:{e.Message}"));
                };

                try { _ws.Connect(); }
                catch (Exception ex)
                {
                    _parent.Post(() => _parent.OnWsFailed(this, 0, $"connect-ex:{ex.Message}"));
                    return;
                }

                // 认证超时看门狗
                var token = _cts.Token;
                _authTimeoutThread = new Thread(() =>
                {
                    try
                    {
                        if (token.WaitHandle.WaitOne(AUTH_TIMEOUT_MS)) return;
                        if (_shutdown) return;
                        _parent.Post(() =>
                        {
                            if (_parent._session == this && _parent._state != WsState.Connected && !_shutdown)
                                _parent.OnWsFailed(this, 0, "auth-timeout");
                        });
                    }
                    catch { }
                })
                { IsBackground = true, Name = "VG_AuthTimeout" };
                _authTimeoutThread.Start();
            }

            private bool ValidateServerCertificate(
                object sender,
                X509Certificate certificate,
                X509Chain chain,
                SslPolicyErrors sslPolicyErrors)
            {
                if (sslPolicyErrors == SslPolicyErrors.None)
                    return true;

                var cert2 = certificate as X509Certificate2;
                if (cert2 == null && certificate != null)
                    cert2 = new X509Certificate2(certificate);

                var dnsName = cert2?.GetNameInfo(X509NameType.DnsName, false) ?? "";
                var chainStatus = chain == null
                    ? "none"
                    : string.Join(",", chain.ChainStatus.Select(s => s.Status.ToString()).ToArray());

                LogManager.StaticWarn(
                    $"[Server] TLS certificate warning host={_expectedHost} certDns={dnsName} errors={sslPolicyErrors} chain={chainStatus}");

                var onlyChainErrors = sslPolicyErrors == SslPolicyErrors.RemoteCertificateChainErrors;
                var hostMatches = string.Equals(dnsName, _expectedHost, StringComparison.OrdinalIgnoreCase);
                if (onlyChainErrors && hostMatches)
                {
                    LogManager.StaticWarn("[Server] TLS certificate chain ignored for matched server host (Win7 root store compatibility)");
                    return true;
                }

                return false;
            }

            public void StartHeartbeat()
            {
                if (_shutdown) return;
                Interlocked.Exchange(ref _lastMessageAtTicks, DateTime.UtcNow.Ticks);
                var token = _cts.Token;
                _heartbeatThread = new Thread(() => HeartbeatLoop(token))
                {
                    IsBackground = true,
                    Name = "VG_Heartbeat"
                };
                _heartbeatThread.Start();
            }

            private void HeartbeatLoop(CancellationToken token)
            {
                while (!token.IsCancellationRequested && !_shutdown)
                {
                    if (token.WaitHandle.WaitOne(HEARTBEAT_INTERVAL_MS)) return;
                    if (_shutdown) return;

                    // 幽灵检测：只看 lastMessageAt，心跳不自我喂食
                    long last = Interlocked.Read(ref _lastMessageAtTicks);
                    long silentMs = (DateTime.UtcNow.Ticks - last) / TimeSpan.TicksPerMillisecond;
                    if (silentMs > GHOST_THRESHOLD_MS)
                    {
                        _parent.Post(() => _parent.OnGhostDetected(this));
                        return;
                    }

                    bool isMonitoring, isReady;
                    int cooldown;
                    float confidence;
                    string targets;
                    int targetSamplingRate;
                    string modelKey;
                    string[] modelOptions;
                    bool canSwitchModelWhileMonitoring;
                    lock (_parent._hbParamsLock)
                    {
                        isMonitoring = _parent._hbIsMonitoring;
                        isReady = _parent._hbIsReady;
                        cooldown = _parent._hbCooldown;
                        confidence = _parent._hbConfidence;
                        targets = _parent._hbTargets;
                        targetSamplingRate = _parent._hbTargetSamplingRate;
                        modelKey = _parent._hbModelKey;
                        modelOptions = (string[])_parent._hbModelOptions.Clone();
                        canSwitchModelWhileMonitoring = _parent._hbCanSwitchModelWhileMonitoring;
                    }

                    SendJson(new Dictionary<string, object>
                    {
                        ["type"] = "heartbeat",
                        ["deviceId"] = _parent._deviceId,
                        ["deviceName"] = _parent._deviceName,
                        ["isMonitoring"] = isMonitoring,
                        ["isReady"] = isReady,
                        ["cooldown"] = cooldown,
                        ["confidence"] = confidence,
                        ["targets"] = targets,
                        ["targetSamplingRate"] = targetSamplingRate,
                        ["modelKey"] = modelKey,
                        ["modelOptions"] = modelOptions,
                        ["canSwitchModelWhileMonitoring"] = canSwitchModelWhileMonitoring,
                    });
                    // 注意：发送心跳后绝不更新 _lastMessageAtTicks
                }
            }

            private void HandleMessage(string json)
            {
                try
                {
                    var d = SimpleJson.ParseDict(json);
                    string type = SimpleJson.GetString(d, "type");
                    switch (type)
                    {
                        case "auth-result":
                        {
                            bool success = d.TryGetValue("success", out object sv) && sv is bool b && b;
                            string reason = SimpleJson.GetString(d, "reason", "");
                            _parent.Post(() => _parent.OnAuthResult(this, success, reason));
                            break;
                        }
                        case "kicked":
                        {
                            string reason = SimpleJson.GetString(d, "reason", "duplicate");
                            _parent.Post(() => _parent.OnKicked(this, reason));
                            break;
                        }
                        case "command":
                        {
                            string cmd = SimpleJson.GetString(d, "command");
                            if (!string.IsNullOrEmpty(cmd))
                                try { _parent.CommandReceived?.Invoke(_parent, cmd); } catch { }
                            break;
                        }
                        case "set-config":
                        {
                            string key = SimpleJson.GetString(d, "key");
                            string val = SimpleJson.GetString(d, "value");
                            if (!string.IsNullOrEmpty(key))
                                try { _parent.SetConfigReceived?.Invoke(_parent, new KeyValuePair<string, string>(key, val)); } catch { }
                            break;
                        }
                        case "request-screenshot":
                        {
                            string alertId = SimpleJson.GetString(d, "alertId");
                            if (!string.IsNullOrEmpty(alertId))
                                ThreadPool.QueueUserWorkItem(_ => _parent.PushScreenshotData(this, alertId));
                            break;
                        }
                    }
                }
                catch (Exception ex)
                {
                    System.Diagnostics.Debug.WriteLine($"[Server] HandleMessage 异常: {ex.Message}");
                }
            }

            public bool SendJson(Dictionary<string, object> msg)
            {
                if (_shutdown) return false;
                string json;
                try { json = SimpleJson.ToJson(msg); }
                catch { return false; }

                lock (_sendLock)
                {
                    if (_ws == null || !_ws.IsAlive) return false;
                    try
                    {
                        var task = Task.Run(() => _ws.Send(json));
                        if (task.Wait(SEND_TIMEOUT_MS)) return true;
                        LogManager.StaticWarn($"[Server] WS 发送超时({SEND_TIMEOUT_MS}ms)");
                        return false;
                    }
                    catch (Exception ex)
                    {
                        LogManager.StaticWarn($"[Server] WS 发送异常: {ex.Message}");
                        return false;
                    }
                }
            }

            public void Shutdown(string reason)
            {
                if (_shutdown) return;
                _shutdown = true;
                try { _cts.Cancel(); } catch { }
                try { _ws?.Close(); } catch { }
                LogManager.StaticInfo($"[Server] Session shutdown: {reason}");
            }
        }

        // ── 序列化辅助 ───────────────────────────────────────────────

        private static List<Dictionary<string, object>> BuildDetectionsPayload(
            IReadOnlyList<Detection> detections)
        {
            var list = new List<Dictionary<string, object>>();
            if (detections == null) return list;
            foreach (var d in detections)
            {
                list.Add(new Dictionary<string, object>
                {
                    ["label"] = d.Label ?? "",
                    ["confidence"] = d.Confidence,
                    ["bbox"] = new Dictionary<string, object>
                    {
                        ["x"] = d.BoundingBox.X,
                        ["y"] = d.BoundingBox.Y,
                        ["w"] = d.BoundingBox.Width,
                        ["h"] = d.BoundingBox.Height,
                    },
                });
            }
            return list;
        }
    }
}
