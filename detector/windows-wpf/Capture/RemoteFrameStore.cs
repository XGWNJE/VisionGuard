using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Text;
using System.Text.Json;

namespace VisionGuard.Detector.Windows.Capture
{
    public sealed class RemoteStreamInfo
    {
        public string streamId { get; set; } = "";
        public string publisherDeviceId { get; set; } = "";
        public string publisherName { get; set; } = "";
        public string targetDeviceId { get; set; } = "";
        public string sourceId { get; set; } = "";
        public string sourceName { get; set; } = "";
        public bool isStreaming { get; set; }
        public string stopReason { get; set; } = "";
    }
    public sealed class RemoteFrameHeader
    {
        public string streamId { get; set; } = "";
        public string sessionId { get; set; } = "";
        public long sequence { get; set; }
        public long capturedAt { get; set; }
        public long receivedAt { get; set; }
        public int width { get; set; }
        public int height { get; set; }
        public int rotation { get; set; }
        [System.Text.Json.Serialization.JsonIgnore]
        public long LocalReceivedTicks { get; set; }
    }
    public sealed class RemoteFrameUnavailableException : IOException
    {
        public bool ExpectedStop { get; }
        public bool WaitingForNext { get; }
        public RemoteFrameUnavailableException(string message, bool expectedStop = false, bool waitingForNext = false) : base(message) { ExpectedStop = expectedStop; WaitingForNext = waitingForNext; }
    }
    // Only one decoded frame per stream. The inference caller owns a clone, not this cache.
    public sealed class RemoteFrameStore : IDisposable
    {
        public const int MaximumPayloadBytes = 2 * 1024 * 1024;
        public const int MaximumHeaderBytes = 4096;
        public const int MaximumFrameAgeMs = 3000;
        public static RemoteFrameStore Shared { get; } = new RemoteFrameStore();
        public event Action<string>? FrameAvailable;
        private readonly object _sync = new object();
        private object? _owner;
        private readonly Dictionary<string, Entry> _frames = new Dictionary<string, Entry>(StringComparer.Ordinal);
        private readonly Dictionary<string, RemoteStreamInfo> _streams = new Dictionary<string, RemoteStreamInfo>(StringComparer.Ordinal);
        private sealed class Entry
        {
            public Bitmap Frame = null!;
            public RemoteFrameHeader Header = null!;
            public long ReceivedTicks;
        }
        public void Attach(object owner) { lock (_sync) { Clear(); _owner = owner; } }
        public void SetStreams(IEnumerable<RemoteStreamInfo> values, string targetDeviceId, object? owner = null)
        {
            lock (_sync)
            {
                if (!ReferenceEquals(_owner, owner)) return;
                _streams.Clear();
                foreach (var stream in values)
                    if (stream.targetDeviceId == targetDeviceId && !string.IsNullOrWhiteSpace(stream.sourceId)) _streams[stream.streamId] = stream;
                foreach (var key in new List<string>(_frames.Keys))
                    if (!_streams.TryGetValue(key, out var stream) || IsExpectedStop(key)) { _frames[key].Frame.Dispose(); _frames.Remove(key); }
            }
        }
        public bool IsBound(string streamId) { lock (_sync) return _streams.ContainsKey(streamId); }
        public bool IsExpectedStop(string streamId)
        {
            lock (_sync) return _streams.TryGetValue(streamId, out var s) && !s.isStreaming && (s.stopReason == "user" || s.stopReason == "background" || s.stopReason == "locked");
        }
        public RemoteFrameHeader Accept(byte[] packet, object? owner = null)
        {
            if (packet == null || packet.Length < 6 || packet.Length > MaximumPayloadBytes + MaximumHeaderBytes + 4) throw new InvalidDataException("媒体帧大小无效。");
            uint headerLength = ((uint)packet[0] << 24) | ((uint)packet[1] << 16) | ((uint)packet[2] << 8) | packet[3];
            if (headerLength == 0 || headerLength > MaximumHeaderBytes || headerLength + 4 >= packet.Length) throw new InvalidDataException("媒体帧头无效。");
            var header = JsonSerializer.Deserialize<RemoteFrameHeader>(new UTF8Encoding(false, true).GetString(packet, 4, (int)headerLength)) ?? throw new InvalidDataException("媒体帧头为空。");
            if (string.IsNullOrWhiteSpace(header.streamId) || string.IsNullOrWhiteSpace(header.sessionId) || header.sequence < 0 || header.width <= 0 || header.height <= 0 || Math.Max(header.width, header.height) > 1280 || Math.Min(header.width, header.height) > 720 || (header.rotation != 0 && header.rotation != 90 && header.rotation != 180 && header.rotation != 270)) throw new InvalidDataException("媒体尺寸或帧序号无效。");
            int offset = 4 + (int)headerLength;
            if (packet.Length - offset < 2 || packet.Length - offset > MaximumPayloadBytes || packet[offset] != 0xff || packet[offset + 1] != 0xd8) throw new InvalidDataException("媒体必须是 JPEG。");
            // The relay stamps receivedAt for diagnostics. Cache expiry below uses this machine's monotonic clock.
            lock (_sync)
            {
                if (!ReferenceEquals(_owner, owner)) throw new IOException("媒体连接已被替换。");
                if (!_streams.TryGetValue(header.streamId, out var stream)) throw new InvalidDataException("镜头来源尚未授权绑定。");
                if (_frames.TryGetValue(header.streamId, out var old) && old.Header.sessionId == header.sessionId && header.sequence <= old.Header.sequence) throw new InvalidDataException("媒体帧序号重复或倒退。");
                using (var bytes = new MemoryStream(packet, offset, packet.Length - offset, false))
                using (var image = Image.FromStream(bytes, false, true))
                {
                    if (image.Width != header.width || image.Height != header.height) throw new InvalidDataException("JPEG 尺寸与帧头不一致。");
                    var bitmap = new Bitmap(image);
                    if (header.rotation == 90) bitmap.RotateFlip(RotateFlipType.Rotate90FlipNone);
                    else if (header.rotation == 180) bitmap.RotateFlip(RotateFlipType.Rotate180FlipNone);
                    else if (header.rotation == 270) bitmap.RotateFlip(RotateFlipType.Rotate270FlipNone);
                    old?.Frame.Dispose();
                    header.LocalReceivedTicks = Stopwatch.GetTimestamp();
                    _frames[header.streamId] = new Entry { Frame = bitmap, Header = header, ReceivedTicks = header.LocalReceivedTicks };
                    stream.isStreaming = true; stream.stopReason = null;
                }
            }
            FrameAvailable?.Invoke(header.streamId);
            return header;
        }
        public Bitmap ReadFresh(string streamId, ref string lastSession, ref long lastSequence, out RemoteFrameHeader header)
        {
            lock (_sync)
            {
                if (IsExpectedStop(streamId)) throw new RemoteFrameUnavailableException("镜头已主动停止推流。", true);
                if (!_frames.TryGetValue(streamId, out var entry)) throw new RemoteFrameUnavailableException("等待镜头新画面。");
                double age = (Stopwatch.GetTimestamp() - entry.ReceivedTicks) * 1000d / Stopwatch.Frequency;
                if (age > MaximumFrameAgeMs) throw new RemoteFrameUnavailableException("镜头画面已停滞，等待恢复。");
                if (lastSession == entry.Header.sessionId && lastSequence == entry.Header.sequence) throw new RemoteFrameUnavailableException("等待镜头下一帧。", waitingForNext: true);
                var clone = (Bitmap)entry.Frame.Clone();
                lastSession = entry.Header.sessionId; lastSequence = entry.Header.sequence; header = entry.Header;
                return clone;
            }
        }
        public Bitmap Peek(string streamId)
        {
            string session = ""; long sequence = -1;
            return ReadFresh(streamId, ref session, ref sequence, out _);
        }
        public void ClearFrames(object? owner = null) { lock (_sync) { if (!ReferenceEquals(_owner, owner)) return; foreach (var e in _frames.Values) e.Frame.Dispose(); _frames.Clear(); } }
        public void Detach(object owner) { lock (_sync) { if (!ReferenceEquals(_owner, owner)) return; Clear(); _owner = null; } }
        public void Clear() { lock (_sync) { ClearFrames(_owner); _streams.Clear(); } }
        public void Dispose() => Clear();
    }
}
