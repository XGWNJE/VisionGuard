using System;

namespace VisionGuard.Detector.Windows
{
    /// <summary>
    /// 显式入口点。
    /// WPF 自动生成的入口无法在最早时机设置进程级网络参数，而 Windows 7 上必须在使用
    /// 任何 HTTPS/WSS 之前开启 TLS 1.2：.NET Framework 默认 SystemDefault，在未启用
    /// SCHANNEL 客户端 TLS 1.2 时退化为 TLS 1.0，服务端只接受 1.2 及以上。
    /// </summary>
    internal static class Program
    {
        [STAThread]
        public static int Main(string[] args)
        {
            // Win7：.NET Framework 默认 SystemDefault，在未启用 SCHANNEL 客户端 TLS 1.2 时
            // 退化为 TLS 1.0，服务端只接受 1.2 及以上。必须在任何网络调用前设置。
            System.Net.ServicePointManager.SecurityProtocol |= System.Net.SecurityProtocolType.Tls12;
            var startupArgs = new System.Collections.Generic.List<string>(args);
            int environmentIndex = startupArgs.IndexOf("--isolated-environment");
            if (environmentIndex >= 0)
            {
                if (environmentIndex + 1 >= startupArgs.Count) throw new ArgumentException("缺少独立测试环境文件路径。");
                Utils.AccountSession.ConfigureIsolatedEnvironment(startupArgs[environmentIndex + 1]);
                startupArgs.RemoveRange(environmentIndex, 2); args = startupArgs.ToArray();
            }

            // 驻留拉起契约探针：在两个归档档位产物上都能跑（legacy 档在 Win7 上运行），
            // 不打开主界面、不创建推理会话，只验证「检测端能否把同目录的驻留拉起来」。
            if (args.Length >= 1 && args[0] == "--resident-launch")
                return RunResidentLaunchProbe(args);

            // 本地预览的内置登录：UI 预览脚本用环境变量递入隔离服务的账号，启动即登录，不停在登录页。
            // 要调登录界面时加 `--login-ui`（ui-preview.ps1 的 -LoginUi），或干脆不带这个环境变量。
            if (System.Array.IndexOf(args, "--login-ui") < 0) TryPreviewSignIn();

            // 隔离预览的临时来源写在未分区设置里，登录后补进账号分区（见方法注释）。
            SeedIsolatedPreviewSettings();

            // 先加载账号分区设置，驻留沿用已登录账号的同一设备身份。
            Utils.SettingsStore.Load();

            // 按运行环境选择并预加载 ONNX Runtime 原生库；必须在任何推理调用之前。
            Runtime.NativeLibrarySelector.Initialize();

            // 只在主实例启动时拉起驻留：第二实例会因下方单实例守卫自行退出，不应重复拉起。
            // 驻留负责远程重新打开检测端，缺失或拉起失败都不影响检测本身。
            if (IsPrimaryInstance())
            {
                Runtime.ResidentLauncher.EnsureStarted();
            }

            var app = new App();
            app.InitializeComponent();
            return app.Run();
        }

        /// <summary>
        /// `--resident-launch [report.json] [wait-ms]`：拉起驻留并把状态写成 JSON。
        /// 退出码 0 = 驻留在握手窗口内进入运行状态；1 = 拉起失败（原因写在报告里）。
        /// 与人员 smoke 同样使用真实发布产物，不替代 Win7 实机验收，但能把「同目录驻留能否起来」变成机器可判定检查。
        /// </summary>
        private static int RunResidentLaunchProbe(string[] args)
        {
            // WinExe 默认没有控制台，探针的 stdout 会被丢弃；附加到父控制台才能让调用方直接看到结论。
            AttachConsole(AttachParentProcess);

            string reportPath = args.Length >= 2 ? args[1] : string.Empty;
            int waitMs = 8000;
            if (args.Length >= 3) int.TryParse(args[2], out waitMs);

            // 探针不经过主界面，自己加载同一账号分区设置后再拉起驻留。
            Utils.SettingsStore.Load();

            Runtime.ResidentLauncher.EnsureStarted();
            var status = Runtime.ResidentLauncher.LastStatus;

            // 给驻留一点时间完成认证，便于服务端侧在同一轮里看到它。
            if (status.IsRunning && waitMs > 0) System.Threading.Thread.Sleep(waitMs);

            var refreshed = Runtime.ResidentLauncher.RefreshStatus();
            // 用序列化器生成 JSON：路径里的反斜杠必须转义，手写拼接会产出 PowerShell 5.1
            // 都无法解析的 JSON（实测：ConvertFrom-Json 报 “Unrecognized escape sequence”）。
            string json = System.Text.Json.JsonSerializer.Serialize(
                new
                {
                    isRunning = refreshed.IsRunning,
                    executableFound = refreshed.ExecutableFound,
                    launchAttempted = refreshed.LaunchAttempted,
                    handshakeSucceeded = refreshed.HandshakeSucceeded,
                    executablePath = refreshed.ExecutablePath,
                    failureReason = refreshed.FailureReason,
                    launchLogPath = refreshed.LogPath,
                },
                new System.Text.Json.JsonSerializerOptions { WriteIndented = true });

            Console.WriteLine(json);
            if (!string.IsNullOrEmpty(reportPath))
            {
                string full = System.IO.Path.GetFullPath(reportPath);
                System.IO.Directory.CreateDirectory(System.IO.Path.GetDirectoryName(full));
                System.IO.File.WriteAllText(full, json, new System.Text.UTF8Encoding(false));
            }

            // 脱离父控制台：驻留是常驻进程，会继承本进程的控制台句柄，
            // 不释放会让调用方（PowerShell / CI）一直等控制台关闭，探针看起来像卡住。
            FreeConsole();
            return refreshed.IsRunning ? 0 : 1;
        }

        private const uint AttachParentProcess = 0xFFFFFFFF;

        /// <summary>
        /// 隔离预览的临时来源：`PrepareWindows` 把 4 / 16 路预览来源写在未分区的 settings.ini 里，
        /// 而登录之后设置读写走账号分区路径 `accounts/&lt;scope&gt;/settings.ini`，那份来源就看不见了。
        /// 这里在首次登录后把它复制进账号分区，让预览一开窗就有临时来源。
        /// 只在设置了 VISIONGUARD_SETTINGS_PATH（隔离环境）时生效，且不覆盖已存在的分区设置。
        /// </summary>
        private static void SeedIsolatedPreviewSettings()
        {
            try
            {
                string overridePath = Environment.GetEnvironmentVariable("VISIONGUARD_SETTINGS_PATH");
                if (string.IsNullOrWhiteSpace(overridePath) || Utils.AccountSession.Current == null) return;
                string full = System.IO.Path.GetFullPath(overridePath.Trim());
                string scoped = System.IO.Path.Combine(
                    System.IO.Path.GetDirectoryName(full),
                    "accounts", Utils.AccountSession.ScopeKey, System.IO.Path.GetFileName(full));
                if (System.IO.File.Exists(scoped) || !System.IO.File.Exists(full)) return;
                System.IO.Directory.CreateDirectory(System.IO.Path.GetDirectoryName(scoped));
                System.IO.File.Copy(full, scoped);
            }
            catch (Exception error)
            {
                Utils.LogManager.StaticWarn("[PreviewSignIn] seed settings skipped: " + error.Message);
            }
        }

        /// <summary>
        /// 本地预览的内置登录。环境变量 `VISIONGUARD_PREVIEW_SIGNIN` 是
        /// `{"endpoint":"http://127.0.0.1:4318","username":"vg-test","password":"…"}`。
        ///
        /// 为什么用环境变量而不是隔离配置文件：隔离 JSON 的字段契约明确不含账号密码
        /// （见 docs/60），凭据也就不会随配置落盘。正式运行没有这个变量，行为与以前完全一致。
        /// 登录失败只记一条日志：用户仍可在登录页手填账号密码。
        /// </summary>
        private static void TryPreviewSignIn()
        {
            try
            {
                string raw = Environment.GetEnvironmentVariable("VISIONGUARD_PREVIEW_SIGNIN");
                if (string.IsNullOrWhiteSpace(raw) || Utils.AccountSession.Current != null) return;
                using (var document = System.Text.Json.JsonDocument.Parse(raw))
                {
                    var root = document.RootElement;
                    string endpoint = root.TryGetProperty("endpoint", out var e) ? e.GetString() : null;
                    string username = root.TryGetProperty("username", out var u) ? u.GetString() : null;
                    string password = root.TryGetProperty("password", out var p) ? p.GetString() : null;
                    if (string.IsNullOrWhiteSpace(endpoint) || string.IsNullOrWhiteSpace(username) || string.IsNullOrWhiteSpace(password)) return;
                    Utils.AccountSession.Login(endpoint, username, password);
                }
            }
            catch (Exception error)
            {
                Utils.LogManager.StaticWarn("[PreviewSignIn] " + error.Message);
            }
        }

        [System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool AttachConsole(uint processId);

        [System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool FreeConsole();

        /// <summary>探测本进程是否为主实例（与 App 内的单实例守卫共用同一互斥体名）。</summary>
        private static bool IsPrimaryInstance()
        {
            try
            {
                bool createdNew;
                // initiallyOwned: false —— 只探测是否已存在，不抢占所有权，避免干扰 App 内的单实例守卫。
                using (var mutex = new System.Threading.Mutex(false, @"Local\VisionGuard." + Runtime.ResidentLauncher.ApplicationId, out createdNew))
                {
                    return createdNew;
                }
            }
            catch (Exception)
            {
                // 探测失败时按主实例处理，由 App 内的守卫做最终判定。
                return true;
            }
        }
    }
}
