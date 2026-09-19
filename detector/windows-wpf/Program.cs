using System;

namespace VisionGuard
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

            // 驻留拉起契约探针：在两个归档档位产物上都能跑（legacy 档在 Win7 上运行），
            // 不打开主界面、不创建推理会话，只验证「检测端能否把同目录的驻留拉起来」。
            if (args.Length >= 1 && args[0] == "--resident-launch")
                return RunResidentLaunchProbe(args);

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

            // 探针不经过主界面，因此必须自己加载设置：否则 AppConfig.DeviceId 每次都新生成一个 GUID，
            // 写出的驻留配置与检测端自身的设备身份对不上，服务端就会把驻留当成另一台设备。
            SeedIsolatedDeviceIdIfRequested();
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

        /// <summary>
        /// 隔离验证专用：若 VISIONGUARD_SETTINGS_PATH 指向的文件不存在，先写入一个测试专属 DeviceId。
        /// 只有自动化验证会设置该变量，普通运行不受影响；这样探针与它拉起的驻留共用同一设备身份。
        /// </summary>
        private static void SeedIsolatedDeviceIdIfRequested()
        {
            string path = System.Environment.GetEnvironmentVariable("VISIONGUARD_SETTINGS_PATH");
            if (string.IsNullOrWhiteSpace(path) || System.IO.File.Exists(path)) return;

            string directory = System.IO.Path.GetDirectoryName(System.IO.Path.GetFullPath(path));
            if (!string.IsNullOrEmpty(directory)) System.IO.Directory.CreateDirectory(directory);
            System.IO.File.WriteAllText(path,
                "# VisionGuard 用户设置（自动化验证隔离文件）" + System.Environment.NewLine +
                "DeviceId=" + System.Guid.NewGuid().ToString() + System.Environment.NewLine,
                new System.Text.UTF8Encoding(false));
        }

        private const uint AttachParentProcess = 0xFFFFFFFF;

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
