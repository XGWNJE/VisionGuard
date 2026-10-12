// ┌─────────────────────────────────────────────────────────────┐
// │ MediaPublishService.cs                                      │
// │ 角色：推流态下把本机采集画面按媒体契约推给其他推理节点       │
// │ 线程：独立后台发布线程 + 独立接收线程；帧源回调在发布线程执行│
// │ 对外 API：Start/Stop/Status，事件 StatusChanged             │
// └─────────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using VisionGuard.Detector.Windows.Net;
using VisionGuard.Detector.Windows.Utils;

namespace VisionGuard.Detector.Windows.Services
{
    /// <summary>
    /// 相机推流契约的 Windows 发布端：4 字节大端 JSON 头长度 + UTF-8 头 + JPEG，
    /// 最多 8 帧在途、合计 2 MiB 加帧头，frame-ack 累计确认；分辨率最长边 1280、短边 720。
    /// 发布端必须由控制台显式绑定目标；发布 2.5 秒无新帧由服务标记停滞。
    /// </summary>
    public sealed class MediaPublishService : IDisposable
    {
        public const int MaximumLongEdge = 1280;
        public const int MaximumShortEdge = 720;
        public const long JpegQuality = 55L;
        private const int MaximumHeaderBytes = 4096;
        private const int MaximumFrameBytes = 2 * 1024 * 1024;
        private const int MaximumInflightFrames = 8;
        private const long MaximumInflightBytes = MaximumFrameBytes;

        private readonly string _url, _token;
        private readonly Func<PublishedFrame?> _frameSource;
        private readonly object _sync = new();
        private readonly ManualResetEventSlim _wake = new(false);
        private readonly ManualResetEventSlim _ready = new(false);
        private readonly Dictionary<long, long> _inflight = new();
        private readonly CancellationTokenSource _stop = new();
        private long _inflightBytes;
        private Thread? _worker, _receiver;
        private MinimalWebSocketClient? _socket;
        private string _streamId = "", _sessionId = "";
        private bool _connectionReady, _disposed;

        public MediaPublishService(string url, string token, Func<PublishedFrame?> frameSource, int targetFps)
        {
            _url = url.TrimEnd('/').Replace("https://", "wss://").Replace("http://", "ws://") + "/media/ws";
            _token = token;
            _frameSource = frameSource;
            SetTargetFps(targetFps);
        }

        /// <summary>推流状态变化；回调在后台线程触发，界面线程由调用方负责切换。</summary>
        public event EventHandler? StatusChanged;

        public PublishStatus Status { get; private set; } = PublishStatus.Idle;
        /// <summary>状态说明：连接 / 停止等自身状态优先，其次是服务端拒收原因（未绑定目标等）。</summary>
        public string StatusDetail => _statusDetail.Length > 0 ? _statusDetail : _rejectDetail;
        private string _statusDetail = "", _rejectDetail = "";
        public double ActualFps { get; private set; }
        public int TargetFps { get; private set; } = 5;
        public long LastAcknowledgedSequence { get; private set; }
        public long SentFrames { get; private set; }
        public long DroppedFrames { get; private set; }
        public long UnacknowledgedFrames { get { lock (_sync) return _inflight.Count; } }

        public void SetTargetFps(int fps)
        {
            TargetFps = Math.Max(1, Math.Min(5, fps));
        }

        public void Start()
        {
            if (_worker != null) return;
            _worker = new Thread(PublishLoop) { IsBackground = true, Name = "VG_MediaPublish" };
            _worker.Start();
        }

        /// <summary>停止推流：先按契约上报正常停止原因，再断开连接。</summary>
        public void Stop()
        {
            if (_disposed && _worker == null) return;
            var socket = _socket;
            try
            {
                if (socket != null && socket.State == WebSocketState.Open)
                {
                    var bytes = Encoding.UTF8.GetBytes(SimpleJson.ToJson(new { type = "stream-stop", reason = "user" }));
                    socket.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, CancellationToken.None).GetAwaiter().GetResult();
                }
            }
            catch { }
            _stop.Cancel();
            try { socket?.Abort(); } catch { }
            _wake.Set(); _ready.Set();
            var receiver = _receiver; _receiver = null;
            var worker = _worker; _worker = null;
            if (receiver != null && receiver.IsAlive) receiver.Join(2000);
            if (worker != null && worker.IsAlive) worker.Join(2000);
            ClearQueue();
        }

        private void PublishLoop()
        {
            long nextSample = 0;
            long previousSequence = 0, previousSentAt = 0;
            while (!_stop.IsCancellationRequested)
            {
                try
                {
                    using (var ws = new MinimalWebSocketClient(new Uri(_url), maxMessageBytes: MaximumFrameBytes + MaximumHeaderBytes + 5))
                    {
                        _socket = ws;
                        SetStatus(PublishStatus.Connecting, "连接媒体通道");
                        Utils.MediaDiagnostics.Write("[Publish] connecting " + _url);
                        ws.ConnectAsyncInternal(_stop.Token).GetAwaiter().GetResult();
                        _connectionReady = false; _ready.Reset(); _inflight.Clear(); _inflightBytes = 0;
                        Send(ws, new { type = "media-auth", token = _token, direction = "publish", version = AppConfig.Version });
                        StartReceiver(ws);
                        long lastHeartbeat = NowTicks();
                        long lastReport = NowTicks();
                        while (!_stop.IsCancellationRequested && ws.State == WebSocketState.Open)
                        {
                            if (NowTicks() - lastHeartbeat >= 3 * System.Diagnostics.Stopwatch.Frequency)
                            {
                                Send(ws, new { type = "media-heartbeat" });
                                lastHeartbeat = NowTicks();
                            }
                            if (NowTicks() - lastReport >= 10 * System.Diagnostics.Stopwatch.Frequency)
                            {
                                lastReport = NowTicks();
                                Utils.MediaDiagnostics.Write($"[Publish] stats status={Status} sent={SentFrames} acked={LastAcknowledgedSequence} unacked={UnacknowledgedFrames} dropped={DroppedFrames}");
                            }
                            if (!_connectionReady) { _ready.Wait(200); continue; }
                            long now = NowTicks();
                            if (nextSample == 0) nextSample = now;
                            if (now < nextSample) { _wake.Wait(ComputeWaitMs(now, nextSample)); _wake.Reset(); continue; }
                            nextSample = Math.Max(nextSample + IntervalTicks(), now);
                            if (!HasInflightCapacity())
                            {
                                // 发送被回执窗口卡住是本机最难从界面看出的故障，明确留一次日志。
                                if (!_inflightFull)
                                {
                                    _inflightFull = true;
                                    Utils.MediaDiagnostics.Write($"[Publish] inflight window full: unacked={UnacknowledgedFrames} acked={LastAcknowledgedSequence} — 等待服务端 frame-ack");
                                }
                                DroppedFrames++; continue;
                            }
                            _inflightFull = false;
                            if (PublishFrame(ws, ref previousSequence, ref previousSentAt)) continue;
                        }
                    }
                }
                catch (Exception ex)
                {
                    if (!_stop.IsCancellationRequested)
                    {
                        Utils.MediaDiagnostics.Write("[Publish] connect failed: " + ex.Message);
                        SetStatus(PublishStatus.Faulted, Describe(ex));
                    }
                }
                finally
                {
                    _connectionReady = false;
                    _socket = null;
                }
                if (_stop.IsCancellationRequested) break;
                ClearQueue();
                if (_stop.Token.WaitHandle.WaitOne(2000)) break;
            }
            SetStatus(PublishStatus.Stopped, "已停止推流");
        }

        private void StartReceiver(MinimalWebSocketClient ws)
        {
            var receiver = new Thread(() => ReceiveLoop(ws)) { IsBackground = true, Name = "VG_MediaPublishRx" };
            _receiver = receiver;
            receiver.Start();
        }

        /// <summary>接收线程：读取媒体回执并更新频率与在途额度；断线只结束本线程。</summary>
        private void ReceiveLoop(MinimalWebSocketClient ws)
        {
            var buffer = new byte[64 * 1024];
            try
            {
                while (!_stop.IsCancellationRequested && ws.State == WebSocketState.Open)
                {
                    var result = ws.ReceiveAsync(new ArraySegment<byte>(buffer), _stop.Token).GetAwaiter().GetResult();
                    if (result.MessageType == WebSocketMessageType.Close) break;
                    if (result.MessageType == WebSocketMessageType.Binary) continue;
                    var json = SimpleJson.ParseDict(Encoding.UTF8.GetString(buffer, 0, result.Count));
                    if (json == null) continue;
                    string type = SimpleJson.GetString(json, "type");
                    if (type == "media-ready")
                    {
                        _streamId = SimpleJson.GetString(json, "streamId") ?? "";
                        _sessionId = SimpleJson.GetString(json, "sessionId") ?? "";
                        SetTargetFps(SimpleJson.GetInt(json, "targetSamplingRate", 5));
                        if (string.IsNullOrEmpty(_streamId) || string.IsNullOrEmpty(_sessionId))
                            SetStatus(PublishStatus.Faulted, "推流目标未绑定：请先在控制台关联推理节点。");
                        else { _connectionReady = true; SetStatus(PublishStatus.Streaming, ""); }
                        _ready.Set();
                    }
                    else if (type == "media-rate")
                    {
                        SetTargetFps(SimpleJson.GetInt(json, "targetSamplingRate", TargetFps));
                    }
                    else if (type == "frame-ack")
                    {
                        Acknowledged(SimpleJson.GetLong(json, "sequence", 0), SimpleJson.GetBool(json, "accepted", false), SimpleJson.GetString(json, "dropReason") ?? "");
                    }
                    else if (type == "media-error")
                    {
                        SetStatus(PublishStatus.Faulted, SimpleJson.GetString(json, "reason") ?? "媒体通道错误");
                        break;
                    }
                }
            }
            catch (Exception ex)
            {
                if (!_stop.IsCancellationRequested) LogManager.StaticWarn("[MediaPublish] 接收线程结束：" + ex.Message);
            }
            finally
            {
                _connectionReady = false;
                _ready.Set();
                try { ws.Abort(); } catch { }
            }
        }

        private bool PublishFrame(MinimalWebSocketClient ws, ref long previousSequence, ref long previousSentAt)
        {
            PublishedFrame frame;
            // 帧源在发布线程上串行取帧并编码：采集、编码、发送共用同一条节拍，不需要额外队列。
            Utils.MediaDiagnostics.Write("[Publish] stage=capture");
            try
            {
                var produced = _frameSource();
                if (!produced.HasValue) return false;
                frame = produced.Value;
            }
            catch (Exception ex) { Utils.MediaDiagnostics.Write("[Publish] frame failed: " + ex.Message); SetStatus(PublishStatus.Faulted, Describe(ex)); return false; }
            try
            {
                Utils.MediaDiagnostics.Write("[Publish] stage=encode");
                byte[]? jpeg = EncodeJpeg(frame);
                if (jpeg == null) { DroppedFrames++; SetStatus(PublishStatus.Faulted, "画面编码失败：无法生成符合契约的 JPEG。"); return false; }
                long capturedAt = NextCapturedAt();
                long sequence;
                lock (_sync) { sequence = ++_sendSequence; }
                byte[] header = Encoding.UTF8.GetBytes(SimpleJson.ToJson(new
                {
                    streamId = _streamId,
                    sessionId = _sessionId,
                    sequence,
                    capturedAt,
                    width = frame.Width,
                    height = frame.Height,
                    rotation = 0,
                }));
                if (header.Length > MaximumHeaderBytes) { DroppedFrames++; return false; }
                var packet = new byte[4 + header.Length + jpeg.Length];
                packet[0] = (byte)(header.Length >> 24); packet[1] = (byte)(header.Length >> 16);
                packet[2] = (byte)(header.Length >> 8); packet[3] = (byte)header.Length;
                Buffer.BlockCopy(header, 0, packet, 4, header.Length);
                Buffer.BlockCopy(jpeg, 0, packet, 4 + header.Length, jpeg.Length);
                lock (_sync) { _inflight[sequence] = packet.Length; _inflightBytes += packet.Length; }
                Utils.MediaDiagnostics.Write($"[Publish] stage=send seq={sequence}");
                ws.SendAsync(new ArraySegment<byte>(packet), WebSocketMessageType.Binary, true, _stop.Token).GetAwaiter().GetResult();
                Utils.MediaDiagnostics.Write($"[Publish] stage=sent seq={sequence}");
                SentFrames++;
                long now = NowTicks();
                if (previousSentAt != 0 && now > previousSentAt)
                    ActualFps = Math.Round((sequence - previousSequence) * (double)System.Diagnostics.Stopwatch.Frequency / (now - previousSentAt), 2);
                previousSequence = sequence; previousSentAt = now;
                return true;
            }
            finally { frame.Bitmap.Dispose(); }
        }

        private long _sendSequence;
        private long _capturedAt;
        private bool _inflightFull;

        private void Acknowledged(long sequence, bool accepted, string dropReason)
        {
            lock (_sync)
            {
                long released = 0;
                var stale = new List<long>();
                foreach (var pair in _inflight)
                {
                    if (pair.Key <= sequence) { released += pair.Value; stale.Add(pair.Key); }
                }
                foreach (var key in stale) _inflight.Remove(key);
                _inflightBytes = Math.Max(0, _inflightBytes - released);
                if (sequence > LastAcknowledgedSequence) LastAcknowledgedSequence = sequence;
            }
            if (!accepted && !string.IsNullOrEmpty(dropReason)) { DroppedFrames++; SetRejectDetail(DescribeReject(dropReason)); }
            else if (accepted) SetRejectDetail("");
        }

        private bool HasInflightCapacity()
        {
            lock (_sync) return _inflight.Count < MaximumInflightFrames && _inflightBytes < MaximumInflightBytes;
        }

        private long NextCapturedAt()
        {
            long now = NowTicks() * 1000L / System.Diagnostics.Stopwatch.Frequency;
            if (now <= _capturedAt) now = _capturedAt + 1;
            _capturedAt = now;
            return now;
        }

        private long IntervalTicks() => System.Diagnostics.Stopwatch.Frequency / TargetFps;
        private static long NowTicks() => System.Diagnostics.Stopwatch.GetTimestamp();
        private static int ComputeWaitMs(long now, long due)
            => (int)Math.Max(1, Math.Min(200, (due - now) * 1000L / System.Diagnostics.Stopwatch.Frequency));

        private void ClearQueue()
        {
            lock (_sync)
            {
                _inflight.Clear(); _inflightBytes = 0;
            }
        }

        private void SetStatus(PublishStatus status, string detail)
        {
            var next = detail ?? "";
            bool changed = Status != status || _statusDetail != next;
            Status = status; _statusDetail = next;
            if (changed)
            {
                Utils.MediaDiagnostics.Write($"[Publish] status={status} detail={next}");
                StatusChanged?.Invoke(this, EventArgs.Empty);
            }
        }

        /// <summary>
        /// 服务端回执里明确拒收时把原因挂到状态说明上：未绑定目标这类问题只会体现在回执里，
        /// 不显示出来界面就一直是「推流中」，用户无法判断服务端到底有没有收下画面。
        /// </summary>
        private void SetRejectDetail(string detail)
        {
            if (_rejectDetail == detail) return;
            _rejectDetail = detail;
            Utils.MediaDiagnostics.Write($"[Publish] reject={detail}");
            StatusChanged?.Invoke(this, EventArgs.Empty);
        }

        private static string DescribeReject(string reason) => reason switch
        {
            "unbound" => "服务端未绑定目标：请在控制台关联推理节点",
            "consumer-unavailable" => "目标推理节点未接入媒体通道",
            _ => "",
        };

        private static string Describe(Exception ex)
            => ex is WebSocketException || ex is IOException ? "媒体连接中断" : ex.Message;

        private void Send(MinimalWebSocketClient socket, object value)
            => socket.SendAsync(new ArraySegment<byte>(Encoding.UTF8.GetBytes(SimpleJson.ToJson(value))),
                WebSocketMessageType.Text, true, _stop.Token).GetAwaiter().GetResult();

        /// <summary>等比缩放到契约上限；超出才缩放，不做放大。返回的位图由调用方 Dispose。</summary>
        public static Bitmap ScaleToContract(Bitmap source)
        {
            int width = source.Width, height = source.Height;
            int longEdge = Math.Max(width, height), shortEdge = Math.Min(width, height);
            if (longEdge <= MaximumLongEdge && shortEdge <= MaximumShortEdge) return new Bitmap(source);
            double scale = Math.Min(MaximumLongEdge / (double)longEdge, MaximumShortEdge / (double)shortEdge);
            int targetW = Math.Max(1, (int)Math.Round(width * scale)), targetH = Math.Max(1, (int)Math.Round(height * scale));
            var scaled = new Bitmap(targetW, targetH, PixelFormat.Format24bppRgb);
            using (var graphics = Graphics.FromImage(scaled))
            {
                graphics.InterpolationMode = InterpolationMode.HighQualityBicubic;
                graphics.PixelOffsetMode = PixelOffsetMode.HighQuality;
                graphics.DrawImage(source, 0, 0, targetW, targetH);
            }
            return scaled;
        }

        /// <summary>编码为 JPEG（质量 55）并自检 EOI 与 SOF 尺寸，不合格的帧直接丢弃。</summary>
        private static byte[]? EncodeJpeg(PublishedFrame frame)
        {
            using (var scaled = ScaleToContract(frame.Bitmap))
            using (var stream = new MemoryStream())
            {
                var codec = FindJpegCodec();
                if (codec == null) return null;
                var parameters = new EncoderParameters(1);
                parameters.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, JpegQuality);
                scaled.Save(stream, codec, parameters);
                byte[] bytes = stream.ToArray();
                if (bytes.Length < 4 || bytes[0] != 0xff || bytes[1] != 0xd8
                    || bytes[bytes.Length - 2] != 0xff || bytes[bytes.Length - 1] != 0xd9) return null;
                return JpegDimensionsMatch(bytes, scaled.Width, scaled.Height) ? bytes : null;
            }
        }

        private static ImageCodecInfo? FindJpegCodec()
        {
            foreach (var codec in ImageCodecInfo.GetImageEncoders())
                if (codec.MimeType == "image/jpeg") return codec;
            return null;
        }

        /// <summary>自检 SOF 宽高与头一致，避免把中继判为非法帧的包发出去。</summary>
        private static bool JpegDimensionsMatch(byte[] bytes, int width, int height)
        {
            int position = 2;
            while (position + 3 < bytes.Length)
            {
                if (bytes[position++] != 0xff) return false;
                while (bytes[position] == 0xff) position++;
                byte marker = bytes[position++];
                if (marker == 0xda || marker == 0xd9) return false;
                if (marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
                if (position + 2 > bytes.Length) return false;
                int length = (bytes[position] << 8) | bytes[position + 1];
                if (length < 2 || position + length > bytes.Length) return false;
                if (marker == 0xc0 || marker == 0xc1 || marker == 0xc2)
                {
                    if (length < 8) return false;
                    int sofHeight = (bytes[position + 3] << 8) | bytes[position + 4];
                    int sofWidth = (bytes[position + 5] << 8) | bytes[position + 6];
                    return sofWidth == width && sofHeight == height;
                }
                position += length;
            }
            return false;
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            Stop();
            _stop.Dispose();
            _wake.Dispose();
            _ready.Dispose();
        }
    }

    public enum PublishStatus { Idle, Connecting, Streaming, Faulted, Stopped }

    public readonly struct PublishedFrame
    {
        public Bitmap Bitmap { get; }
        public int Width => Bitmap.Width;
        public int Height => Bitmap.Height;
        public PublishedFrame(Bitmap bitmap) { Bitmap = bitmap; }
    }
}
