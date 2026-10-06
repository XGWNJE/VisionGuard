using System;
using System.Collections.Generic;
using System.IO;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Diagnostics;
using VisionGuard.Detector.Windows.Capture;
using VisionGuard.Detector.Windows.Net;
using VisionGuard.Detector.Windows.Utils;

namespace VisionGuard.Detector.Windows.Services
{
    public sealed class RemoteMediaService : IDisposable
    {
        private readonly CancellationTokenSource _stop = new CancellationTokenSource();
        private readonly string _url, _token, _deviceId;
        private MinimalWebSocketClient? _socket;
        private readonly Thread _worker;
        public RemoteMediaService(string url, string token, string deviceId)
        {
            _url = url.TrimEnd('/').Replace("https://", "wss://").Replace("http://", "ws://") + "/media/ws"; _token = token;
            _deviceId = deviceId;
            RemoteFrameStore.Shared.Attach(this);
            _worker = new Thread(Run) { IsBackground = true, Name = "VG_RemoteMedia" }; _worker.Start();
        }
        private void Send(MinimalWebSocketClient socket, object value)
        {
            byte[] bytes = Encoding.UTF8.GetBytes(SimpleJson.ToJson(value));
            socket.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, _stop.Token).GetAwaiter().GetResult();
        }
        private void Run()
        {
            while (!_stop.IsCancellationRequested)
            {
                try
                {
                    using (var ws = new MinimalWebSocketClient(new Uri(_url), maxMessageBytes: RemoteFrameStore.MaximumPayloadBytes + RemoteFrameStore.MaximumHeaderBytes + 4))
                    {
                        _socket = ws;
                        ws.ConnectAsyncInternal(_stop.Token).GetAwaiter().GetResult();
                        Send(ws, new { type = "media-auth", token = _token, direction = "subscribe" });
                        long lastResponse = Stopwatch.GetTimestamp(); int mediaReady = 0, checking = 0;
                        using (var health = new Timer(_ => {
                            if (Interlocked.Exchange(ref checking, 1) != 0) return;
                            try {
                                if (_stop.IsCancellationRequested || ws.State != WebSocketState.Open) return;
                                if ((Stopwatch.GetTimestamp() - Interlocked.Read(ref lastResponse)) * 1000d / Stopwatch.Frequency >= 12000) { ws.Abort(); return; }
                                if (Volatile.Read(ref mediaReady) != 0) Send(ws, new { type = "media-heartbeat" });
                            } catch { ws.Abort(); }
                            finally { Volatile.Write(ref checking, 0); }
                        }, null, 3000, 3000))
                        {
                        byte[] buffer = new byte[64 * 1024];
                        long previousFrameAt = 0;
                        while (!_stop.IsCancellationRequested)
                        {
                            using (var message = new MemoryStream())
                            {
                                WebSocketReceiveResult result;
                                do
                                {
                                    result = ws.ReceiveAsync(new ArraySegment<byte>(buffer), _stop.Token).GetAwaiter().GetResult();
                                    if (result.MessageType == WebSocketMessageType.Close) throw new IOException("媒体连接已关闭。");
                                    if (message.Length + result.Count > RemoteFrameStore.MaximumPayloadBytes + RemoteFrameStore.MaximumHeaderBytes + 4) throw new InvalidDataException("媒体消息超过上限。");
                                    message.Write(buffer, 0, result.Count);
                                } while (!result.EndOfMessage);
                                if (result.MessageType == WebSocketMessageType.Binary)
                                {
                                    long arrivedAt = Stopwatch.GetTimestamp();
                                    var header = RemoteFrameStore.Shared.Accept(message.ToArray(), this);
                                    long decodedAt = Stopwatch.GetTimestamp();
                                    Send(ws, new { type = "frame-received", header.streamId, header.sessionId, header.sequence });
                                    if (MediaDiagnostics.Enabled)
                                        MediaDiagnostics.Write(string.Format(System.Globalization.CultureInfo.InvariantCulture,
                                            "[MediaPerf] event=receive sequence={0} packetBytes={1} gapMs={2:F3} decodeMs={3:F3} ackSendMs={4:F3}",
                                            header.sequence, message.Length, previousFrameAt == 0 ? 0 : (arrivedAt - previousFrameAt) * 1000d / Stopwatch.Frequency,
                                            (decodedAt - arrivedAt) * 1000d / Stopwatch.Frequency, (Stopwatch.GetTimestamp() - decodedAt) * 1000d / Stopwatch.Frequency));
                                    previousFrameAt = arrivedAt;
                                    Interlocked.Exchange(ref lastResponse, Stopwatch.GetTimestamp());
                                }
                                else
                                {
                                    var json = SimpleJson.ParseDict(Encoding.UTF8.GetString(message.ToArray()));
                                    string type = SimpleJson.GetString(json, "type");
                                    if (type == "media-error" || type == "auth-result" && json.TryGetValue("success", out var value) && value is bool success && !success) throw new InvalidOperationException("媒体鉴权或绑定失败。");
                                    if (type == "stream-list") {
                                        var payload = System.Text.Json.JsonSerializer.Deserialize<MediaStreamList>(Encoding.UTF8.GetString(message.ToArray()));
                                        if (payload?.streams != null) RemoteFrameStore.Shared.SetStreams(payload.streams, _deviceId, this);
                                    }
                                    if (type == "media-ready") Volatile.Write(ref mediaReady, 1);
                                    if (type == "media-ready" || type == "media-heartbeat-ack" || type == "stream-list") Interlocked.Exchange(ref lastResponse, Stopwatch.GetTimestamp());
                                }
                            }
                        }
                        }
                    }
                }
                catch (Exception ex) { if (!_stop.IsCancellationRequested) LogManager.StaticWarn("[Media] " + ex.Message); }
                finally { _socket = null; RemoteFrameStore.Shared.ClearFrames(this); }
                if (_stop.Token.WaitHandle.WaitOne(2000)) break;
            }
        }
        private sealed class MediaStreamList { public RemoteStreamInfo[] streams { get; set; } = Array.Empty<RemoteStreamInfo>(); }
        public void Dispose()
        {
            _stop.Cancel(); _socket?.Abort(); _worker.Join(2000); RemoteFrameStore.Shared.Detach(this);
        }
    }
}
