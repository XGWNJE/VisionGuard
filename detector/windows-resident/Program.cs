using System.Diagnostics;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using Microsoft.Win32;

namespace VisionGuard.Resident;

internal sealed record ResidentConfig(string ServerUrl, string? ApiKey, string DeviceId, string DeviceName, string WpfPath, string WinFormsPath);

internal static class Program
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    public static async Task<int> Main(string[] args)
    {
        if (args.Length == 2 && args[0] == "--enable-startup") return SetStartup(args[1], true);
        if (args.Length == 1 && args[0] == "--disable-startup") return SetStartup("", false);
        if (args.Length == 2 && args[0] == "--status") return IsRunning(args[1]) ? 0 : 3;
        if (args.Length == 2 && args[0] == "--shutdown") return SignalShutdown(args[1]) ? 0 : 3;
        if (args.Length != 2 || args[0] != "--config")
        {
            Console.Error.WriteLine("Usage: VisionGuard.Resident --config <resident.json> | --enable-startup <resident.json> | --disable-startup");
            return 2;
        }

        var config = JsonSerializer.Deserialize<ResidentConfig>(await File.ReadAllTextAsync(args[1]), Json)
            ?? throw new InvalidDataException("Invalid resident config.");
        Validate(config);
        var apiKey = Environment.GetEnvironmentVariable("VISIONGUARD_API_KEY") ?? config.ApiKey!;
        await RunAsync(config, apiKey, CancellationToken.None);
        return 0;
    }

    private static int SetStartup(string configPath, bool enabled)
    {
        const string keyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
        using var key = Registry.CurrentUser.OpenSubKey(keyPath, writable: true) ?? Registry.CurrentUser.CreateSubKey(keyPath);
        if (!enabled) key.DeleteValue("VisionGuardResident", throwOnMissingValue: false);
        else
        {
            var exe = Environment.ProcessPath ?? throw new InvalidOperationException("Executable path unavailable.");
            var fullConfig = Path.GetFullPath(configPath);
            if (!File.Exists(fullConfig)) throw new FileNotFoundException("Resident config not found.", fullConfig);
            key.SetValue("VisionGuardResident", $"\"{exe}\" --config \"{fullConfig}\"");
        }
        return 0;
    }

    private static async Task RunAsync(ResidentConfig config, string apiKey, CancellationToken token)
    {
        var delay = TimeSpan.FromSeconds(1);
        while (!token.IsCancellationRequested)
        {
            try
            {
                using var ws = new ClientWebSocket();
                var endpoint = config.ServerUrl.TrimEnd('/') + "/ws";
                endpoint = endpoint.StartsWith("https://", StringComparison.OrdinalIgnoreCase)
                    ? "wss://" + endpoint[8..]
                    : "ws://" + endpoint[7..];
                await ws.ConnectAsync(new Uri(endpoint), token);
                var channel = Environment.GetEnvironmentVariable("VISIONGUARD_CHANNEL") ?? "vnext";
                await SendAsync(ws, new { type = "auth", channel, role = "windows-resident", apiKey, config.DeviceId, config.DeviceName }, token);
                await SessionAsync(ws, config, token);
                delay = TimeSpan.FromSeconds(1);
            }
            catch (Exception ex) when (!token.IsCancellationRequested)
            {
                Console.Error.WriteLine($"[{DateTimeOffset.Now:O}] resident connection failed: {ex.Message}");
                await Task.Delay(delay, token);
                delay = TimeSpan.FromSeconds(Math.Min(30, delay.TotalSeconds * 2));
            }
        }
    }

    private static async Task SessionAsync(ClientWebSocket ws, ResidentConfig config, CancellationToken token)
    {
        using var heartbeatCts = CancellationTokenSource.CreateLinkedTokenSource(token);
        using var sendLock = new SemaphoreSlim(1, 1);
        async Task SendLockedAsync(object value, CancellationToken cancellationToken)
        {
            await sendLock.WaitAsync(cancellationToken);
            try { await SendAsync(ws, value, cancellationToken); }
            finally { sendLock.Release(); }
        }
        var heartbeat = Task.Run(async () =>
        {
            while (!heartbeatCts.IsCancellationRequested)
            {
                await SendLockedAsync(new { type = "resident-heartbeat", components = Components() }, heartbeatCts.Token);
                await Task.Delay(TimeSpan.FromSeconds(3), heartbeatCts.Token);
            }
        }, heartbeatCts.Token);

        var buffer = new byte[16 * 1024];
        try
        {
            while (ws.State == WebSocketState.Open && !token.IsCancellationRequested)
            {
                var result = await ws.ReceiveAsync(buffer, token);
                if (result.MessageType == WebSocketMessageType.Close) break;
                using var doc = JsonDocument.Parse(buffer.AsMemory(0, result.Count));
                var root = doc.RootElement;
                if (root.GetProperty("type").GetString() != "command") continue;
                var command = root.GetProperty("command").GetString() ?? "";
                var requestId = root.TryGetProperty("requestId", out var id) ? id.GetString() : null;
                var (success, reason) = await ExecuteAsync(command, config);
                await SendLockedAsync(new { type = "command-ack", requestId, command, success, reason }, token);
            }
        }
        finally
        {
            heartbeatCts.Cancel();
            try { await heartbeat; } catch (OperationCanceledException) { }
        }
    }

    private static async Task<(bool, string)> ExecuteAsync(string command, ResidentConfig config)
    {
        return command switch
        {
            "open-wpf" => await OpenAsync("Wpf", config.WpfPath),
            "open-winforms" => await OpenAsync("WinForms", config.WinFormsPath),
            "close-wpf" => await CloseAsync("Wpf"),
            "close-winforms" => await CloseAsync("WinForms"),
            _ => (false, "unsupported resident command"),
        };
    }

    private static async Task<(bool, string)> OpenAsync(string appId, string path)
    {
        if (IsRunning(appId)) return (true, "already running");
        if (!File.Exists(path)) return (false, "configured executable not found");
        Process.Start(new ProcessStartInfo(path) { UseShellExecute = true, WorkingDirectory = Path.GetDirectoryName(path)! });
        var deadline = DateTime.UtcNow.AddSeconds(10);
        while (DateTime.UtcNow < deadline)
        {
            if (IsRunning(appId)) return (true, "application handshake completed; monitoring remains stopped");
            await Task.Delay(200);
        }
        return (false, "application handshake timeout");
    }

    private static async Task<(bool, string)> CloseAsync(string appId)
    {
        if (!SignalShutdown(appId)) return (false, "application is not running");
        var deadline = DateTime.UtcNow.AddSeconds(10);
        while (DateTime.UtcNow < deadline)
        {
            if (!IsRunning(appId)) return (true, "application shutdown completed");
            await Task.Delay(200);
        }
        return (false, "application shutdown timeout");
    }

    private static Dictionary<string, string> Components() => new()
    {
        ["resident"] = "running",
        ["wpfApp"] = IsRunning("Wpf") ? "running" : "stopped",
        ["winFormsApp"] = IsRunning("WinForms") ? "running" : "stopped",
    };

    private static bool IsRunning(string appId)
    {
        try { return EventWaitHandle.OpenExisting($"Local\\VisionGuard.{appId}.Running").WaitOne(0); }
        catch (WaitHandleCannotBeOpenedException) { return false; }
    }

    private static bool SignalShutdown(string appId)
    {
        if (!IsRunning(appId)) return false;
        try { EventWaitHandle.OpenExisting($"Local\\VisionGuard.{appId}.Shutdown").Set(); return true; }
        catch (WaitHandleCannotBeOpenedException) { return false; }
    }

    private static void Validate(ResidentConfig c)
    {
        if (!Uri.TryCreate(c.ServerUrl, UriKind.Absolute, out var uri) || uri.Scheme is not ("https" or "http")) throw new InvalidDataException("ServerUrl must be HTTP(S).");
        if (string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("VISIONGUARD_API_KEY")) && string.IsNullOrWhiteSpace(c.ApiKey))
            throw new InvalidDataException("VISIONGUARD_API_KEY or config ApiKey is required.");
        if (string.IsNullOrWhiteSpace(c.DeviceId)) throw new InvalidDataException("DeviceId is required.");
    }

    private static Task SendAsync(ClientWebSocket ws, object value, CancellationToken token)
    {
        var bytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(value, Json));
        return ws.SendAsync(bytes, WebSocketMessageType.Text, true, token);
    }
}
