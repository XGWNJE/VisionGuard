using System;
using System.IO;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace VisionGuard.Net
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
        private const int MaxMessageBytes = 32 * 1024 * 1024;

        private readonly Uri _uri;
        private readonly int _connectTimeoutMs;
        private readonly int _sendTimeoutMs;
        private TcpClient _tcp;
        private Stream _stream;
        private System.Net.WebSockets.WebSocketState _state = System.Net.WebSockets.WebSocketState.None;

        public MinimalWebSocketClient(Uri uri, int connectTimeoutMs = 20000, int sendTimeoutMs = 15000)
        {
            _uri = uri;
            _connectTimeoutMs = connectTimeoutMs;
            _sendTimeoutMs = sendTimeoutMs;
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
            string host = _uri.Host;
            int port = _uri.Port > 0 ? _uri.Port : (_uri.Scheme == "wss" ? 443 : 80);
            bool secure = string.Equals(_uri.Scheme, "wss", StringComparison.OrdinalIgnoreCase);

            _tcp = new TcpClient { NoDelay = true };
            var connectTask = _tcp.ConnectAsync(host, port);
            if (!connectTask.Wait(_connectTimeoutMs)) throw new TimeoutException("TCP 连接超时: " + host + ":" + port);
            connectTask.GetAwaiter().GetResult();

            Stream stream = _tcp.GetStream();
            if (secure)
            {
                var ssl = new SslStream(stream, false, (sender, cert, chain, errors) => true);
                // 显式 TLS 1.2：Win7 的 SCHANNEL 默认不含 1.2，服务端只接受 1.2 及以上。
                ssl.AuthenticateAsClient(host, null, SslProtocols.Tls12, false);
                stream = ssl;
            }
            _stream = stream;
            _state = System.Net.WebSockets.WebSocketState.Connecting;

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
            var writeTask = _stream.WriteAsync(requestBytes, 0, requestBytes.Length, ct);
            if (!writeTask.Wait(_connectTimeoutMs)) throw new TimeoutException("握手请求写入超时");
            await _stream.FlushAsync(ct).ConfigureAwait(false);

            string responseHead = ReadHttpHead();
            if (string.IsNullOrEmpty(responseHead)) throw new IOException("服务端未返回握手响应（连接被关闭）");

            string statusLine = responseHead.Split(new[] { "\r\n" }, StringSplitOptions.None)[0];
            if (statusLine.IndexOf("101", StringComparison.Ordinal) < 0)
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
            _state = System.Net.WebSockets.WebSocketState.Open;
        }

        private string ReadHttpHead()
        {
            var builder = new StringBuilder();
            var single = new byte[1];
            var watch = System.Diagnostics.Stopwatch.StartNew();
            while (watch.ElapsedMilliseconds < _connectTimeoutMs)
            {
                int read = _stream.Read(single, 0, 1);
                if (read <= 0) break;
                builder.Append((char)single[0]);
                int length = builder.Length;
                if (length >= 4 && builder[length - 4] == '\r' && builder[length - 3] == '\n'
                    && builder[length - 2] == '\r' && builder[length - 1] == '\n') break;
            }
            return builder.ToString();
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

        public override async Task<System.Net.WebSockets.WebSocketReceiveResult> ReceiveAsync(
            ArraySegment<byte> buffer,
            CancellationToken cancellationToken)
        {
            var message = new MemoryStream();
            var messageType = System.Net.WebSockets.WebSocketMessageType.Text;

            while (true)
            {
                Frame frame = ReadFrame();
                if (frame == null) throw new IOException("连接已被服务端关闭");

                switch (frame.Opcode)
                {
                    case 0x1: messageType = System.Net.WebSockets.WebSocketMessageType.Text; break;
                    case 0x2: messageType = System.Net.WebSockets.WebSocketMessageType.Binary; break;
                    case 0x8:
                        _state = System.Net.WebSockets.WebSocketState.CloseReceived;
                        byte[] closePayload = frame.Payload;
                        var closeStatus = closePayload != null && closePayload.Length >= 2
                            ? (System.Net.WebSockets.WebSocketCloseStatus)((closePayload[0] << 8) | closePayload[1])
                            : System.Net.WebSockets.WebSocketCloseStatus.NormalClosure;
                        string description = closePayload != null && closePayload.Length > 2
                            ? Encoding.UTF8.GetString(closePayload, 2, closePayload.Length - 2) : string.Empty;
                        return new System.Net.WebSockets.WebSocketReceiveResult(
                            0, System.Net.WebSockets.WebSocketMessageType.Close, true, closeStatus, description);
                    case 0x9:
                        WriteFrame(0xA, frame.Payload, 0, frame.Payload == null ? 0 : frame.Payload.Length);
                        continue;
                    case 0xA:
                        continue;
                    default:
                        continue;
                }

                if (frame.Payload != null && frame.Payload.Length > 0)
                {
                    if (message.Length + frame.Payload.Length > MaxMessageBytes)
                        throw new IOException("单条消息超过上限(" + MaxMessageBytes + " 字节)");
                    message.Write(frame.Payload, 0, frame.Payload.Length);
                }
                if (frame.Fin) break;
            }

            byte[] data = message.ToArray();
            int length2 = Math.Min(data.Length, buffer.Count);
            Array.Copy(data, 0, buffer.Array, buffer.Offset, length2);
            return new System.Net.WebSockets.WebSocketReceiveResult(length2, messageType, true);
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

            if (length < 0 || length > MaxMessageBytes) throw new IOException("帧长度非法: " + length);

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
            try { if (_stream != null) _stream.Dispose(); } catch { }
            try { if (_tcp != null) _tcp.Close(); } catch { }
        }

        public override void Dispose() => Abort();
    }
}
