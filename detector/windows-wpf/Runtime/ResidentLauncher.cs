using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Text.Json;
using System.Threading;
using VisionGuard.Utils;

namespace VisionGuard.Runtime
{
    /// <summary>驻留程序在本机的拉起状态与最近一次失败原因。</summary>
    internal sealed class ResidentStatus
    {
        /// <summary>驻留可执行文件是否存在于预期位置。</summary>
        public bool ExecutableFound { get; set; }
        /// <summary>驻留当前是否在运行（按单实例互斥体判定）。</summary>
        public bool IsRunning { get; set; }
        /// <summary>本次进程是否尝试过拉起（已运行时不重复拉起）。</summary>
        public bool LaunchAttempted { get; set; }
        /// <summary>拉起后是否在握手窗口内进入运行状态。</summary>
        public bool HandshakeSucceeded { get; set; }
        /// <summary>解析到的驻留可执行文件路径（未找到时为空）。</summary>
        public string ExecutablePath { get; set; } = string.Empty;
        /// <summary>未找到或拉起失败的原因（成功时为空）。</summary>
        public string FailureReason { get; set; } = string.Empty;
        /// <summary>最近一次写入的详细日志路径。</summary>
        public string LogPath { get; set; } = string.Empty;

        /// <summary>面向界面的单行状态文案。</summary>
        public string Describe()
        {
            if (IsRunning) return "运行中";
            if (!ExecutableFound) return "未找到驻留程序";
            if (!string.IsNullOrEmpty(FailureReason)) return "未运行（" + FailureReason + "）";
            return "未运行";
        }
    }

    /// <summary>完整退出时关闭驻留的结果；失败时主体必须保持打开以避免留下未知后台状态。</summary>
    internal sealed class ResidentExitResult
    {
        public bool Succeeded { get; set; }
        public string FailureReason { get; set; } = string.Empty;
    }

    /// <summary>
    /// 由检测端拉起 Windows 驻留程序（V10 决策 28）。
    ///
    /// 角色反转：驻留不再负责打开检测端，而是由检测端在启动时拉起驻留；驻留自行注册登录自启
    /// 并**脱离父进程生命周期**继续运行，因此检测端正常退出或崩溃后，驻留仍可接受
    /// `open-detector` 远程重新打开请求。
    ///
    /// 拉起失败不得阻断检测功能，但**必须可见**：驻留是独立进程，检测端无法从自身状态推断它是否活着，
    /// 若只写 Debug 输出，Release 包在用户机器上就是完全静默的（Win7 实测：驻留没起来，界面上没有任何提示，
    /// 接收端也看不到入口）。因此这里把状态与失败原因同时暴露给界面，并额外写一份可带走的日志文件。
    /// </summary>
    internal static class ResidentLauncher
    {
        /// <summary>检测端与驻留共用的应用标识；用于单实例互斥体与运行/退出事件名。</summary>
        public const string ApplicationId = "Detector";

        private const string ResidentExeName = "VisionGuard.Resident.exe";
        private const string ResidentExeConfigName = "VisionGuard.Resident.exe.config";
        private const string ResidentMutexName = @"Local\VisionGuard.Resident.SingleInstance";
        private const string ResidentShutdownEventName = @"Local\VisionGuard.Resident.Shutdown";
        private const int HandshakeWaitMs = 6000;
        private const int ShutdownWaitMs = 6000;

        private static readonly object StatusLock = new object();
        private static ResidentStatus _status = new ResidentStatus();

        /// <summary>最近一次拉起的状态快照。</summary>
        public static ResidentStatus LastStatus
        {
            get { lock (StatusLock) return _status; }
        }

        /// <summary>重新按当前进程事实刷新状态（不触发拉起）。</summary>
        public static ResidentStatus RefreshStatus()
        {
            var status = new ResidentStatus
            {
                ExecutablePath = ResolveResidentPath(AppDomain.CurrentDomain.BaseDirectory) ?? string.Empty,
                LogPath = LogFilePath,
            };
            status.ExecutableFound = !string.IsNullOrEmpty(status.ExecutablePath);
            status.IsRunning = IsResidentRunning();

            lock (StatusLock)
            {
                // 保留本次进程此前的拉起结论（是否尝试过、握手是否成功、失败原因）。
                status.LaunchAttempted = _status.LaunchAttempted;
                status.HandshakeSucceeded = _status.HandshakeSucceeded;
                status.FailureReason = status.IsRunning ? string.Empty : _status.FailureReason;
                _status = status;
                return _status;
            }
        }

        /// <summary>确保驻留已运行；已在运行则直接返回。</summary>
        public static void EnsureStarted()
        {
            try
            {
                if (IsResidentRunning())
                {
                    MarkRunning("驻留已在运行，跳过拉起");
                    return;
                }

                string appDir = AppDomain.CurrentDomain.BaseDirectory;
                string exePath = ResolveResidentPath(appDir);
                if (exePath == null)
                {
                    string reason = "未找到 " + ResidentExeName + "，查找位置：" + DescribeSearchPaths(appDir);
                    MarkNotFound(reason);
                    Log("WARN [resident] " + reason);
                    return;
                }

                if (!File.Exists(Path.Combine(Path.GetDirectoryName(exePath), ResidentExeConfigName)))
                {
                    // 缺 .config 会让驻留在 .NET Framework 上以错误的运行时假设启动，必须显式提示。
                    Log("WARN [resident] 缺少 " + ResidentExeConfigName + "（与 " + exePath + " 同目录），仍按原样尝试拉起");
                }

                string configPath = WriteConfig(appDir);
                var startInfo = new ProcessStartInfo
                {
                    FileName = exePath,
                    Arguments = "--config \"" + configPath + "\"",
                    WorkingDirectory = appDir,
                    // 关键：脱离父进程生命周期，检测端退出或崩溃后驻留继续运行。
                    UseShellExecute = true,
                };
                Process.Start(startInfo);

                bool handshake = WaitForRunning(HandshakeWaitMs);
                lock (StatusLock)
                {
                    _status = new ResidentStatus
                    {
                        ExecutableFound = true,
                        ExecutablePath = exePath,
                        LaunchAttempted = true,
                        HandshakeSucceeded = handshake,
                        IsRunning = handshake,
                        LogPath = LogFilePath,
                        FailureReason = handshake ? string.Empty
                            : "已启动但在 " + HandshakeWaitMs + "ms 内未进入运行状态，详见 " + LogFilePath,
                    };
                }

                if (handshake) Log("INFO [resident] 驻留已拉起并进入运行状态：" + exePath);
                else Log("WARN [resident] 驻留已拉起，" + HandshakeWaitMs + "ms 内未上报运行状态，详见 " + LogFilePath);
            }
            catch (Exception ex)
            {
                string reason = ex.GetType().Name + " - " + ex.Message;
                lock (StatusLock)
                {
                    _status = new ResidentStatus
                    {
                        ExecutableFound = _status.ExecutableFound,
                        ExecutablePath = _status.ExecutablePath,
                        LaunchAttempted = true,
                        IsRunning = false,
                        LogPath = LogFilePath,
                        FailureReason = reason,
                    };
                }
                Log("WARN [resident] 拉起驻留失败: " + reason);
            }
        }

        /// <summary>
        /// 为用户发起的「完整退出」停止驻留并取消登录自启。
        /// 先去掉自启，再通过驻留专用事件请求优雅退出；只有确认互斥体消失才允许主体关闭。
        /// </summary>
        public static ResidentExitResult StopForCompleteExit()
        {
            string exePath = ResolveResidentPath(AppDomain.CurrentDomain.BaseDirectory);
            if (string.IsNullOrEmpty(exePath))
            {
                return new ResidentExitResult
                {
                    Succeeded = false,
                    FailureReason = "未找到驻留程序，无法确认已取消登录自启。"
                };
            }

            string startupFailure;
            if (!RunResidentCommand(exePath, "--disable-startup", out startupFailure))
            {
                return new ResidentExitResult
                {
                    Succeeded = false,
                    FailureReason = "取消驻留登录自启失败：" + startupFailure
                };
            }

            if (!IsResidentRunning())
            {
                Log("INFO [resident] 完整退出：驻留未运行，已取消登录自启");
                return new ResidentExitResult { Succeeded = true };
            }

            if (!SignalResidentShutdown())
            {
                return new ResidentExitResult
                {
                    Succeeded = false,
                    FailureReason = "未能向驻留发送退出请求。"
                };
            }

            if (!WaitForStopped(ShutdownWaitMs))
            {
                return new ResidentExitResult
                {
                    Succeeded = false,
                    FailureReason = "驻留在 " + ShutdownWaitMs + "ms 内未退出。"
                };
            }

            Log("INFO [resident] 完整退出：驻留已停止，登录自启已取消");
            return new ResidentExitResult { Succeeded = true };
        }

        private static void MarkRunning(string note)
        {
            var status = new ResidentStatus
            {
                ExecutablePath = ResolveResidentPath(AppDomain.CurrentDomain.BaseDirectory) ?? string.Empty,
                IsRunning = true,
                LaunchAttempted = false,
                HandshakeSucceeded = true,
                LogPath = LogFilePath,
            };
            status.ExecutableFound = !string.IsNullOrEmpty(status.ExecutablePath);

            lock (StatusLock) _status = status;
            Log("INFO [resident] " + note);
        }

        private static void MarkNotFound(string reason)
        {
            lock (StatusLock)
            {
                _status = new ResidentStatus
                {
                    ExecutableFound = false,
                    IsRunning = false,
                    LaunchAttempted = false,
                    HandshakeSucceeded = false,
                    FailureReason = reason,
                    LogPath = LogFilePath,
                };
            }
        }

        /// <summary>驻留拉起日志路径：与驻留自身日志同目录，便于一并带走。</summary>
        public static string LogFilePath => Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VisionGuard", "resident-launch.log");

        /// <summary>
        /// 写拉起日志。Debug.WriteLine 在 Release 包里没有任何落点，用户机器上无法取证，
        /// 因此关键结论同时写进文件。
        /// </summary>
        private static void Log(string message)
        {
            LogManager.StaticInfo("[resident] " + message);
            try
            {
                string path = LogFilePath;
                Directory.CreateDirectory(Path.GetDirectoryName(path));
                File.AppendAllText(path, DateTimeOffset.Now.ToString("O") + " " + message + Environment.NewLine, new UTF8Encoding(false));
            }
            catch
            {
                // 日志写入失败不影响检测功能。
            }
        }

        /// <summary>
        /// 写驻留配置：与检测端共享服务地址、通道、设备身份与 API Key。
        ///
        /// 必须用 JSON 序列化器生成，不能手写字符串拼接：
        /// Windows 路径里的单个反斜杠在 JSON 里是非法转义序列，用 XML 转义（`\` → `\`）写出来
        /// 会让驻留在 `JavaScriptSerializer.Deserialize` 处直接 fatal 退出（2026-09-17 实测根因：
        /// 检测端每次启动都重写配置，驻留在 Win7 与 Win10 上都是“起来了但立刻死”，
        /// 而界面上完全没有提示）。
        /// </summary>
        private static string WriteConfig(string appDir)
        {
            string directory = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VisionGuard");
            Directory.CreateDirectory(directory);
            string configPath = Path.Combine(directory, "resident-config.json");

            var payload = new Dictionary<string, string>
            {
                ["serverUrl"] = AppConfig.ServerUrl ?? string.Empty,
                ["apiKey"] = AppConfig.ApiKey ?? string.Empty,
                ["deviceId"] = AppConfig.DeviceId ?? string.Empty,
                ["deviceName"] = Environment.MachineName,
                ["channel"] = AppConfig.Channel ?? string.Empty,
                // 驻留在收到 open-detector 时按此路径启动检测端，因此由检测端写入自身位置。
                ["detectorPath"] = Path.Combine(appDir, "VisionGuard.exe"),
                ["appId"] = ApplicationId,
            };

            string json = JsonSerializer.Serialize(payload);
            File.WriteAllText(configPath, json, new UTF8Encoding(false));

            // 自检：写出的配置必须能被同一族序列化器读回，避免把非法 JSON 交给驻留后才在它那边 fatal。
            var roundTrip = JsonSerializer.Deserialize<Dictionary<string, string>>(json);
            if (roundTrip == null || roundTrip.Count != payload.Count
                || !string.Equals(roundTrip["detectorPath"], payload["detectorPath"], StringComparison.Ordinal))
            {
                throw new InvalidDataException("驻留配置自检失败，拒绝以可能损坏的配置拉起驻留：" + configPath);
            }

            return configPath;
        }

        /// <summary>
        /// 按开发与发布两种布局查找驻留程序：
        ///  发布包：驻留与检测端同目录（构建与发布都会把驻留放进检测端输出目录）。
        ///  开发目录：驻留构建在 detector\windows-resident\bin\Release\net472\。
        /// </summary>
        private static string[] SearchPaths(string appDir)
        {
            return new[]
            {
                Path.Combine(appDir, ResidentExeName),
                Path.Combine(appDir, "resident", ResidentExeName),
                // 开发布局：检测端在 detector\windows-wpf\bin\x64\<档位>\，驻留在 detector\windows-resident\bin\Release\net472\。
                Path.Combine(appDir, "..", "..", "..", "..", "windows-resident", "bin", "Release", "net472", ResidentExeName),
            };
        }

        private static string ResolveResidentPath(string appDir)
        {
            foreach (string candidate in SearchPaths(appDir))
            {
                string full = Path.GetFullPath(candidate);
                if (File.Exists(full)) return full;
            }
            return null;
        }

        private static string DescribeSearchPaths(string appDir)
        {
            var parts = new StringBuilder();
            foreach (string candidate in SearchPaths(appDir))
            {
                if (parts.Length > 0) parts.Append(" | ");
                parts.Append(Path.GetFullPath(candidate));
            }
            return parts.ToString();
        }

        private static bool IsResidentRunning()
        {
            try
            {
                using (Mutex mutex = Mutex.OpenExisting(ResidentMutexName))
                {
                    return true;
                }
            }
            catch (WaitHandleCannotBeOpenedException)
            {
                return false;
            }
            catch (UnauthorizedAccessException)
            {
                // 存在但无权限打开：按“已在运行”处理，避免误拉第二个实例。
                return true;
            }
        }

        private static bool WaitForRunning(int timeoutMs)
        {
            var deadline = DateTime.UtcNow.AddMilliseconds(timeoutMs);
            while (DateTime.UtcNow < deadline)
            {
                if (IsResidentRunning()) return true;
                Thread.Sleep(200);
            }
            return false;
        }

        private static bool WaitForStopped(int timeoutMs)
        {
            var deadline = DateTime.UtcNow.AddMilliseconds(timeoutMs);
            while (DateTime.UtcNow < deadline)
            {
                if (!IsResidentRunning()) return true;
                Thread.Sleep(100);
            }
            return !IsResidentRunning();
        }

        private static bool SignalResidentShutdown()
        {
            try
            {
                using (EventWaitHandle handle = EventWaitHandle.OpenExisting(ResidentShutdownEventName))
                {
                    handle.Set();
                    return true;
                }
            }
            catch (WaitHandleCannotBeOpenedException)
            {
                return false;
            }
        }

        private static bool RunResidentCommand(string executablePath, string arguments, out string failure)
        {
            failure = string.Empty;
            try
            {
                using (Process process = Process.Start(new ProcessStartInfo
                {
                    FileName = executablePath,
                    Arguments = arguments,
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    WorkingDirectory = Path.GetDirectoryName(executablePath),
                }))
                {
                    if (process == null)
                    {
                        failure = "进程未启动";
                        return false;
                    }
                    if (!process.WaitForExit(ShutdownWaitMs))
                    {
                        failure = "命令超时";
                        return false;
                    }
                    if (process.ExitCode == 0) return true;
                    failure = "退出码 " + process.ExitCode;
                    return false;
                }
            }
            catch (Exception ex)
            {
                failure = ex.GetType().Name + " - " + ex.Message;
                return false;
            }
        }
    }
}
