using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using VisionGuard.Detector.Windows.Capture;
using VisionGuard.Detector.Windows.Net;
using VisionGuard.Detector.Windows.Services;
using VisionGuard.Detector.Windows.Utils;

internal static class Program
{
    static void Require(bool value, string message) { if (!value) throw new Exception(message); }
    static int Main(string[] args)
    {
        try
        {
            if (args.Length == 3 && args[0] == "--camera-observe")
            {
                AccountSession.ConfigureIsolatedEnvironment(args[1]); AccountSession.Load();
                Require(AccountSession.IsIsolated && new Uri(AccountSession.ServiceUrl).IsLoopback, "Real camera probe requires an isolated local service");
                var session = AccountSession.Current ?? throw new Exception("Prepare an isolated Windows session first");
                Directory.CreateDirectory(args[2]);
                using var transport = new ServerPushService();
                IReadOnlyList<RemoteStreamInfo> streams = Array.Empty<RemoteStreamInfo>(); var gate = new object();
                transport.StreamsReceived += (_, values) => { lock (gate) streams = values; };
                transport.UpdateHeartbeatParams(false, true, 5, .5f, "person", 3, "yolo26n_320", new[] { "yolo26n_320" });
                transport.Configure(AccountSession.ServiceUrl, session.token, session.device.deviceId, session.device.deviceName);
                var sequences = new Dictionary<string, (string session, long sequence)>(); int received = 0;
                var watch = Stopwatch.StartNew();
                while (watch.Elapsed < TimeSpan.FromSeconds(180))
                {
                    RemoteStreamInfo[] current; lock (gate) current = streams.ToArray();
                    foreach (var stream in current)
                    {
                        sequences.TryGetValue(stream.streamId, out var last); string id = last.session ?? ""; long sequence = last.sequence;
                        Bitmap frame;
                        try { frame = RemoteFrameStore.Shared.ReadFresh(stream.streamId, ref id, ref sequence, out var header); }
                        catch (RemoteFrameUnavailableException) { continue; }
                        using var releaseFrame = frame;
                        sequences[stream.streamId] = (id, sequence); received++;
                        frame.Save(Path.Combine(args[2], received == 1 ? "first-camera.jpg" : "latest-camera.jpg"), ImageFormat.Jpeg);
                        if (received == 1) Console.WriteLine($"CAMERA first frame {frame.Width}x{frame.Height} sequence={sequence}");
                        if (received == 30) Console.WriteLine("CAMERA received 30 fresh frames; continuing idle/restart observation.");
                    }
                    Thread.Sleep(50);
                }
                Require(received >= 30, $"Camera deadline: received {received} fresh frames");
                Console.WriteLine($"PASS real camera: {received} fresh decoded frames through the production Windows control/media services; no object recognition asserted."); return 0;
            }
            if (args.Length == 2 && args[0] == "--settings-environment")
            {
                SettingsEnvironmentChild(args[1]);
                Console.WriteLine("PASS isolated settings initialization/read/write"); return 0;
            }
            if (args.Length == 1 && args[0] == "--delayed-refresh")
            {
                AccountSession.Load(); Require(AccountSession.EnsureFresh() != null, "Child refresh failed");
                Console.WriteLine("PASS resident-like child refresh completed"); return 0;
            }
            if (args.Length == 2 && args[0] == "--inspect-environment")
            {
                AccountSession.ConfigureIsolatedEnvironment(args[1]);
                Console.WriteLine(JsonSerializer.Serialize(new { ok = true, service = AccountSession.ServiceUrl, accountDir = AccountSession.Root, scope = AccountSession.ScopeKey, appId = AccountSession.ApplicationId, deviceId = AccountSession.Current?.device.deviceId }));
                return 0;
            }
            if (args.Length >= 3 && args[0] == "--login")
            {
                string password = Environment.GetEnvironmentVariable("VISIONGUARD_LOGIN_PASSWORD") ?? throw new Exception("VISIONGUARD_LOGIN_PASSWORD is required.");
                var session = AccountSession.Login(args[1], args[2], password, args.Length > 3 ? args[3] : "Windows acceptance node");
                Console.WriteLine(JsonSerializer.Serialize(new { ok = true, session.account.accountId, session.device.deviceId, scope = AccountSession.ScopeKey, service = AccountSession.ServiceUrl, appId = AccountSession.ApplicationId }));
                return 0;
            }
            WebSocketMessageProbe();
            RemoteFrameProbe();
            AccountProbe();
            AccountThrottleProbe(false);
            AccountThrottleProbe(true);
            AlertClockProbe();
            AlertRegistrationProbe();
            DeviceIdentityMemoryProbe();
            AlertTimingsProbe();
            CrossProcessSessionProbe();
            IsolatedEnvironmentProbe();
            Console.WriteLine("PASS: complete binary/fragmented text, ping, bounded payload, cancellation, newest frame, sequence, expiry, expected stop, encrypted session, renewal, logout and account outbox isolation.");
            return 0;
        }
        catch (Exception ex) { Console.Error.WriteLine(ex); return 1; }
    }
    static void SendFrame(Stream stream, byte opcode, byte[] data, bool fin = true)
    {
        var header = new List<byte> { (byte)((fin ? 0x80 : 0) | opcode) };
        if (data.Length < 126) header.Add((byte)data.Length);
        else if (data.Length < 65536) { header.Add(126); header.Add((byte)(data.Length >> 8)); header.Add((byte)data.Length); }
        else { header.Add(127); for (int shift = 56; shift >= 0; shift -= 8) header.Add((byte)((long)data.Length >> shift)); }
        stream.Write(header.ToArray(), 0, header.Count); stream.Write(data, 0, data.Length); stream.Flush();
    }
    static void Handshake(NetworkStream stream)
    {
        var bytes = new List<byte>(); int b;
        while ((b = stream.ReadByte()) >= 0)
        {
            bytes.Add((byte)b);
            if (bytes.Count >= 4 && Encoding.ASCII.GetString(bytes.Skip(bytes.Count - 4).ToArray()) == "\r\n\r\n") break;
        }
        string request = Encoding.ASCII.GetString(bytes.ToArray());
        string key = request.Split(new[] { "\r\n" }, StringSplitOptions.None).First(line => line.StartsWith("Sec-WebSocket-Key:", StringComparison.OrdinalIgnoreCase)).Split(':')[1].Trim();
        string accept; using (var sha = SHA1.Create()) accept = Convert.ToBase64String(sha.ComputeHash(Encoding.ASCII.GetBytes(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")));
        byte[] response = Encoding.ASCII.GetBytes("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n");
        stream.Write(response, 0, response.Length); stream.Flush();
    }
    static byte[] Receive(MinimalWebSocketClient ws, WebSocketMessageType type, CancellationToken ct)
    {
        using var bytes = new MemoryStream();
        byte[] buffer = new byte[8192]; WebSocketReceiveResult result;
        do { result = ws.ReceiveAsync(new ArraySegment<byte>(buffer), ct).GetAwaiter().GetResult(); Require(result.MessageType == type, "Wrong message type"); bytes.Write(buffer, 0, result.Count); } while (!result.EndOfMessage);
        return bytes.ToArray();
    }
    static void WebSocketMessageProbe()
    {
        var listener = new TcpListener(IPAddress.Loopback, 0); listener.Start(); int port = ((IPEndPoint)listener.LocalEndpoint).Port;
        byte[] binary = Enumerable.Range(0, 420001).Select(i => (byte)(i % 251)).ToArray();
        byte[] text = Encoding.UTF8.GetBytes(new string('x', 70000));
        var fixture = Task.Run(() =>
        {
            using var peer = listener.AcceptTcpClient(); using var stream = peer.GetStream(); Handshake(stream);
            SendFrame(stream, 2, binary.Take(130000).ToArray(), false);
            SendFrame(stream, 9, Encoding.ASCII.GetBytes("ping"));
            SendFrame(stream, 0, binary.Skip(130000).ToArray());
            SendFrame(stream, 1, text.Take(11001).ToArray(), false); SendFrame(stream, 0, text.Skip(11001).ToArray());
            Thread.Sleep(1500);
        });
        using (var ws = new MinimalWebSocketClient(new Uri("ws://127.0.0.1:" + port + "/ws")))
        {
            ws.ConnectAsyncInternal(CancellationToken.None).GetAwaiter().GetResult();
            Require(binary.SequenceEqual(Receive(ws, WebSocketMessageType.Binary, CancellationToken.None)), "Large fragmented binary was truncated");
            Require(text.SequenceEqual(Receive(ws, WebSocketMessageType.Text, CancellationToken.None)), "Large fragmented text was truncated");
            using var cancel = new CancellationTokenSource(100);
            bool canceled = false; var elapsed = System.Diagnostics.Stopwatch.StartNew();
            try { ws.ReceiveAsync(new ArraySegment<byte>(new byte[16]), cancel.Token).GetAwaiter().GetResult(); } catch (Exception) { canceled = cancel.IsCancellationRequested; }
            Require(canceled && elapsed.ElapsedMilliseconds < 1200, "Cancellation did not interrupt a blocking socket read");
        }
        fixture.GetAwaiter().GetResult(); listener.Stop();
        var limitListener = new TcpListener(IPAddress.Loopback, 0); limitListener.Start(); int limitPort = ((IPEndPoint)limitListener.LocalEndpoint).Port;
        var tooLarge = Task.Run(() =>
        {
            using var peer = limitListener.AcceptTcpClient(); using var stream = peer.GetStream(); Handshake(stream);
            stream.Write(new byte[] { 0x82, 127, 0, 0, 0, 0, 2, 0, 0, 1 }, 0, 10); stream.Flush();
        });
        using (var ws = new MinimalWebSocketClient(new Uri("ws://127.0.0.1:" + limitPort + "/ws")))
        {
            ws.ConnectAsyncInternal(CancellationToken.None).GetAwaiter().GetResult(); bool rejected = false;
            try { ws.ReceiveAsync(new ArraySegment<byte>(new byte[16]), CancellationToken.None).GetAwaiter().GetResult(); } catch (IOException) { rejected = true; }
            Require(rejected, "Oversized websocket payload was not rejected");
        }
        tooLarge.GetAwaiter().GetResult(); limitListener.Stop();
    }
    static byte[] Packet(long sequence, Color color, string session = "generation-1")
    {
        using var image = new Bitmap(320, 180); using (var g = Graphics.FromImage(image)) g.Clear(color);
        var header = new RemoteFrameHeader { streamId = "probe-stream", sessionId = session, sequence = sequence, capturedAt = 1, receivedAt = 1, width = 320, height = 180 };
        byte[] json = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(header)); using var bytes = new MemoryStream();
        bytes.Write(new byte[] { (byte)(json.Length >> 24), (byte)(json.Length >> 16), (byte)(json.Length >> 8), (byte)json.Length }, 0, 4); bytes.Write(json, 0, json.Length); image.Save(bytes, ImageFormat.Jpeg); return bytes.ToArray();
    }
    static void RemoteFrameProbe()
    {
        using var frames = new RemoteFrameStore();
        int notifications = 0;
        frames.FrameAvailable += streamId => {
            Require(streamId == "probe-stream", "Notification was routed to another stream");
            // The decoded latest frame is already readable when the event fires.
            using var latest = frames.Peek(streamId);
            notifications++;
        };
        var stream = new RemoteStreamInfo { streamId = "probe-stream", targetDeviceId = "node-1", sourceId = "camera-1", isStreaming = true };
        stream.isStreaming = false;
        frames.SetStreams(new[] { stream }, "node-1"); frames.Accept(Packet(1, Color.Red)); frames.Accept(Packet(2, Color.Blue));
        byte[] shortJpeg = Packet(3, Color.Red); int headerSize = (shortJpeg[0] << 24) | (shortJpeg[1] << 16) | (shortJpeg[2] << 8) | shortJpeg[3];
        bool shortRejected = false; try { frames.Accept(shortJpeg.Take(headerSize + 5).ToArray()); } catch (InvalidDataException) { shortRejected = true; }
        Require(shortRejected, "One-byte JPEG payload was not cleanly rejected");
        Require(notifications == 2, "Invalid frames triggered processing notifications");
        string session = ""; long sequence = -1; using (var image = frames.ReadFresh("probe-stream", ref session, ref sequence, out var header)) Require(header.sequence == 2 && image.GetPixel(20, 20).B > 200, "Latest frame was not retained");
        bool noDuplicate = false; try { frames.ReadFresh("probe-stream", ref session, ref sequence, out _).Dispose(); } catch (RemoteFrameUnavailableException ex) { noDuplicate = ex.WaitingForNext; }
        Require(noDuplicate, "Same cached frame could advance inference again");
        bool backward = false; try { frames.Accept(Packet(1, Color.Green)); } catch (InvalidDataException) { backward = true; }
        Require(backward, "Backward sequence accepted");
        frames.Accept(Packet(0, Color.Green, "generation-2"));
        using (var image = frames.ReadFresh("probe-stream", ref session, ref sequence, out _)) Require(sequence == 0 && session == "generation-2", "New media generation was rejected");
        Thread.Sleep(RemoteFrameStore.MaximumFrameAgeMs + 30);
        bool expired = false; try { frames.Peek("probe-stream").Dispose(); } catch (RemoteFrameUnavailableException ex) { expired = !ex.ExpectedStop && !ex.WaitingForNext; }
        Require(expired, "Stale cached frame was processed");
        stream.isStreaming = false; stream.stopReason = "background"; frames.SetStreams(new[] { stream }, "node-1");
        bool expected = false; try { frames.Peek("probe-stream").Dispose(); } catch (RemoteFrameUnavailableException ex) { expected = ex.ExpectedStop; }
        Require(expected, "Background stop was interpreted as an abnormal outage");
        frames.SetStreams(new[] { stream }, "different-node"); Require(!frames.IsBound("probe-stream"), "Foreign device binding remained accessible");
        var oldOwner = new object(); var newOwner = new object();
        frames.Attach(oldOwner); frames.Attach(newOwner); stream.isStreaming = false; stream.stopReason = "";
        frames.SetStreams(new[] { stream }, "node-1", newOwner); frames.Accept(Packet(1, Color.Blue), newOwner);
        frames.ClearFrames(oldOwner); frames.Detach(oldOwner); frames.SetStreams(Array.Empty<RemoteStreamInfo>(), "node-1", oldOwner);
        using (var image = frames.Peek("probe-stream")) Require(image.GetPixel(20, 20).B > 200, "Old connection cleanup removed new frames");
        bool oldRejected = false; try { frames.Accept(Packet(2, Color.Red), oldOwner); } catch (IOException) { oldRejected = true; }
        Require(oldRejected, "Old media callback replaced a new connection frame");
        Require(notifications == 4, "Old connection callbacks advanced processing notifications");
    }
    static void AccountProbe()
    {
        string directory = Path.Combine(Path.GetTempPath(), "VisionGuard-AccountMedia-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(directory);
        Environment.SetEnvironmentVariable("VISIONGUARD_ACCOUNT_DIR", directory);
        var tcp = new TcpListener(IPAddress.Loopback, 0); tcp.Start(); int port = ((IPEndPoint)tcp.LocalEndpoint).Port; tcp.Stop();
        string url = "http://127.0.0.1:" + port; Environment.SetEnvironmentVariable("VISIONGUARD_SERVER_URL", url);
        using var listener = new HttpListener(); listener.Prefixes.Add(url + "/"); listener.Start();
        bool revoked = false; int calls = 0;
        using var refreshEntered = new ManualResetEvent(false);
        using var refreshRelease = new ManualResetEvent(false);
        var fixture = Task.Run(() =>
        {
            while (calls < 5)
            {
                var request = listener.GetContext(); calls++;
                using var reader = new StreamReader(request.Request.InputStream); string body = reader.ReadToEnd();
                string path = request.Request.Url!.AbsolutePath;
                if (path.EndsWith("refresh") || path.EndsWith("session")) { refreshEntered.Set(); Require(refreshRelease.WaitOne(5000), "Account HTTP test was not released"); }
                if (path.EndsWith("logout")) revoked = true;
                string user = body.Contains("second") ? "second" : "first";
                if (path.EndsWith("login")) Require(body.Contains("windows-inference") && !body.Contains("role"), "Client self-granted an identity");
                string token = path.EndsWith("refresh") ? "rotated-probe-token" : "probe-token-" + user;
                var snapshot = new AccountSnapshot { ok = true, token = token, expiresAt = DateTimeOffset.UtcNow.AddMinutes(path.EndsWith("login") ? 1 : 60).ToString("o"), channel = "probe", account = new AccountIdentity { accountId = user, username = user }, device = new AccountDevice { deviceId = "node-" + user, deviceName = "probe", role = "detector", nodeType = "visual", platform = "windows", component = "windows-inference" }, resident = new ResidentAccount { token = token + "-resident", expiresAt = DateTimeOffset.UtcNow.AddHours(1).ToString("o"), device = new AccountDevice { deviceId = "node-" + user, role = "lifecycle", nodeType = "resident", platform = "windows", component = "windows-resident" } } };
                byte[] bytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(snapshot)); request.Response.ContentType = "application/json"; request.Response.OutputStream.Write(bytes, 0, bytes.Length); request.Response.Close();
            }
        });
        AccountSession.Load(); AccountSession.Login(url, "first", "local-fixture-password", "probe"); string firstScope = AccountSession.ScopeKey;
        byte[] saved = File.ReadAllBytes(Directory.GetFiles(directory, "session-*.bin").Single()); Require(!Encoding.UTF8.GetString(saved).Contains("probe-token"), "Session credential stored in plaintext");
        var refreshing = Task.Run(() => AccountSession.EnsureFresh());
        Require(refreshEntered.WaitOne(5000), "Refresh request did not start");
        try
        {
            var read = Task.Run(() => {
                for (int index = 0; index < 100; index++)
                    Require(AccountSession.Current!.token == "probe-token-first" && AccountSession.ServiceUrl == url && AccountSession.ScopeKey == firstScope,
                        "The endpoint/identity view changed before refresh completed");
            });
            Require(read.Wait(200), "Inference account reads waited for an HTTP refresh");
            read.GetAwaiter().GetResult();
        }
        finally { refreshRelease.Set(); }
        Require(refreshing.GetAwaiter().GetResult()!.token == "rotated-probe-token", "Expiry did not rotate account/resident login");
        Require(AccountSession.ScopeKey == firstScope, "Token renewal changed account scope");
        refreshEntered.Reset(); refreshRelease.Reset();
        typeof(AccountSession).GetField("_lastValidated", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Static)!.SetValue(null, DateTime.MinValue);
        var validating = Task.Run(() => AccountSession.EnsureFresh());
        Require(refreshEntered.WaitOne(5000), "Periodic validation request did not start");
        try
        {
            var read = Task.Run(() => AccountSession.Current!.token == "rotated-probe-token" && AccountSession.ScopeKey == firstScope && AccountSession.ServiceUrl == url);
            Require(read.Wait(200) && read.Result, "Inference account reads waited for periodic HTTP validation");
        }
        finally { refreshRelease.Set(); }
        validating.GetAwaiter().GetResult();
        AccountSession.ApplyDeviceUpdate(new AccountDevice { deviceId = "node-first", deviceName = "Renamed by console" });
        AccountSession.Load(); Require(AccountSession.Current!.device.deviceName == "Renamed by console" && AccountSession.Current.resident.device.deviceName == "Renamed by console", "Server-assigned device name did not persist for both Windows components");
        using var firstAlerts = new AlertService("first-source", "first source"); bool leakedAlert = false; firstAlerts.AlertTriggered += (_, _) => leakedAlert = true;
        string pathA = Path.Combine(directory, firstScope, "outbox.json"); var outboxA = new AlertOutbox(pathA);
        outboxA.Enqueue("first-event", JsonSerializer.Serialize(new { expiresAt = DateTimeOffset.UtcNow.AddSeconds(30).ToString("o") }));
        AccountSession.Login(url, "second", "local-fixture-password", "probe"); string secondScope = AccountSession.ScopeKey;
        Require(firstScope != secondScope, "Accounts share a local scope"); var outboxB = new AlertOutbox(Path.Combine(directory, secondScope, "outbox.json")); Require(outboxB.Snapshot().Count == 0, "Old account outbox was exposed");
        using (var frame = new Bitmap(320, 180)) firstAlerts.Evaluate(new List<VisionGuard.Detector.Windows.Models.Detection> { new VisionGuard.Detector.Windows.Models.Detection { Label = "person", Confidence = .95f, BoundingBox = new RectangleF(10, 10, 30, 30) } }, new VisionGuard.Detector.Windows.Models.MonitorConfig(), new Dictionary<string, long> { ["captureMs"] = 1, ["preprocessMs"] = 1, ["inferMs"] = 1, ["parseMs"] = 1 }, frame);
        Require(!leakedAlert, "Old inference emitted an alert under the new account");
        AccountSession.Logout(); fixture.GetAwaiter().GetResult(); Require(revoked && AccountSession.Current == null && Directory.GetFiles(directory, "session-*.bin").Length == 0, "Logout failed to revoke/remove the login"); listener.Stop();
        // A unique temporary directory contains only this probe's generated files.
        Directory.Delete(directory, true);
    }
    static void IsolatedEnvironmentProbe()
    {
        string directory = Path.Combine(Path.GetTempPath(), "VisionGuard-Isolated-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(directory);
        string file = Path.Combine(directory, "environment.json");
        var config = new Dictionary<string, string> { ["serviceUrl"] = "http://127.0.0.1:3100", ["accountDir"] = Path.Combine(directory, "accounts"), ["settingsPath"] = Path.Combine(directory, "settings.ini"), ["modelsDirectory"] = Path.Combine(directory, "models"), ["logDirectory"] = Path.Combine(directory, "logs") };
        File.WriteAllText(file, JsonSerializer.Serialize(config)); AccountSession.ConfigureIsolatedEnvironment(file);
        Require(AccountSession.IsIsolated && AccountSession.Root == config["accountDir"] && AccountSession.ServiceUrl == config["serviceUrl"] && AccountSession.LogRoot == config["logDirectory"], "Explicit isolated environment did not apply");
        File.WriteAllText(config["settingsPath"], "SettingsEnvironmentProbe.Marker=isolated-read" + Environment.NewLine);
        var childStart = new ProcessStartInfo(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "AccountMedia.Probe.exe"), "--settings-environment \"" + file + "\"")
        {
            UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true
        };
        foreach (string name in new[] { "VISIONGUARD_ACCOUNT_DIR", "VISIONGUARD_SERVER_URL", "VISIONGUARD_SETTINGS_PATH", "VISIONGUARD_MODELS_DIR", "VISIONGUARD_LOG_DIR" })
            childStart.EnvironmentVariables.Remove(name);
        using (var child = Process.Start(childStart) ?? throw new Exception("Settings initialization child did not start"))
        {
            Require(child.WaitForExit(10000), "Settings initialization child timed out");
            string childError = child.StandardError.ReadToEnd();
            Require(child.ExitCode == 0, "CLI-only settings initialization failed: " + childError);
        }
        Require(File.ReadAllText(config["settingsPath"]).Contains("SettingsEnvironmentProbe.Marker=isolated-write"), "Settings save missed the explicitly selected file");
        config["password"] = "must-not-be-supported"; File.WriteAllText(file, JsonSerializer.Serialize(config)); bool rejectedSecret = false;
        try { AccountSession.ConfigureIsolatedEnvironment(file); } catch (InvalidDataException) { rejectedSecret = true; }
        Require(rejectedSecret, "Secret-bearing test environment was accepted"); config.Remove("password");
        config["accountDir"] = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VisionGuard", "accounts"); File.WriteAllText(file, JsonSerializer.Serialize(config)); bool rejectedShared = false;
        try { AccountSession.ConfigureIsolatedEnvironment(file); } catch (InvalidDataException) { rejectedShared = true; }
        Require(rejectedShared, "Explicit test environment reused the normal account directory");
        Directory.Delete(directory, true);
    }
    static void SettingsEnvironmentChild(string configurationPath)
    {
        var settings = typeof(AccountSession).Assembly.GetType("VisionGuard.Detector.Windows.Utils.SettingsStore", true)!;
        // The CLR may run beforefieldinit initializers before CLI environment parsing.
        Require((settings.Attributes & System.Reflection.TypeAttributes.BeforeFieldInit) == 0,
            "Settings storage may initialize before the isolated environment is selected");
        AccountSession.ConfigureIsolatedEnvironment(configurationPath);
        settings.GetMethod("Load")!.Invoke(null, null);
        string marker = (string)settings.GetMethod("GetString")!.Invoke(null, new object[] { "SettingsEnvironmentProbe.Marker", "missing" })!;
        Require(marker == "isolated-read", "Settings load did not read the CLI-selected file");
        settings.GetMethod("Set", new[] { typeof(string), typeof(string) })!.Invoke(null, new object[] { "SettingsEnvironmentProbe.Marker", "isolated-write" });
        settings.GetMethod("Save")!.Invoke(null, null);
    }
    static void DeviceIdentityMemoryProbe()
    {
        string directory = Path.Combine(Path.GetTempPath(), "VisionGuard-DeviceMemory-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(directory);
        Environment.SetEnvironmentVariable("VISIONGUARD_ACCOUNT_DIR", directory);
        var tcp = new TcpListener(IPAddress.Loopback, 0); tcp.Start(); int port = ((IPEndPoint)tcp.LocalEndpoint).Port; tcp.Stop();
        string url = "http://127.0.0.1:" + port; Environment.SetEnvironmentVariable("VISIONGUARD_SERVER_URL", url);
        using var listener = new HttpListener(); listener.Prefixes.Add(url + "/"); listener.Start();
        var fixture = Task.Run(() =>
        {
            for (int call = 1; call <= 9; call++)
            {
                var request = listener.GetContext(); using var reader = new StreamReader(request.Request.InputStream);
                using var body = JsonDocument.Parse(reader.ReadToEnd()); var value = body.RootElement;
                object response = new { ok = true };
                if (request.Request.Url!.AbsolutePath.EndsWith("login"))
                {
                    string user = value.GetProperty("username").GetString()!;
                    Require(user == (call == 8 ? "other" : "first"), "Username case was not normalized");
                    bool remembered = call == 3 || call == 5;
                    Require(value.TryGetProperty("deviceId", out var id) == remembered, "Registration memory crossed accounts or was lost on logout");
                    if (remembered) { Require(id.GetString() == "node-original", "Wrong assigned device ID reused"); Require(!value.TryGetProperty("deviceName", out _), "Relogin overwrote the console's device name"); }
                    if (call == 5) { request.Response.StatusCode = 403; response = new { ok = false }; }
                    else
                    {
                        string device = call == 6 ? "node-reregistered" : call == 8 ? "node-other" : "node-original";
                        string name = call == 3 ? "Console renamed" : "Local node";
                        string token = "synthetic-memory-token-" + call;
                        response = new AccountSnapshot { ok = true, token = token, expiresAt = DateTimeOffset.UtcNow.AddHours(1).ToString("o"), account = new AccountIdentity { accountId = user, username = user }, channel = "probe", device = new AccountDevice { deviceId = device, deviceName = name, component = "windows-inference" }, resident = new ResidentAccount { token = token + "-resident", expiresAt = DateTimeOffset.UtcNow.AddHours(1).ToString("o"), device = new AccountDevice { deviceId = device, deviceName = name, component = "windows-resident" } } };
                    }
                }
                byte[] bytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(response)); request.Response.ContentType = "application/json"; request.Response.OutputStream.Write(bytes, 0, bytes.Length); request.Response.Close();
            }
        });
        AccountSession.Load(); AccountSession.Login(url, " FIRST ", "synthetic-memory-password", "Initial name"); string scope = AccountSession.ScopeKey;
        AccountSession.Logout(); Require(Directory.GetFiles(directory, "session-*.bin").Length == 0 && Directory.GetFiles(directory, "devices-*.json").Length == 1, "Logout retained credentials or erased non-secret registration memory");
        var retained = AccountSession.Login(url, "FiRsT", "synthetic-memory-password", "Stale local name"); Require(retained.device.deviceName == "Console renamed" && scope == AccountSession.ScopeKey, "Relogin lost the binding/settings scope or renamed the device");
        AccountSession.Logout(); var replacement = AccountSession.Login(url, " FIRST ", "synthetic-memory-password", "Local node"); Require(replacement.device.deviceId == "node-reregistered" && scope != AccountSession.ScopeKey, "Unbound device was not reregistered once");
        AccountSession.Logout(); AccountSession.Login(url, "OTHER", "synthetic-memory-password", "Other"); AccountSession.Logout(); fixture.GetAwaiter().GetResult(); listener.Stop();
        string memory = File.ReadAllText(Directory.GetFiles(directory, "devices-*.json").Single());
        Require(memory.Contains("node-reregistered") && memory.Contains("node-other") && !memory.Contains("node-original") && !memory.Contains("token") && !memory.Contains("password"), "Registration memory retained a revoked ID or credentials");
        Directory.Delete(directory, true);
    }
    static void AlertTimingsProbe()
    {
        using var alerts = new AlertService("timings-source", "Media timings");
        var input = new Dictionary<string, long> { ["captureMs"] = 1, ["preprocessMs"] = 2, ["inferMs"] = 3, ["parseMs"] = 4, ["remoteCachedAgeMs"] = 42, ["publisherCapturedAt"] = 12345, ["relayReceivedAt"] = 23456, ["processMs"] = -1 };
        VisionGuard.Detector.Windows.Models.AlertEvent? emitted = null; alerts.AlertTriggered += (_, alert) => emitted = alert;
        using var frame = new Bitmap(320, 180);
        alerts.Evaluate(new List<VisionGuard.Detector.Windows.Models.Detection> { new VisionGuard.Detector.Windows.Models.Detection { Label = "person", Confidence = .95f, BoundingBox = new RectangleF(10, 10, 30, 30) } }, new VisionGuard.Detector.Windows.Models.MonitorConfig { SaveAlertSnapshot = false }, input, frame);
        Require(emitted != null && emitted.Timings["remoteCachedAgeMs"] == 42 && emitted.Timings["publisherCapturedAt"] == 12345 && emitted.Timings["relayReceivedAt"] == 23456, "Alert evaluation dropped media diagnostics");
        Require(emitted!.Timings["captureMs"] == 1 && emitted.Timings["preprocessMs"] == 2 && emitted.Timings["inferMs"] == 3 && emitted.Timings["parseMs"] == 4 && emitted.Timings["processMs"] >= 10 && input["processMs"] == -1, "Process timing is wrong or the caller's dictionary was mutated");
        emitted.Snapshot?.Dispose();
    }
    static void AccountThrottleProbe(bool refresh)
    {
        string directory = Path.Combine(Path.GetTempPath(), "VisionGuard-Throttle-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        Environment.SetEnvironmentVariable("VISIONGUARD_ACCOUNT_DIR", directory);
        var tcp = new TcpListener(IPAddress.Loopback, 0); tcp.Start(); int port = ((IPEndPoint)tcp.LocalEndpoint).Port; tcp.Stop();
        string url = "http://127.0.0.1:" + port; Environment.SetEnvironmentVariable("VISIONGUARD_SERVER_URL", url);
        using var listener = new HttpListener(); listener.Prefixes.Add(url + "/"); listener.Start();
        int calls = 0;
        var fixture = Task.Run(() => {
            for (int index = 0; index < 4; index++)
            {
                var request = listener.GetContext(); Interlocked.Increment(ref calls);
                if (index == 1 || index == 2) Require(request.Request.Url!.AbsolutePath.EndsWith(refresh ? "refresh" : "session"), "Wrong maintenance endpoint");
                object body;
                if (index == 1) { request.Response.StatusCode = 429; request.Response.Headers["Retry-After"] = refresh ? DateTime.UtcNow.AddMinutes(2).ToString("R") : "120"; body = new { ok = false }; }
                else if (index == 3) { request.Response.StatusCode = 401; body = new { ok = false }; }
                else body = new AccountSnapshot { ok = true, token = index == 0 ? "throttle-initial" : "throttle-rotated", expiresAt = DateTimeOffset.UtcNow.AddMinutes(index == 0 && refresh ? 1 : 60).ToString("o"),
                    account = new AccountIdentity { accountId = "throttle-owner", username = "throttle" }, device = new AccountDevice { deviceId = "throttle-node", component = "windows-inference" }, resident = new ResidentAccount { token = "throttle-resident" } };
                byte[] bytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(body)); request.Response.ContentType = "application/json";
                request.Response.OutputStream.Write(bytes, 0, bytes.Length); request.Response.Close();
            }
        });
        try
        {
            AccountSession.Load(); AccountSession.Login(url, "throttle", "synthetic-throttle-password", "Throttle probe");
            void SetField(string name, DateTime value) => typeof(AccountSession).GetField(name, System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Static)!.SetValue(null, value);
            SetField("_lastValidated", DateTime.MinValue);
            var original = AccountSession.Current!.token;
            for (int index = 0; index < 5; index++)
            {
                bool failed = false;
                try { AccountSession.EnsureFresh(); }
                catch (InvalidOperationException error) { failed = error.Message.Contains("秒后重试") && (refresh || error.Message.Contains("120")); }
                Require(failed && AccountSession.Current!.token == original, "429 revoked a valid login or lost the readable wait time");
            }
            Require(Volatile.Read(ref calls) == 2, "Maintenance ignored Retry-After and retried during the cooldown");
            var retryAt = (DateTime)typeof(AccountSession).GetField("_nextMaintenanceAttempt", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Static)!.GetValue(null)!;
            Require(retryAt > DateTime.UtcNow.AddSeconds(110), "Server retry delay was not retained");
            SetField("_nextMaintenanceAttempt", DateTime.MinValue);
            Require(AccountSession.EnsureFresh() != null && Volatile.Read(ref calls) == 3, "Maintenance did not recover after the retry deadline");
            SetField("_lastValidated", DateTime.MinValue);
            Require(AccountSession.EnsureFresh() == null, "Actual session revocation must still sign out");
            Require(fixture.Wait(5000), "Throttle fixture did not finish"); fixture.GetAwaiter().GetResult();
            Console.WriteLine("PASS account " + (refresh ? "refresh" : "validation") + " 429 backoff, recovery and revocation");
        }
        finally { listener.Stop(); Directory.Delete(directory, true); }
    }

    static JsonDocument ReadClientMessage(NetworkStream stream)
    {
        int first = stream.ReadByte(), second = stream.ReadByte();
        Require(first >= 0 && second >= 0 && (first & 15) == 1, "Expected a text frame");
        int length = second & 127;
        if (length == 126) length = (stream.ReadByte() << 8) | stream.ReadByte();
        Require(length < 65536 && (second & 128) != 0, "Unexpected client frame size or mask");
        byte[] Read(int count) { byte[] bytes = new byte[count]; int read = 0; while (read < count) { int got = stream.Read(bytes, read, count - read); if (got == 0) throw new EndOfStreamException(); read += got; } return bytes; }
        var mask = Read(4); var body = Read(length);
        for (int index = 0; index < body.Length; index++) body[index] ^= mask[index % 4];
        return JsonDocument.Parse(body);
    }

    static void AlertRegistrationProbe()
    {
        string directory = Path.Combine(Path.GetTempPath(), "VisionGuard-AlertRegistration-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(directory);
        string? previousLogRoot = Environment.GetEnvironmentVariable("VISIONGUARD_LOG_DIR");
        Environment.SetEnvironmentVariable("VISIONGUARD_LOG_DIR", Path.Combine(directory, "logs"));
        Environment.SetEnvironmentVariable("VISIONGUARD_ACCOUNT_DIR", directory);
        var tcp = new TcpListener(IPAddress.Loopback, 0); tcp.Start(); int loginPort = ((IPEndPoint)tcp.LocalEndpoint).Port; tcp.Stop();
        string loginUrl = "http://127.0.0.1:" + loginPort; Environment.SetEnvironmentVariable("VISIONGUARD_SERVER_URL", loginUrl);
        using var loginServer = new HttpListener(); loginServer.Prefixes.Add(loginUrl + "/"); loginServer.Start();
        var login = Task.Run(() => {
            var request = loginServer.GetContext(); var snapshot = new AccountSnapshot { ok = true, token = "registration-fixture-token", expiresAt = DateTimeOffset.UtcNow.AddHours(1).ToString("o"),
                account = new AccountIdentity { accountId = "registration-owner", username = "registration" }, device = new AccountDevice { deviceId = "registration-node", component = "windows-inference" }, resident = new ResidentAccount { token = "registration-resident" } };
            byte[] bytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(snapshot)); request.Response.OutputStream.Write(bytes, 0, bytes.Length); request.Response.Close();
        });
        AccountSession.Load(); var account = AccountSession.Login(loginUrl, "registration", "synthetic-registration-password", "Registration probe"); login.GetAwaiter().GetResult(); loginServer.Stop();
        string scopeDirectory = Path.GetDirectoryName(AlertService.GetSnapshotPath("unused"))!;
        scopeDirectory = Path.GetDirectoryName(scopeDirectory)!;
        Require(!Directory.Exists(scopeDirectory), "Registration probe must use a fresh isolated account scope");
        string outboxPath = Path.Combine(scopeDirectory, "alert-outbox.json"), alertId = Guid.NewGuid().ToString();
        new AlertOutbox(outboxPath).Enqueue(alertId, JsonSerializer.Serialize(new { type = "alert", alertId, sourceId = "probe-source", expiresAt = NtpSync.UtcNow.AddSeconds(30) }));
        var control = new TcpListener(IPAddress.Loopback, 0); control.Start(); int port = ((IPEndPoint)control.LocalEndpoint).Port;
        var fixture = Task.Run(() => {
            using var peer = control.AcceptTcpClient(); using var stream = peer.GetStream(); stream.ReadTimeout = 10000;
            Handshake(stream);
            using (var auth = ReadClientMessage(stream)) Require(auth.RootElement.GetProperty("type").GetString() == "auth", "Missing authentication");
            void Send(object value) => SendFrame(stream, 1, Encoding.UTF8.GetBytes(JsonSerializer.Serialize(value)));
            Send(new { type = "auth-result", success = true, maxSources = 16 });
            using (var heartbeat = ReadClientMessage(stream)) {
                Require(heartbeat.RootElement.GetProperty("type").GetString() == "heartbeat", "Queued alert was sent before source registration");
                Require(heartbeat.RootElement.GetProperty("sources")[0].GetProperty("sourceId").GetString() == "probe-source", "Source missing from initial registration");
            }
            Thread.Sleep(100); Require(!stream.DataAvailable, "Alert was sent before the first heartbeat acknowledgement");
            Send(new { type = "heartbeat-ack", maxSources = 16 });
            for (int attempt = 0; attempt < 3; attempt++)
            {
                using (var alert = ReadClientMessage(stream)) {
                    Require(alert.RootElement.GetProperty("type").GetString() == "alert" && alert.RootElement.GetProperty("alertId").GetString() == alertId, "Retry lost the stable alert identity");
                }
                Send(new { type = "alert-ack", alertId, accepted = attempt == 2, reason = attempt == 0 ? "unknown-source" : attempt == 1 ? "storage-failed" : "stored" });
                if (attempt < 2) {
                    using var heartbeat = ReadClientMessage(stream); Require(heartbeat.RootElement.GetProperty("type").GetString() == "heartbeat", "Retry was not paced by the heartbeat");
                    Send(new { type = "heartbeat-ack", maxSources = 16 });
                }
            }
            Thread.Sleep(200);
        });
        try
        {
            using var service = new ServerPushService();
            service.UpdateHeartbeatParams(true, true, 5, .5f, "person", sources: new object[] { new { sourceId = "probe-source", sourceName = "probe" } });
            service.Configure("http://127.0.0.1:" + port, account.token, account.device.deviceId, "Registration probe");
            Require(fixture.Wait(15000), "Source registration contract timed out"); fixture.GetAwaiter().GetResult();
            Require(new AlertOutbox(outboxPath).Snapshot().Count == 0, "Accepted alert remained in the outbox");
            string deliveryLog = File.ReadAllText(Path.Combine(directory, "logs", "alert-delivery.log"));
            Require(deliveryLog.Contains("reason=unknown-source") && deliveryLog.Contains("reason=storage-failed") && !deliveryLog.Contains(account.token), "Release rejection evidence missing or exposed credentials");
            Console.WriteLine("PASS initial source registration before queued alert, unknown-source/storage retry, stable ID and persistence ack");
        }
        finally { control.Stop(); Environment.SetEnvironmentVariable("VISIONGUARD_LOG_DIR", previousLogRoot); Directory.Delete(directory, true); Directory.Delete(scopeDirectory, true); }
    }

    static void AlertClockProbe()
    {
        string directory = Path.Combine(Path.GetTempPath(), "VisionGuard-AlertClock-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(directory);
        var offset = typeof(NtpSync).GetField("_offsetMs", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Static)!;
        long original = (long)offset.GetValue(null)!;
        try
        {
            // The PC is one minute ahead; NTP corrects event time without altering the system clock.
            offset.SetValue(null, -60_000L);
            var queue = new AlertOutbox(Path.Combine(directory, "outbox.json"));
            queue.Enqueue("live", JsonSerializer.Serialize(new { expiresAt = NtpSync.UtcNow.AddSeconds(30) }));
            queue.Enqueue("expired", JsonSerializer.Serialize(new { expiresAt = NtpSync.UtcNow.AddSeconds(-1) }));
            Require(queue.Snapshot().Count == 1 && queue.Snapshot()[0].AlertId == "live", "Clock correction discarded a newly created live alert");
            offset.SetValue(null, 60_000L);
            Require(queue.Snapshot().Count == 0, "Corrected expiry must prune events when the PC is behind");
            Console.WriteLine("PASS corrected alert clock with PC ahead/behind by one minute");
        }
        finally { offset.SetValue(null, original); Directory.Delete(directory, true); }
    }

    static void CrossProcessSessionProbe()
    {
        string directory = Path.Combine(Path.GetTempPath(), "VisionGuard-SessionRace-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(directory);
        Environment.SetEnvironmentVariable("VISIONGUARD_ACCOUNT_DIR", directory);
        var tcp = new TcpListener(IPAddress.Loopback, 0); tcp.Start(); int port = ((IPEndPoint)tcp.LocalEndpoint).Port; tcp.Stop();
        string url = "http://127.0.0.1:" + port; Environment.SetEnvironmentVariable("VISIONGUARD_SERVER_URL", url);
        using var listener = new HttpListener(); listener.Prefixes.Add(url + "/"); listener.Start();
        using var refreshEntered = new ManualResetEventSlim(false); using var releaseRefresh = new ManualResetEventSlim(false);
        string logoutAuthorization = ""; var responses = new List<Task>(); int loginCount = 0;
        AccountSnapshot Snapshot(string token, int minutes) => new AccountSnapshot { ok = true, token = token, expiresAt = DateTimeOffset.UtcNow.AddMinutes(minutes).ToString("o"), channel = "probe", account = new AccountIdentity { accountId = "race-account", username = "race" }, device = new AccountDevice { deviceId = "race-node", deviceName = "Race node", component = "windows-inference" }, resident = new ResidentAccount { token = token + "-resident", expiresAt = DateTimeOffset.UtcNow.AddHours(1).ToString("o"), device = new AccountDevice { deviceId = "race-node", deviceName = "Race node", component = "windows-resident" } } };
        void Reply(HttpListenerContext request, object value) { byte[] bytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(value)); request.Response.ContentType = "application/json"; request.Response.OutputStream.Write(bytes, 0, bytes.Length); request.Response.Close(); }
        var server = Task.Run(() =>
        {
            for (int i = 0; i < 4; i++)
            {
                var request = listener.GetContext(); string path = request.Request.Url!.AbsolutePath;
                if (path.EndsWith("refresh"))
                {
                    responses.Add(Task.Run(() => { refreshEntered.Set(); Require(releaseRefresh.Wait(10000), "Refresh fixture gate timed out"); Reply(request, Snapshot("race-rotated-token", 60)); }));
                }
                else if (path.EndsWith("logout")) { logoutAuthorization = request.Request.Headers["Authorization"] ?? ""; Reply(request, new { ok = true }); }
                else { loginCount++; Reply(request, Snapshot(loginCount == 1 ? "race-initial-token" : "race-parent-new-token", loginCount == 1 ? 1 : 60)); }
            }
        });
        Process? child = null;
        try
        {
            AccountSession.Load(); AccountSession.Login(url, "race", "synthetic-race-password", "Race node");
            var start = new ProcessStartInfo(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "AccountMedia.Probe.exe"), "--delayed-refresh") { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
            start.EnvironmentVariables["VISIONGUARD_ACCOUNT_DIR"] = directory; start.EnvironmentVariables["VISIONGUARD_SERVER_URL"] = url;
            child = Process.Start(start) ?? throw new Exception("Resident-like probe child did not start");
            Require(refreshEntered.Wait(10000), "Child never reached delayed HTTP refresh");
            var parent = Task.Run(() => { AccountSession.Logout(); AccountSession.Login(url, "race", "synthetic-race-password", "Race node"); });
            Require(!parent.Wait(200), "Parent bypassed the cross-process writer lock during delayed resident refresh");
            releaseRefresh.Set(); Require(parent.Wait(15000), "Parent logout/relogin did not finish after refresh"); parent.GetAwaiter().GetResult();
            Require(child.WaitForExit(10000) && child.ExitCode == 0, "Resident-like child refresh failed");
            server.GetAwaiter().GetResult(); Task.WhenAll(responses).GetAwaiter().GetResult();
            AccountSession.Load(); Require(AccountSession.Current!.token == "race-parent-new-token", "Late child refresh overwrote the parent's new login");
            Require(logoutAuthorization == "Bearer race-rotated-token", "Logout used a stale token instead of the locked disk session");
        }
        finally { releaseRefresh.Set(); if (child != null) { if (!child.HasExited) child.Kill(); child.Dispose(); } listener.Stop(); }
        Directory.Delete(directory, true);
    }
}
