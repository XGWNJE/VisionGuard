using System;
using System.IO;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using VisionGuard.Detector.Windows.Net;

internal static class Program
{
#if NETFRAMEWORK
    static int Main(string[] args)
    {
        using (var ws = new MinimalWebSocketClient(new Uri(args[0]), connectTimeoutMs: 2000))
        {
            try
            {
                ws.ConnectAsyncInternal(CancellationToken.None).GetAwaiter().GetResult();
                ws.SendAsync(new ArraySegment<byte>(Encoding.UTF8.GetBytes("synthetic-auth-marker")), WebSocketMessageType.Text, true, CancellationToken.None).GetAwaiter().GetResult();
                return args[1] == "accept" ? 0 : 1;
            }
            catch (Exception ex)
            {
                Console.WriteLine(ex.GetType().Name + ": " + ex.Message);
                return args[1] == "reject" && ws.State == WebSocketState.Aborted ? 0 : 2;
            }
        }
    }
#else
    static async Task Main(string[] args)
    {
        var exe = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "../net472/WebSocketTls.Probe.exe"));
        if (Array.IndexOf(args, "--local-only") < 0)
        {
            using var echo = System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(exe, "wss://ws.postman-echo.com/raw accept") { UseShellExecute = false, CreateNoWindow = true });
            await echo.WaitForExitAsync();
            if (echo.ExitCode != 0) throw new Exception("Trusted WSS echo failed");
            Console.WriteLine("PASS valid public certificate (net472 production client, synthetic payload only)");
        }
        foreach (var scenario in new[] { "untrusted", "expired", "future", "wrong-domain", "stalled-tls", "stalled-upgrade" })
        {
            using var rsa = RSA.Create(2048);
            var request = new CertificateRequest("CN=VisionGuard isolated TLS " + Guid.NewGuid(), rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
            var san = new SubjectAlternativeNameBuilder();
            san.AddDnsName(scenario == "wrong-domain" ? "invalid.example" : "localhost");
            request.CertificateExtensions.Add(san.Build());
            request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
            request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection { new Oid("1.3.6.1.5.5.7.3.1") }, false));
            var start = scenario == "expired" ? DateTimeOffset.UtcNow.AddDays(-3) : scenario == "future" ? DateTimeOffset.UtcNow.AddDays(1) : DateTimeOffset.UtcNow.AddMinutes(-5);
            var end = scenario == "expired" ? DateTimeOffset.UtcNow.AddDays(-1) : DateTimeOffset.UtcNow.AddDays(3);
            using var certificate = request.CreateSelfSigned(start, end);
            var listener = new TcpListener(IPAddress.Loopback, 0);
            using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            int applicationBytes = 0;
            try
            {
                listener.Start();
                int port = ((IPEndPoint)listener.LocalEndpoint).Port;
                var server = Task.Run(async () =>
                {
                    using var tcp = await listener.AcceptTcpClientAsync(deadline.Token);
                    using var ssl = new SslStream(tcp.GetStream());
                    try
                    {
                        if (scenario == "stalled-tls")
                        {
                            var bytes = new byte[4096];
                            while (await tcp.GetStream().ReadAsync(bytes, deadline.Token) > 0) { }
                            return;
                        }
                        if (scenario == "stalled-upgrade")
                        {
                            var bytes = new byte[4096];
                            while (await tcp.GetStream().ReadAsync(bytes, deadline.Token) > 0) applicationBytes++;
                            return;
                        }
                        await ssl.AuthenticateAsServerAsync(new SslServerAuthenticationOptions { ServerCertificate = certificate, EnabledSslProtocols = SslProtocols.Tls12 }, deadline.Token);
                        var head = new StringBuilder();
                        var single = new byte[1];
                        while (await ssl.ReadAsync(single, deadline.Token) > 0)
                        {
                            applicationBytes++;
                            head.Append((char)single[0]);
                            if (head.ToString().EndsWith("\r\n\r\n")) break;
                        }
                        if (scenario == "stalled-upgrade")
                        {
                            while (await ssl.ReadAsync(single, deadline.Token) > 0) applicationBytes++;
                            return;
                        }
                        string key = null;
                        foreach (var line in head.ToString().Split('\n'))
                            if (line.StartsWith("Sec-WebSocket-Key:")) key = line.Substring(18).Trim();
                        if (key == null) return;
                        var accept = Convert.ToBase64String(SHA1.HashData(Encoding.ASCII.GetBytes(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")));
                        await ssl.WriteAsync(Encoding.ASCII.GetBytes("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n"), deadline.Token);
                        var auth = new byte[4096];
                        applicationBytes += await ssl.ReadAsync(auth, deadline.Token);
                    }
                    catch (Exception ex) when (ex is IOException || ex is AuthenticationException || ex is OperationCanceledException) { }
                });
                using var client = System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(exe, (scenario == "stalled-upgrade" ? "ws" : "wss") + "://localhost:" + port + "/ws reject") { UseShellExecute = false, CreateNoWindow = true });
                await client.WaitForExitAsync(deadline.Token);
                await server.WaitAsync(deadline.Token);
                if (client.ExitCode != 0 || scenario is "untrusted" or "expired" or "future" or "wrong-domain" && applicationBytes != 0)
                    throw new Exception(scenario + " failed; client=" + client.ExitCode + ", applicationBytes=" + applicationBytes);
                Console.WriteLine("PASS " + scenario + " (net472 production client, bytes=" + applicationBytes + ")");
            }
            finally
            {
                listener.Stop();
            }
        }
    }
#endif
}
