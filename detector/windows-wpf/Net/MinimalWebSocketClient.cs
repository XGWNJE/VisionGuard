using System;
using System.IO;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace VisionGuard.Detector.Windows.Net
{
    /// <summary>
    /// 自研最小 WebSocket 客户端，用于替代两条在 Windows 7 上走不通的路径：
    ///
    ///  - `System.Net.WebSockets.ClientWebSocket`（.NET Framework）依赖系统 WebSocket 组件，
    ///    该组件自 Windows 8 起才提供，Win7 上必然抛 `PlatformNotSupportedException`。
    ///  - `websocket-sharp` 1.0.0 内部以 `SslProtocols.Default` 协商 TLS，Win7 上退化为 TLS 1.0，
    ///    被服务端拒绝（实测 1006 关闭且 300ms 内即失败），且它不暴露任何 TLS 版本配置入口。
    ///
    /// 本实现继承 `System.Net.WebSockets.WebSocket` 抽象类，因此对上层是 `ClientWebSocket`
    /// 的直接替代品（`SendAsync`/`ReceiveAsync`/`State`/`Abort` 用法一致），并显式使用 TLS 1.2。
    ///
    /// 能力边界（当前协议只需要这些）：
    ///  - 发送：文本帧与二进制帧，带掩码（客户端到服务端必须掩码）。
    ///  - 接收：文本帧、二进制帧、ping→pong、close，支持分片与 16/64 位长度。
    ///  - 未实现：permessage-deflate 压缩、扩展协商、子协议协商。当前服务端均未使用；
    ///    若将来启用，必须按 RFC 6455 补实现与测试，不能静默忽略。
    /// </summary>
    internal sealed class MinimalWebSocketClient : System.Net.WebSockets.WebSocket
    {
        private readonly int _maxMessageBytes;
        private readonly object _writeSync = new object();
        private byte[] _pendingMessage;
        private int _pendingOffset;
        private System.Net.WebSockets.WebSocketMessageType _pendingType;

        private readonly Uri _uri;
        private readonly int _connectTimeoutMs;
        private readonly int _sendTimeoutMs;
        private TcpClient _tcp;
        private Stream _stream;
        private System.Net.WebSockets.WebSocketState _state = System.Net.WebSockets.WebSocketState.None;

        public MinimalWebSocketClient(Uri uri, int connectTimeoutMs = 20000, int sendTimeoutMs = 15000, int maxMessageBytes = 32 * 1024 * 1024)
        {
            _uri = uri;
            _connectTimeoutMs = connectTimeoutMs;
            _sendTimeoutMs = sendTimeoutMs;
            if (maxMessageBytes < 1) throw new ArgumentOutOfRangeException(nameof(maxMessageBytes));
            _maxMessageBytes = maxMessageBytes;
        }

        public override System.Net.WebSockets.WebSocketState State => _state;
        public override System.Net.WebSockets.WebSocketCloseStatus? CloseStatus => null;
        public override string CloseStatusDescription => null;
        public override string SubProtocol => null;

        /// <summary>握手摘要，供日志与验收取证。</summary>
        public string HandshakeSummary { get; private set; } = string.Empty;

        /// <summary>
        /// 建立 TCP（必要时 TLS）连接并完成 WebSocket 握手。
        /// net472 的 WebSocket 基类没有 ConnectAsync，因此本方法使用独立命名。
        /// </summary>
        public async Task ConnectAsyncInternal(CancellationToken ct)
        {
            if (_state != System.Net.WebSockets.WebSocketState.None)
                throw new InvalidOperationException("WebSocket client cannot be reused.");
            if (_uri.Scheme != "ws" && _uri.Scheme != "wss")
                throw new ArgumentException("Only ws and wss endpoints are supported.");
            ct.ThrowIfCancellationRequested();
            _state = System.Net.WebSockets.WebSocketState.Connecting;
            using (var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct))
            using (timeout.Token.Register(Abort))
            {
            timeout.CancelAfter(_connectTimeoutMs);
            try
            {
            string host = _uri.Host;
            int port = _uri.Port > 0 ? _uri.Port : (_uri.Scheme == "wss" ? 443 : 80);
            bool secure = string.Equals(_uri.Scheme, "wss", StringComparison.OrdinalIgnoreCase);

            _tcp = new TcpClient { NoDelay = true };
            await _tcp.ConnectAsync(host, port).ConfigureAwait(false);
            timeout.Token.ThrowIfCancellationRequested();

            _stream = _tcp.GetStream();
            if (secure)
            {
                // 系统验证完整证书链、有效期和目标域名；不提供绕过验证的回调。
                // TLS 1.2 保留 Windows 7 SCHANNEL 的必要兼容。
                var ssl = new SslStream(_stream, false);
                _stream = ssl; // 握手失败或取消时也必须释放 TLS 和底层 TCP。
                await ssl.AuthenticateAsClientAsync(host, null, SslProtocols.Tls12, false).ConfigureAwait(false);
                timeout.Token.ThrowIfCancellationRequested();
            }

            string key = Convert.ToBase64String(Guid.NewGuid().ToByteArray());
            var head = new StringBuilder();
            head.Append("GET ").Append(_uri.PathAndQuery).Append(" HTTP/1.1\r\n");
            head.Append("Host: ").Append(host);
            if (port != 443 && port != 80) head.Append(':').Append(port);
            head.Append("\r\n");
            head.Append("Upgrade: websocket\r\n");
            head.Append("Connection: Upgrade\r\n");
            head.Append("Sec-WebSocket-Key: ").Append(key).Append("\r\n");
            head.Append("Sec-WebSocket-Version: 13\r\n");
            head.Append("\r\n");

            var requestBytes = Encoding.ASCII.GetBytes(head.ToString());
            await _stream.WriteAsync(requestBytes, 0, requestBytes.Length, timeout.Token).ConfigureAwait(false);
            await _stream.FlushAsync(timeout.Token).ConfigureAwait(false);

            string responseHead = await ReadHttpHeadAsync(timeout.Token).ConfigureAwait(false);
            if (string.IsNullOrEmpty(responseHead)) throw new IOException("服务端未返回握手响应（连接被关闭）");

            string statusLine = responseHead.Split(new[] { "\r\n" }, StringSplitOptions.None)[0];
            if (!statusLine.StartsWith("HTTP/1.1 101 ", StringComparison.Ordinal)
                || !string.Equals(ExtractHeader(responseHead, "Upgrade"), "websocket", StringComparison.OrdinalIgnoreCase))
            {
                HandshakeSummary = statusLine;
                throw new IOException("WebSocket 握手被拒绝: " + statusLine);
            }

            string accept = ExtractHeader(responseHead, "Sec-WebSocket-Accept");
            string expected = Convert.ToBase64String(SHA1.Create().ComputeHash(
                Encoding.ASCII.GetBytes(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")));
            if (!string.Equals(accept, expected, StringComparison.Ordinal))
                throw new IOException("Sec-WebSocket-Accept 校验失败: got=" + accept + " expected=" + expected);

            HandshakeSummary = statusLine + " | accept 校验通过";
            timeout.Token.ThrowIfCancellationRequested();
            _state = System.Net.WebSockets.WebSocketState.Open;
            timeout.CancelAfter(Timeout.Infinite);
            }
            catch
            {
                Abort();
                if (ct.IsCancellationRequested) throw new OperationCanceledException(ct);
                if (timeout.IsCancellationRequested) throw new TimeoutException("WebSocket 连接或握手超时");
                throw;
            }
            }
        }

        private async Task<string> ReadHttpHeadAsync(CancellationToken ct)
        {
            var builder = new StringBuilder();
            var single = new byte[1];
            while (builder.Length < 16 * 1024)
            {
                int read = await _stream.ReadAsync(single, 0, 1, ct).ConfigureAwait(false);
                if (read <= 0) break;
                builder.Append((char)single[0]);
                int length = builder.Length;
                if (length >= 4 && builder[length - 4] == '\r' && builder[length - 3] == '\n'
                    && builder[length - 2] == '\r' && builder[length - 1] == '\n') return builder.ToString();
            }
            throw new IOException("WebSocket 响应头不完整或过大");
        }

        private static string ExtractHeader(string head, string name)
        {
            foreach (var line in head.Split(new[] { "\r\n" }, StringSplitOptions.RemoveEmptyEntries))
            {
                int index = line.IndexOf(':');
                if (index <= 0) continue;
                if (string.Equals(line.Substring(0, index).Trim(), name, StringComparison.OrdinalIgnoreCase))
                    return line.Substring(index + 1).Trim();
            }
            return null;
        }

        public override Task SendAsync(
            ArraySegment<byte> buffer,
            System.Net.WebSockets.WebSocketMessageType messageType,
            bool endOfMessage,
            CancellationToken cancellationToken)
        {
            if (_state != System.Net.WebSockets.WebSocketState.Open) throw new IOException("连接未打开");
            byte opcode = messageType == System.Net.WebSockets.WebSocketMessageType.Binary ? (byte)0x2 : (byte)0x1;
            WriteFrame(opcode, buffer.Array, buffer.Offset, buffer.Count);
            return Task.CompletedTask;
        }

        private void WriteFrame(byte opcode, byte[] payload, int offset, int count)
        {
            lock (_writeSync) WriteFrameCore(opcode, payload, offset, count);
        }

        private void WriteFrameCore(byte opcode, byte[] payload, int offset, int count)
        {
            var header = new byte[14];
            int headerLength = 2;
            header[0] = (byte)(0x80 | opcode);
            if (count <= 125)
            {
                header[1] = (byte)(0x80 | count);
            }
            else if (count <= 65535)
            {
                header[1] = 0x80 | 126;
                header[2] = (byte)(count >> 8);
                header[3] = (byte)count;
                headerLength = 4;
            }
            else
            {
                header[1] = 0x80 | 127;
                headerLength = 10;
                for (int i = 0; i < 8; i++) header[2 + i] = (byte)((long)count >> (56 - 8 * i));
            }

            var mask = new byte[4];
            using (var random = RandomNumberGenerator.Create()) random.GetBytes(mask);
            Array.Copy(mask, 0, header, headerLength, 4);
            headerLength += 4;

            var frame = new byte[headerLength + count];
            Array.Copy(header, 0, frame, 0, headerLength);
            for (int i = 0; i < count; i++)
                frame[headerLength + i] = (byte)(payload[offset + i] ^ mask[i & 3]);

            var task = _stream.WriteAsync(frame, 0, frame.Length, CancellationToken.None);
            if (!task.Wait(_sendTimeoutMs)) throw new TimeoutException("发送超时(" + _sendTimeoutMs + "ms)");
        }

        public override Task<System.Net.WebSockets.WebSocketReceiveResult> ReceiveAsync(
            ArraySegment<byte> buffer,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (buffer.Array == null || buffer.Count == 0) throw new ArgumentException("A receive buffer is required.");
            using (cancellationToken.Register(Abort))
            {
                if (_pendingMessage == null)
                {
                    using (var message = new MemoryStream())
                    {
                        bool started = false;
                        while (true)
                        {
                            cancellationToken.ThrowIfCancellationRequested();
                            Frame frame = ReadFrame();
                            if (frame == null) throw new IOException("连接已被服务端关闭");
                            if (frame.Opcode == 0x8)
                            {
                                _state = System.Net.WebSockets.WebSocketState.CloseReceived;
                                var close = frame.Payload;
                                var code = close.Length >= 2 ? (System.Net.WebSockets.WebSocketCloseStatus)((close[0] << 8) | close[1]) : System.Net.WebSockets.WebSocketCloseStatus.NormalClosure;
                                return Task.FromResult(new System.Net.WebSockets.WebSocketReceiveResult(0, System.Net.WebSockets.WebSocketMessageType.Close, true, code, close.Length > 2 ? Encoding.UTF8.GetString(close, 2, close.Length - 2) : string.Empty));
                            }
                            if (frame.Opcode == 0x9) { WriteFrame(0xA, frame.Payload, 0, frame.Payload.Length); continue; }
                            if (frame.Opcode == 0xA) continue;
                            if (frame.Opcode == 0x0) { if (!started) throw new IOException("Unexpected continuation frame."); }
                            else if (frame.Opcode == 0x1 || frame.Opcode == 0x2)
                            {
                                if (started) throw new IOException("Fragmented message interrupted.");
                                started = true;
                                _pendingType = frame.Opcode == 0x2 ? System.Net.WebSockets.WebSocketMessageType.Binary : System.Net.WebSockets.WebSocketMessageType.Text;
                            }
                            else throw new IOException("Invalid WebSocket opcode.");
                            if (message.Length + frame.Payload.Length > _maxMessageBytes) throw new IOException("WebSocket message too large.");
                            message.Write(frame.Payload, 0, frame.Payload.Length);
                            if (frame.Fin) break;
                        }
                        _pendingMessage = message.ToArray(); _pendingOffset = 0;
                    }
                }
                int count = Math.Min(_pendingMessage.Length - _pendingOffset, buffer.Count);
                Array.Copy(_pendingMessage, _pendingOffset, buffer.Array, buffer.Offset, count);
                _pendingOffset += count;
                bool end = _pendingOffset == _pendingMessage.Length;
                if (end) { _pendingMessage = null; _pendingOffset = 0; }
                return Task.FromResult(new System.Net.WebSockets.WebSocketReceiveResult(count, _pendingType, end));
            }
        }

        private sealed class Frame
        {
            public bool Fin;
            public byte Opcode;
            public byte[] Payload;
        }

        private Frame ReadFrame()
        {
            var head = new byte[2];
            if (!ReadExact(head, 2)) return null;

            bool fin = (head[0] & 0x80) != 0;
            byte opcode = (byte)(head[0] & 0x0F);
            if ((head[0] & 0x70) != 0) throw new IOException("Unsupported WebSocket extension.");
            bool masked = (head[1] & 0x80) != 0;
            long length = head[1] & 0x7F;

            if (length == 126)
            {
                var extended = new byte[2];
                if (!ReadExact(extended, 2)) return null;
                length = (extended[0] << 8) | extended[1];
            }
            else if (length == 127)
            {
                var extended = new byte[8];
                if (!ReadExact(extended, 8)) return null;
                length = 0;
                for (int i = 0; i < 8; i++) length = (length << 8) | extended[i];
            }

            if (length < 0 || length > _maxMessageBytes || (opcode >= 8 && (!fin || length > 125))) throw new IOException("帧长度非法: " + length);

            byte[] mask = null;
            if (masked)
            {
                mask = new byte[4];
                if (!ReadExact(mask, 4)) return null;
            }

            var payload = new byte[length];
            if (length > 0 && !ReadExact(payload, (int)length)) return null;
            if (masked)
                for (int i = 0; i < payload.Length; i++) payload[i] = (byte)(payload[i] ^ mask[i & 3]);

            return new Frame { Fin = fin, Opcode = opcode, Payload = payload };
        }

        private bool ReadExact(byte[] target, int count)
        {
            int read = 0;
            while (read < count)
            {
                int chunk;
                try { chunk = _stream.Read(target, read, count - read); }
                catch (IOException) { return false; }
                catch (ObjectDisposedException) { return false; }
                if (chunk <= 0) return false;
                read += chunk;
            }
            return true;
        }

        public override Task CloseAsync(
            System.Net.WebSockets.WebSocketCloseStatus closeStatus,
            string statusDescription,
            CancellationToken cancellationToken)
        {
            try
            {
                var payload = new byte[2];
                payload[0] = (byte)((int)closeStatus >> 8);
                payload[1] = (byte)((int)closeStatus & 0xFF);
                WriteFrame(0x8, payload, 0, payload.Length);
            }
            catch
            {
                // 关闭帧发送失败不影响本地释放。
            }
            _state = System.Net.WebSockets.WebSocketState.Closed;
            return Task.CompletedTask;
        }

        public override Task CloseOutputAsync(
            System.Net.WebSockets.WebSocketCloseStatus closeStatus,
            string statusDescription,
            CancellationToken cancellationToken)
            => CloseAsync(closeStatus, statusDescription, cancellationToken);

        public override void Abort()
        {
            _state = System.Net.WebSockets.WebSocketState.Aborted;
            try { if (_tcp != null) _tcp.Close(); } catch { }
            try { if (_stream != null) _stream.Dispose(); } catch { }
        }

        public override void Dispose() => Abort();
    }
}
