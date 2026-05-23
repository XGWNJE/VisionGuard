using System;
using System.Diagnostics;
using System.IO;
using VisionGuard.Utils;

namespace VisionGuard.Services
{
    internal sealed class LegacyTlsTunnelService : IDisposable
    {
        public const string LocalServerUrl = "http://127.0.0.1:18080";

        private const string RemoteHost = "visionguard.xgwnje.cn";
        private const int RemotePort = 443;
        private const int LocalPort = 18080;

        private Process _process;

        public bool IsRunning => _process != null && !_process.HasExited;

        public bool TryStart()
        {
            if (IsRunning) return true;

            var baseDir = AppDomain.CurrentDomain.BaseDirectory;
            var stunnelExe = Path.Combine(baseDir, "tools", "stunnel", "stunnel.exe");
            if (!File.Exists(stunnelExe))
            {
                LogManager.StaticWarn($"[LegacyTlsTunnel] stunnel not found: {stunnelExe}");
                return false;
            }

            try
            {
                var configPath = WriteConfig(baseDir);
                var psi = new ProcessStartInfo
                {
                    FileName = stunnelExe,
                    Arguments = "\"" + configPath + "\"",
                    WorkingDirectory = Path.GetDirectoryName(stunnelExe),
                    CreateNoWindow = true,
                    UseShellExecute = false,
                    WindowStyle = ProcessWindowStyle.Hidden,
                };

                _process = Process.Start(psi);
                LogManager.StaticInfo($"[LegacyTlsTunnel] started pid={_process?.Id} local=127.0.0.1:{LocalPort} remote={RemoteHost}:{RemotePort}");
                return IsRunning;
            }
            catch (Exception ex)
            {
                LogManager.StaticWarn($"[LegacyTlsTunnel] start failed: {ex.Message}");
                return false;
            }
        }

        private static string WriteConfig(string baseDir)
        {
            var appDataDir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "VisionGuard");
            Directory.CreateDirectory(appDataDir);

            var logPath = Path.Combine(appDataDir, "stunnel.log");
            var caPath = Path.Combine(baseDir, "tools", "stunnel", "ca-certs.pem");
            var configPath = Path.Combine(appDataDir, "stunnel-visionguard.conf");

            var verifyLines = File.Exists(caPath)
                ? "verifyChain = yes\r\nCAfile = " + EscapePath(caPath) + "\r\ncheckHost = " + RemoteHost + "\r\n"
                : "verifyChain = no\r\n";

            File.WriteAllText(configPath,
                "client = yes\r\n" +
                "foreground = yes\r\n" +
                "debug = notice\r\n" +
                "output = " + EscapePath(logPath) + "\r\n" +
                "\r\n" +
                "[visionguard-ws]\r\n" +
                "accept = 127.0.0.1:" + LocalPort + "\r\n" +
                "connect = " + RemoteHost + ":" + RemotePort + "\r\n" +
                "sni = " + RemoteHost + "\r\n" +
                verifyLines);

            return configPath;
        }

        private static string EscapePath(string path)
        {
            return path.Replace("\\", "\\\\");
        }

        public void Dispose()
        {
            try
            {
                if (_process != null && !_process.HasExited)
                {
                    LogManager.StaticInfo($"[LegacyTlsTunnel] stopping pid={_process.Id}");
                    _process.Kill();
                    _process.WaitForExit(2000);
                }
            }
            catch (Exception ex)
            {
                LogManager.StaticWarn($"[LegacyTlsTunnel] stop failed: {ex.Message}");
            }
            finally
            {
                _process?.Dispose();
                _process = null;
            }
        }
    }
}
