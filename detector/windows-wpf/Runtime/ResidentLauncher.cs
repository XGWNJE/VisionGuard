using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading;

namespace VisionGuard.Runtime
{
    /// <summary>
    /// 由检测端拉起 Windows 驻留程序（V10 决策 28）。
    ///
    /// 角色反转：驻留不再负责打开检测端，而是由检测端在启动时拉起驻留；驻留自行注册登录自启
    /// 并**脱离父进程生命周期**继续运行，因此检测端正常退出或崩溃后，驻留仍可接受
    /// `open-detector` 远程重新打开请求。
    ///
    /// 拉起失败不得阻断检测功能：这里只记录明确原因，由调用方决定如何呈现。
    /// </summary>
    internal static class ResidentLauncher
    {
        /// <summary>检测端与驻留共用的应用标识；用于单实例互斥体与运行/退出事件名。</summary>
        public const string ApplicationId = "Detector";

        private const string ResidentExeName = "VisionGuard.Resident.exe";
        private const string ResidentMutexName = @"Local\VisionGuard.Resident.SingleInstance";
        private const int HandshakeWaitMs = 6000;

        /// <summary>确保驻留已运行；已在运行则直接返回。</summary>
        public static void EnsureStarted()
        {
            try
            {
                if (IsResidentRunning())
                {
                    Utils.LogManager.StaticInfo("[resident] 驻留已在运行，跳过拉起");
                    return;
                }

                string appDir = AppDomain.CurrentDomain.BaseDirectory;
                string exePath = ResolveResidentPath(appDir);
                if (exePath == null)
                {
                    // 显式记录，不静默：缺少驻留只影响远程重新打开能力，不影响检测本身。
                    Utils.LogManager.StaticWarn("[resident] 未找到驻留程序，已跳过拉起。查找位置：" + DescribeSearchPaths(appDir));
                    return;
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

                if (WaitForRunning(HandshakeWaitMs))
                {
                    Utils.LogManager.StaticInfo("[resident] 驻留已拉起并进入运行状态");
                }
                else
                {
                    Utils.LogManager.StaticWarn("[resident] 驻留已拉起，" + HandshakeWaitMs + "ms 内未上报运行状态");
                }
            }
            catch (Exception ex)
            {
                Utils.LogManager.StaticWarn("[resident] 拉起驻留失败: " + ex.GetType().Name + " - " + ex.Message);
            }
        }

        /// <summary>写驻留配置：与检测端共享服务地址、通道、设备身份与 API Key。</summary>
        private static string WriteConfig(string appDir)
        {
            string directory = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VisionGuard");
            Directory.CreateDirectory(directory);
            string configPath = Path.Combine(directory, "resident-config.json");

            string json =
                "{" + Environment.NewLine +
                "  \"serverUrl\": \"" + Escape(Utils.AppConfig.ServerUrl) + "\"," + Environment.NewLine +
                "  \"apiKey\": \"" + Escape(Utils.AppConfig.ApiKey) + "\"," + Environment.NewLine +
                "  \"deviceId\": \"" + Escape(Utils.AppConfig.DeviceId) + "\"," + Environment.NewLine +
                "  \"deviceName\": \"" + Escape(Environment.MachineName) + "\"," + Environment.NewLine +
                "  \"channel\": \"" + Escape(Utils.AppConfig.Channel) + "\"," + Environment.NewLine +
                // 驻留在收到 open-detector 时按此路径启动检测端，因此由检测端写入自身位置。
                "  \"detectorPath\": \"" + Escape(Path.Combine(appDir, "VisionGuard.exe")) + "\"," + Environment.NewLine +
                "  \"appId\": \"" + ApplicationId + "\"" + Environment.NewLine +
                "}";

            File.WriteAllText(configPath, json, new UTF8Encoding(false));
            Utils.LogManager.StaticInfo("[resident] 配置已写入 " + configPath);
            return configPath;
        }

        private static string Escape(string value) => (value ?? string.Empty).Replace("\\", "\\\\").Replace("\"", "\\\"");

        /// <summary>
        /// 按开发与发布两种布局查找驻留程序：
        ///  发布包：驻留与检测端同目录（发布脚本会把 net472 驻留合并进检测端 ZIP）。
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
    }
}
