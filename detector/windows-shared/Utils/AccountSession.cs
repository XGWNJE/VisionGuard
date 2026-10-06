using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;

namespace VisionGuard.Detector.Windows.Utils
{
    public sealed class AccountDevice
    {
        public string deviceId { get; set; }
        public string deviceName { get; set; }
        public string role { get; set; }
        public string nodeType { get; set; }
        public string platform { get; set; }
        public string component { get; set; }
    }
    public sealed class AccountIdentity { public string accountId { get; set; } public string username { get; set; } }
    public sealed class ResidentAccount { public string token { get; set; } public string expiresAt { get; set; } public AccountDevice device { get; set; } }
    public sealed class AccountSnapshot
    {
        public bool ok { get; set; }
        public string token { get; set; }
        public string expiresAt { get; set; }
        public string channel { get; set; }
        public AccountIdentity account { get; set; }
        public AccountDevice device { get; set; }
        public ResidentAccount resident { get; set; }
        public string serviceUrl { get; set; }
    }

    // The current Windows user owns this encrypted login; passwords are never saved.
    // Resident and launcher read the same store, so neither asks for another credential.
    public static class AccountSession
    {
        private const string ProductionUrl = "https://visionguard.xgwnje.cn";
        private static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = 1024 * 1024 };
        private static readonly object Sync = new object();
        private static AccountSnapshot _current;
        private static string _serviceUrl;
        // Readers must not wait for the writer's HTTP request. Publish one coherent endpoint/identity view.
        private sealed class PublishedSession
        {
            public readonly AccountSnapshot Current;
            public readonly string ServiceUrl, ScopeKey;
            public PublishedSession(AccountSnapshot current, string serviceUrl)
            {
                Current = current; ServiceUrl = serviceUrl;
                ScopeKey = Hash(serviceUrl + "|" + (current == null ? "signed-out" : current.account.accountId + "|" + current.device.deviceId));
            }
        }
        private static PublishedSession _published;
        private static PublishedSession Published
        {
            get { var value = Volatile.Read(ref _published); if (value == null) { Load(); value = Volatile.Read(ref _published); } return value; }
        }
        private static void Publish() { Volatile.Write(ref _published, new PublishedSession(_current, _serviceUrl)); }
        private static DateTime _lastValidated = DateTime.MinValue;
        public static event EventHandler Changed;
        public static AccountSnapshot Current { get { return Published.Current; } }
        public static string ServiceUrl { get { return Published.ServiceUrl; } }
        public static string Root
        {
            get { return Environment.GetEnvironmentVariable("VISIONGUARD_ACCOUNT_DIR") ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VisionGuard", "accounts"); }
        }
        public static bool IsIsolated
        {
            get { return !string.Equals(Path.GetFullPath(Root).TrimEnd(Path.DirectorySeparatorChar), Path.GetFullPath(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VisionGuard", "accounts")).TrimEnd(Path.DirectorySeparatorChar), StringComparison.OrdinalIgnoreCase); }
        }
        public static bool AllowsTestEndpoint
        {
            get
            {
#if DEBUG
                return true;
#else
                return IsIsolated;
#endif
            }
        }
        public static string InstanceSuffix { get { return IsIsolated ? "." + Hash(Path.GetFullPath(Root)).Substring(0, 16) : ""; } }
        public static string ApplicationId { get { return "Detector" + InstanceSuffix; } }
        public static string ResidentMutexName { get { return @"Local\VisionGuard.Resident.SingleInstance" + InstanceSuffix; } }
        public static string ResidentShutdownName { get { return @"Local\VisionGuard.Resident.Shutdown" + InstanceSuffix; } }
        public static string LogRoot { get { return Environment.GetEnvironmentVariable("VISIONGUARD_LOG_DIR") ?? (IsIsolated ? Path.Combine(Root, "logs") : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VisionGuard")); } }
        public static string ScopeKey
        {
            get { return Published.ScopeKey; }
        }
        public static string Hash(string value)
        {
            using (var sha = SHA256.Create()) return BitConverter.ToString(sha.ComputeHash(Encoding.UTF8.GetBytes(value))).Replace("-", "").ToLowerInvariant();
        }
        private static string StorePath { get { return Path.Combine(Root, "session-" + Hash(_serviceUrl) + ".bin"); } }
        private static string DevicesPath { get { return Path.Combine(Root, "devices-" + Hash(_serviceUrl) + ".json"); } }
        private sealed class SessionWriteLock : IDisposable
        {
            private readonly Mutex _mutex;
            public SessionWriteLock()
            {
                if (Volatile.Read(ref _published) == null) Load();
                _mutex = new Mutex(false, @"Local\VisionGuard.AccountSession." + Hash(_serviceUrl));
                bool owned;
                try { owned = _mutex.WaitOne(TimeSpan.FromSeconds(20)); } catch (AbandonedMutexException) { owned = true; }
                if (!owned) { _mutex.Dispose(); throw new TimeoutException("等待登录会话更新超时。"); }
            }
            public void Dispose() { _mutex.ReleaseMutex(); _mutex.Dispose(); }
        }
        private sealed class DeviceRegistrationRejectedException : UnauthorizedAccessException
        {
            public DeviceRegistrationRejectedException() : base("设备登记已失效。") { }
        }
        private static Dictionary<string, string> RememberedDevices()
        {
            if (!File.Exists(DevicesPath)) return new Dictionary<string, string>();
            try { return Json.Deserialize<Dictionary<string, string>>(File.ReadAllText(DevicesPath)) ?? new Dictionary<string, string>(); }
            catch (ArgumentException) { return new Dictionary<string, string>(); }
        }
        private static void RememberDevice(string username, string deviceId)
        {
            var devices = RememberedDevices(); devices[username] = deviceId;
            Directory.CreateDirectory(Root);
            string temp = DevicesPath + "." + Guid.NewGuid().ToString("N");
            File.WriteAllText(temp, Json.Serialize(devices), new UTF8Encoding(false));
            if (File.Exists(DevicesPath)) File.Replace(temp, DevicesPath, null); else File.Move(temp, DevicesPath);
        }
        public static string NormalizeUrl(string value)
        {
            Uri uri;
            if (!Uri.TryCreate((value ?? "").Trim().TrimEnd('/'), UriKind.Absolute, out uri) || (uri.Scheme != "http" && uri.Scheme != "https") || !string.IsNullOrEmpty(uri.UserInfo) || !string.IsNullOrEmpty(uri.Query) || !string.IsNullOrEmpty(uri.Fragment) || uri.AbsolutePath != "/")
                throw new ArgumentException("请输入 HTTP 或 HTTPS 服务地址。");
            return uri.GetLeftPart(UriPartial.Authority).TrimEnd('/');
        }
        public static void ConfigureIsolatedEnvironment(string configurationPath)
        {
            var file = new FileInfo(Path.GetFullPath(configurationPath));
            if (!file.Exists || file.Length > 16 * 1024) throw new InvalidDataException("独立测试环境文件不存在或过大。");
            var values = Json.Deserialize<Dictionary<string, string>>(File.ReadAllText(file.FullName));
            var names = new[] { "serviceUrl", "accountDir", "settingsPath", "modelsDirectory", "logDirectory" };
            if (values == null || values.Count != names.Length) throw new InvalidDataException("测试环境只能包含服务地址与账号、设置、模型、日志路径。");
            foreach (string name in names) if (!values.ContainsKey(name) || string.IsNullOrWhiteSpace(values[name])) throw new InvalidDataException("测试环境缺少 " + name + "。");
            string service = NormalizeUrl(values["serviceUrl"]);
            if (service == ProductionUrl) throw new InvalidDataException("独立测试不得使用正式服务地址。");
            foreach (string name in new[] { "accountDir", "settingsPath", "modelsDirectory", "logDirectory" })
            {
                if (!Path.IsPathRooted(values[name])) throw new InvalidDataException("测试环境路径必须是绝对路径。");
                values[name] = Path.GetFullPath(values[name]);
            }
            string defaultRoot = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VisionGuard", "accounts");
            if (string.Equals(values["accountDir"].TrimEnd(Path.DirectorySeparatorChar), defaultRoot, StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("独立测试必须指定独立账号目录。");
            Environment.SetEnvironmentVariable("VISIONGUARD_ACCOUNT_DIR", values["accountDir"]);
            Environment.SetEnvironmentVariable("VISIONGUARD_SERVER_URL", service);
            Environment.SetEnvironmentVariable("VISIONGUARD_SETTINGS_PATH", values["settingsPath"]);
            Environment.SetEnvironmentVariable("VISIONGUARD_MODELS_DIR", values["modelsDirectory"]);
            Environment.SetEnvironmentVariable("VISIONGUARD_LOG_DIR", values["logDirectory"]);
            lock (Sync) { _current = null; _lastValidated = DateTime.MinValue; Volatile.Write(ref _published, null); }
        }
        public static void Load()
        {
            lock (Sync)
            {
                _serviceUrl = AllowsTestEndpoint ? Environment.GetEnvironmentVariable("VISIONGUARD_SERVER_URL") : ProductionUrl;
                if (string.IsNullOrWhiteSpace(_serviceUrl))
                {
                    string endpoint = Path.Combine(Root, "endpoint.txt");
                    _serviceUrl = File.Exists(endpoint) ? File.ReadAllText(endpoint).Trim() : ProductionUrl;
                }
                _serviceUrl = NormalizeUrl(_serviceUrl);
                _current = Read();
                Publish();
            }
        }
        private static AccountSnapshot Read()
        {
            if (!File.Exists(StorePath)) return null;
            try
            {
                byte[] clear = ProtectedData.Unprotect(File.ReadAllBytes(StorePath), Encoding.UTF8.GetBytes(_serviceUrl), DataProtectionScope.CurrentUser);
                var value = Json.Deserialize<AccountSnapshot>(Encoding.UTF8.GetString(clear));
                if (value == null || !value.ok || value.account == null || value.device == null || value.serviceUrl != _serviceUrl || string.IsNullOrWhiteSpace(value.token)) return null;
                return value;
            }
            catch (CryptographicException) { return null; }
            catch (ArgumentException) { return null; }
        }
        private static void Write(AccountSnapshot value)
        {
            Directory.CreateDirectory(Root);
            if (value == null) { if (File.Exists(StorePath)) File.Delete(StorePath); return; }
            value.serviceUrl = _serviceUrl;
            byte[] encrypted = ProtectedData.Protect(Encoding.UTF8.GetBytes(Json.Serialize(value)), Encoding.UTF8.GetBytes(_serviceUrl), DataProtectionScope.CurrentUser);
            string temp = StorePath + "." + Guid.NewGuid().ToString("N");
            File.WriteAllBytes(temp, encrypted);
            if (File.Exists(StorePath)) File.Replace(temp, StorePath, null); else File.Move(temp, StorePath);
        }
        private static HttpClient Client(string token = null)
        {
            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
            var client = new HttpClient { Timeout = TimeSpan.FromSeconds(15) };
            if (!string.IsNullOrEmpty(token)) client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
            return client;
        }
        private static AccountSnapshot RequestSession(string endpoint, object body, string token)
        {
            using (var client = Client(token))
            using (var content = new StringContent(Json.Serialize(body), Encoding.UTF8, "application/json"))
            using (var response = client.PostAsync(_serviceUrl + endpoint, content).GetAwaiter().GetResult())
            {
                if (response.StatusCode == HttpStatusCode.Forbidden) throw new DeviceRegistrationRejectedException();
                if (response.StatusCode == HttpStatusCode.Unauthorized) throw new UnauthorizedAccessException("账号或密码无效，或登录已失效。");
                if (!response.IsSuccessStatusCode) throw new InvalidOperationException("服务请求失败（" + (int)response.StatusCode + "）。");
                var value = Json.Deserialize<AccountSnapshot>(response.Content.ReadAsStringAsync().GetAwaiter().GetResult());
                if (value == null || !value.ok || value.account == null || value.device == null || string.IsNullOrEmpty(value.token) || value.resident == null) throw new InvalidDataException("服务登录响应不完整。");
                return value;
            }
        }
        public static AccountSnapshot Login(string serviceUrl, string username, string password, string deviceCode)
        {
            lock (Sync)
            {
                var previousService = ServiceUrl; var previousCurrent = _current;
                try
                {
                    _serviceUrl = AllowsTestEndpoint ? NormalizeUrl(serviceUrl) : ProductionUrl;
                    using (var sessionLock = new SessionWriteLock())
                    {
                        var previous = Read();
                        string normalizedUser = username.Trim().ToLowerInvariant();
                        string rememberedId;
                        RememberedDevices().TryGetValue(normalizedUser, out rememberedId);
                        if (string.IsNullOrWhiteSpace(rememberedId) && previous != null && string.Equals(previous.account.username, normalizedUser, StringComparison.OrdinalIgnoreCase)) rememberedId = previous.device.deviceId;
                        var body = new Dictionary<string, object> { ["username"] = normalizedUser, ["password"] = password, ["component"] = "windows-inference", ["deviceCode"] = deviceCode };
                        if (!string.IsNullOrWhiteSpace(rememberedId)) body["deviceId"] = rememberedId;
                        try { _current = RequestSession("/api/account/login", body, null); }
                        catch (DeviceRegistrationRejectedException) when (body.ContainsKey("deviceId"))
                        {
                            body.Remove("deviceId");
                            _current = RequestSession("/api/account/login", body, null);
                        }
                        RememberDevice(normalizedUser, _current.device.deviceId);
                        Write(_current); _lastValidated = DateTime.UtcNow;
                        File.WriteAllText(Path.Combine(Root, "endpoint.txt"), _serviceUrl, new UTF8Encoding(false));
                        Publish();
                    }
                }
                catch { _serviceUrl = previousService; _current = previousCurrent; throw; }
            }
            Changed?.Invoke(null, EventArgs.Empty);
            return Current;
        }
        public static AccountSnapshot EnsureFresh()
        {
            bool changed = false;
            lock (Sync)
            using (var sessionLock = new SessionWriteLock())
            {
                try
                {
                    var disk = Read();
                    if ((_current == null) != (disk == null) || (_current != null && disk != null && _current.token != disk.token)) changed = true;
#pragma warning disable CS8601 // Shared with C# 7.3 projects; null represents a signed-out session.
                    _current = disk;
#pragma warning restore CS8601
                    if (_current != null)
                    {
                        DateTimeOffset expires;
                        if (!DateTimeOffset.TryParse(_current.expiresAt, out expires) || expires <= DateTimeOffset.UtcNow.AddMinutes(5))
                        {
                            try { _current = RequestSession("/api/account/refresh", new { }, _current.token); Write(_current); changed = true; }
                            catch (UnauthorizedAccessException) { _current = null; Write(null); changed = true; }
                        }
                        else if (DateTime.UtcNow - _lastValidated > TimeSpan.FromSeconds(30))
                        {
                            using (var client = Client(_current.token))
                            using (var response = client.GetAsync(_serviceUrl + "/api/account/session").GetAwaiter().GetResult())
                            {
                                if (response.StatusCode == HttpStatusCode.Unauthorized || response.StatusCode == HttpStatusCode.Forbidden) { _current = null; Write(null); changed = true; }
                                else response.EnsureSuccessStatusCode();
                                _lastValidated = DateTime.UtcNow;
                            }
                        }
                    }
                }
                finally { Publish(); }
            }
            if (changed) Changed?.Invoke(null, EventArgs.Empty);
            return Current;
        }
        public static void Logout()
        {
            lock (Sync)
            using (var sessionLock = new SessionWriteLock())
            {
                // Resident may have rotated the login while this process was waiting for the writer lock.
                var current = Read();
                try
                {
                    if (current != null)
                        using (var client = Client(current.token))
                        using (var response = client.PostAsync(_serviceUrl + "/api/account/logout", new StringContent("{}", Encoding.UTF8, "application/json")).GetAwaiter().GetResult()) { response.EnsureSuccessStatusCode(); }
                }
                finally { _current = null; Publish(); Write(null); }
            }
            Changed?.Invoke(null, EventArgs.Empty);
        }
        public static void RenameDevice(string name)
        {
            var s = Current;
            if (s == null) throw new InvalidOperationException("请先登录。");
            using (var client = Client(s.token))
            using (var request = new HttpRequestMessage(new HttpMethod("PATCH"), ServiceUrl + "/api/devices/" + Uri.EscapeDataString(s.device.deviceId)))
            {
                request.Content = new StringContent(Json.Serialize(new { deviceName = name }), Encoding.UTF8, "application/json");
                using (var response = client.SendAsync(request).GetAwaiter().GetResult()) response.EnsureSuccessStatusCode();
            }
            ApplyDeviceUpdate(new AccountDevice { deviceId = s.device.deviceId, deviceName = name });
        }

        public static void ApplyDeviceUpdate(AccountDevice device)
        {
            if (device == null || string.IsNullOrWhiteSpace(device.deviceName)) return;
            lock (Sync)
            using (var sessionLock = new SessionWriteLock())
            {
                var latest = Read();
                if (latest == null || latest.device.deviceId != device.deviceId) return;
                latest.device.deviceName = device.deviceName;
                if (latest.resident != null) latest.resident.device.deviceName = device.deviceName;
                // Preserve the in-memory token until EnsureFresh observes disk rotation and informs its caller.
                if (_current != null && _current.device.deviceId == device.deviceId)
                {
                    _current.device.deviceName = device.deviceName;
                    if (_current.resident != null) _current.resident.device.deviceName = device.deviceName;
                }
                Write(latest);
                Publish();
            }
        }
    }
}
