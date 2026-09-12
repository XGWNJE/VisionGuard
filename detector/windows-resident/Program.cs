using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Reflection;
using System.Threading;
using System.Web.Script.Serialization;
using Microsoft.Win32;
using WebSocketSharp;

namespace VisionGuard.Resident
{
    internal sealed class ResidentConfig
    {
        public string ServerUrl { get; set; }
        public string ApiKey { get; set; }
        public string DeviceId { get; set; }
        public string DeviceName { get; set; }
        public string WpfPath { get; set; }
        public string WinFormsPath { get; set; }
    }

    internal static class Program
    {
        private const int HeartbeatIntervalMs = 3000;
        private const int AuthTimeoutMs = 12000;
        private const int CommandTimeoutMs = 10000;
        private static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = 1024 * 1024 };
        private static readonly object LogLock = new object();

        [STAThread]
        private static int Main(string[] args)
        {
            try
            {
                ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
                if (args.Length == 2 && args[0] == "--enable-startup") return SetStartup(args[1], true);
                if (args.Length == 1 && args[0] == "--disable-startup") return SetStartup(string.Empty, false);
                if (args.Length == 2 && args[0] == "--status") return IsRunning(args[1]) ? 0 : 3;
                if (args.Length == 2 && args[0] == "--shutdown") return SignalShutdown(args[1]) ? 0 : 3;
                if (args.Length != 2 || args[0] != "--config") return 2;

                ResidentConfig config = Json.Deserialize<ResidentConfig>(File.ReadAllText(args[1]));
                Validate(config);
                string apiKey = Environment.GetEnvironmentVariable("VISIONGUARD_API_KEY") ?? config.ApiKey;
                bool createdNew;
                using (var mutex = new Mutex(true, @"Local\VisionGuard.Resident.SingleInstance", out createdNew))
                {
                    if (!createdNew) return 0;
                    Run(config, apiKey);
                }
                return 0;
            }
            catch (Exception ex) { Log("fatal: " + ex); return 1; }
        }

        private static int SetStartup(string configPath, bool enabled)
        {
            const string keyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
            using (RegistryKey key = Registry.CurrentUser.OpenSubKey(keyPath, true) ?? Registry.CurrentUser.CreateSubKey(keyPath))
            {
                if (!enabled) key.DeleteValue("VisionGuardResident", false);
                else
                {
                    string exe = Assembly.GetExecutingAssembly().Location;
                    string fullConfig = Path.GetFullPath(configPath);
                    if (!File.Exists(fullConfig)) throw new FileNotFoundException("Resident config not found.", fullConfig);
                    key.SetValue("VisionGuardResident", string.Format("\"{0}\" --config \"{1}\"", exe, fullConfig));
                }
            }
            return 0;
        }

        private static void Run(ResidentConfig config, string apiKey)
        {
            int delaySeconds = 1;
            while (true)
            {
                try { RunSession(config, apiKey); delaySeconds = 1; }
                catch (Exception ex)
                {
                    Log("connection failed: " + ex.Message);
                    Thread.Sleep(TimeSpan.FromSeconds(delaySeconds));
                    delaySeconds = Math.Min(30, delaySeconds * 2);
                }
            }
        }

        private static void RunSession(ResidentConfig config, string apiKey)
        {
            string endpoint = config.ServerUrl.TrimEnd('/') + "/ws";
            endpoint = endpoint.StartsWith("https://", StringComparison.OrdinalIgnoreCase)
                ? "wss://" + endpoint.Substring(8) : "ws://" + endpoint.Substring(7);

            using (var closed = new ManualResetEvent(false))
            using (var authenticated = new ManualResetEvent(false))
            using (var heartbeatStop = new ManualResetEvent(false))
            using (var ws = new WebSocket(endpoint))
            {
                object sendLock = new object();
                bool authSucceeded = false;
                string failure = "connection closed";
                Thread heartbeat = null;
                Action<Dictionary<string, object>> send = message =>
                {
                    string payload = Json.Serialize(message);
                    lock (sendLock)
                    {
                        if (!ws.IsAlive) throw new IOException("WebSocket is not connected.");
                        ws.Send(payload);
                    }
                };

                ws.OnOpen += delegate
                {
                    send(new Dictionary<string, object>
                    {
                        ["type"] = "auth", ["channel"] = Environment.GetEnvironmentVariable("VISIONGUARD_CHANNEL") ?? "vnext",
                        ["role"] = "windows-resident", ["apiKey"] = apiKey,
                        ["deviceId"] = config.DeviceId, ["deviceName"] = config.DeviceName
                    });
                };
                ws.OnMessage += delegate(object sender, MessageEventArgs e)
                {
                    try
                    {
                        Dictionary<string, object> message = Json.Deserialize<Dictionary<string, object>>(e.Data);
                        string type = GetString(message, "type");
                        if (type == "auth-result")
                        {
                            authSucceeded = GetBool(message, "success");
                            failure = GetString(message, "reason", "authentication failed");
                            SafeSet(authenticated);
                        }
                        else if (type == "command") ThreadPool.QueueUserWorkItem(delegate { HandleCommand(message, config, send); });
                        else if (type == "kicked") { failure = "kicked: " + GetString(message, "reason", "duplicate"); SafeSet(closed); }
                    }
                    catch (Exception ex) { Log("message failed: " + ex.Message); }
                };
                ws.OnClose += delegate(object sender, CloseEventArgs e) { failure = "closed: " + e.Reason; SafeSet(closed); };
                ws.OnError += delegate(object sender, WebSocketSharp.ErrorEventArgs e) { failure = "error: " + e.Message; SafeSet(closed); };

                ws.Connect();
                if (!authenticated.WaitOne(AuthTimeoutMs)) throw new TimeoutException("Resident authentication timed out.");
                if (!authSucceeded) throw new InvalidOperationException(failure);
                heartbeat = new Thread(new ThreadStart(delegate
                {
                    while (!heartbeatStop.WaitOne(HeartbeatIntervalMs))
                    {
                        try { SendHeartbeat(send); }
                        catch (Exception ex) { failure = "heartbeat failed: " + ex.Message; SafeSet(closed); return; }
                    }
                })) { IsBackground = true, Name = "VG_ResidentHeartbeat" };
                heartbeat.Start();
                SendHeartbeat(send);
                closed.WaitOne();
                heartbeatStop.Set();
                heartbeat.Join(2000);
                throw new IOException(failure);
            }
        }

        private static void SendHeartbeat(Action<Dictionary<string, object>> send)
        {
            send(new Dictionary<string, object> { ["type"] = "resident-heartbeat", ["components"] = Components() });
        }

        private static void SafeSet(EventWaitHandle handle)
        {
            try { handle.Set(); } catch (ObjectDisposedException) { }
        }

        private static void HandleCommand(Dictionary<string, object> message, ResidentConfig config, Action<Dictionary<string, object>> send)
        {
            string command = GetString(message, "command");
            string requestId = GetString(message, "requestId");
            string targetDeviceId = GetString(message, "targetDeviceId", config.DeviceId);
            string targetSourceId = GetString(message, "targetSourceId");
            CommandResult result = Execute(command, config);
            var ack = new Dictionary<string, object>
            {
                ["type"] = "command-ack", ["requestId"] = requestId, ["phase"] = "completed",
                ["targetDeviceId"] = targetDeviceId, ["command"] = command,
                ["success"] = result.Success, ["reason"] = result.Reason
            };
            if (!string.IsNullOrEmpty(targetSourceId)) ack["targetSourceId"] = targetSourceId;
            try { send(ack); } catch (Exception ex) { Log("command ack failed: " + ex.Message); }
        }

        private static CommandResult Execute(string command, ResidentConfig config)
        {
            switch (command)
            {
                case "open-wpf": return Open("Wpf", config.WpfPath);
                case "open-winforms": return Open("WinForms", config.WinFormsPath);
                case "close-wpf": return Close("Wpf");
                case "close-winforms": return Close("WinForms");
                default: return new CommandResult(false, "unsupported resident command");
            }
        }

        private static CommandResult Open(string appId, string path)
        {
            if (IsRunning(appId)) return new CommandResult(true, "already running");
            if (string.IsNullOrWhiteSpace(path) || !File.Exists(path)) return new CommandResult(false, "configured executable not found");
            string fullPath = Path.GetFullPath(path);
            Process.Start(new ProcessStartInfo { FileName = fullPath, UseShellExecute = true, WorkingDirectory = Path.GetDirectoryName(fullPath) });
            DateTime deadline = DateTime.UtcNow.AddMilliseconds(CommandTimeoutMs);
            while (DateTime.UtcNow < deadline) { if (IsRunning(appId)) return new CommandResult(true, "application handshake completed; monitoring remains stopped"); Thread.Sleep(200); }
            return new CommandResult(false, "application handshake timeout");
        }

        private static CommandResult Close(string appId)
        {
            if (!SignalShutdown(appId)) return new CommandResult(false, "application is not running");
            DateTime deadline = DateTime.UtcNow.AddMilliseconds(CommandTimeoutMs);
            while (DateTime.UtcNow < deadline) { if (!IsRunning(appId)) return new CommandResult(true, "application shutdown completed"); Thread.Sleep(200); }
            return new CommandResult(false, "application shutdown timeout");
        }

        private static Dictionary<string, string> Components()
        {
            return new Dictionary<string, string>
            {
                ["resident"] = "running", ["wpfApp"] = IsRunning("Wpf") ? "running" : "stopped",
                ["winFormsApp"] = IsRunning("WinForms") ? "running" : "stopped"
            };
        }

        private static bool IsRunning(string appId)
        {
            try { using (EventWaitHandle handle = EventWaitHandle.OpenExisting(@"Local\VisionGuard." + appId + ".Running")) return handle.WaitOne(0); }
            catch (WaitHandleCannotBeOpenedException) { return false; }
        }

        private static bool SignalShutdown(string appId)
        {
            if (!IsRunning(appId)) return false;
            try { using (EventWaitHandle handle = EventWaitHandle.OpenExisting(@"Local\VisionGuard." + appId + ".Shutdown")) { handle.Set(); return true; } }
            catch (WaitHandleCannotBeOpenedException) { return false; }
        }

        private static void Validate(ResidentConfig config)
        {
            Uri uri;
            if (config == null) throw new InvalidDataException("Invalid resident config.");
            if (!Uri.TryCreate(config.ServerUrl, UriKind.Absolute, out uri) || (uri.Scheme != "https" && uri.Scheme != "http")) throw new InvalidDataException("ServerUrl must be HTTP(S).");
            if (string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("VISIONGUARD_API_KEY")) && string.IsNullOrWhiteSpace(config.ApiKey)) throw new InvalidDataException("VISIONGUARD_API_KEY or config ApiKey is required.");
            if (string.IsNullOrWhiteSpace(config.DeviceId)) throw new InvalidDataException("DeviceId is required.");
            if (string.IsNullOrWhiteSpace(config.DeviceName)) config.DeviceName = Environment.MachineName;
        }

        private static string GetString(Dictionary<string, object> message, string key, string fallback = "")
        {
            object value;
            return message != null && message.TryGetValue(key, out value) && value != null ? value.ToString() : fallback;
        }

        private static bool GetBool(Dictionary<string, object> message, string key)
        {
            object value;
            return message != null && message.TryGetValue(key, out value) && value is bool && (bool)value;
        }

        private static void Log(string message)
        {
            try
            {
                string directory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VisionGuard");
                Directory.CreateDirectory(directory);
                lock (LogLock) File.AppendAllText(Path.Combine(directory, "resident.log"), DateTimeOffset.Now.ToString("O") + " " + message + Environment.NewLine);
            }
            catch { }
        }

        private sealed class CommandResult
        {
            public CommandResult(bool success, string reason) { Success = success; Reason = reason; }
            public bool Success { get; private set; }
            public string Reason { get; private set; }
        }
    }
}
