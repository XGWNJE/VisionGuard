using System;
using System.Collections.Generic;
using System.IO;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using VisionGuard.Detector.Windows.Capture;
using VisionGuard.Detector.Windows.Net;
using VisionGuard.Detector.Windows.Utils;

namespace VisionGuard.Detector.Windows.Services
{
    public sealed class RemoteMediaService : IDisposable
    {
        private readonly CancellationTokenSource _stop = new CancellationTokenSource();
        private readonly string _url, _token;
        private MinimalWebSocketClient? _socket;
        private readonly Thread _worker;
        public RemoteMediaService(string url, string token)
        {
            _url = url.TrimEnd('/').Replace("https://", "wss://").Replace("http://", "ws://") + "/media/ws"; _token = token;
            _worker = new Thread(Run) { IsBackground = true, Name = "VG_RemoteMedia" }; _worker.Start();
        }
        private void Send(object value)
        {
            byte[] bytes = Encoding.UTF8.GetBytes(SimpleJson.ToJson(value));
            _socket!.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, _stop.Token).GetAwaiter().GetResult();
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
                        Send(new { type = "media-auth", token = _token, direction = "subscribe" });
                        byte[] buffer = new byte[64 * 1024];
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
                                    var header = RemoteFrameStore.Shared.Accept(message.ToArray());
                                    Send(new { type = "frame-received", header.streamId, header.sessionId, header.sequence });
                                }
                                else
                                {
                                    var json = SimpleJson.ParseDict(Encoding.UTF8.GetString(message.ToArray()));
                                    string type = SimpleJson.GetString(json, "type");
                                    if (type == "media-error" || type == "auth-result" && json.TryGetValue("success", out var value) && value is bool success && !success) throw new InvalidOperationException("媒体鉴权或绑定失败。");
                                }
                            }
                        }
                    }
                }
                catch (Exception ex) { if (!_stop.IsCancellationRequested) LogManager.StaticWarn("[Media] " + ex.Message); }
                finally { _socket = null; RemoteFrameStore.Shared.ClearFrames(); }
                if (_stop.Token.WaitHandle.WaitOne(2000)) break;
            }
        }
        public void Dispose()
        {
            _stop.Cancel(); _socket?.Abort(); _worker.Join(2000); RemoteFrameStore.Shared.ClearFrames();
        }
    }
}
