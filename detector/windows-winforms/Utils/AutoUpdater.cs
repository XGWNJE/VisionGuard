// ┌─────────────────────────────────────────────────────────┐
// │ AutoUpdater.cs                                          │
// │ 角色：自动更新检查与下载                                  │
// │ 职责：启动时检查新版本 → 下载 ZIP → 启动 updater 替换    │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Windows.Forms;

namespace VisionGuard.Utils
{
    public static class AutoUpdater
    {
        private const string CurrentVersion = "4.0.0";
        private const string ServerBase = "https://xgwnje.cn";

        public static void CheckUpdate(string apiKey)
        {
            try
            {
                ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
                using (var wc = new WebClient())
                {
                    wc.Headers.Add("X-API-Key", apiKey ?? "");
                    var url = $"{ServerBase}/api/update?platform=winforms&version={CurrentVersion}";
                    var json = wc.DownloadString(url);
                    var d = SimpleJson.ParseDict(json);

                    if (!d.TryGetValue("ok", out var v1) || !(v1 is bool b1) || !b1) return;
                    if (!d.TryGetValue("hasUpdate", out var v2) || !(v2 is bool b2) || !b2) return;

                    string latest = SimpleJson.GetString(d, "latestVersion");
                    string relUrl = SimpleJson.GetString(d, "downloadUrl");
                    if (string.IsNullOrEmpty(relUrl)) return;

                    var msg = $"发现新版本 {latest}（当前 {CurrentVersion}）。\n\n" +
                              $"为保持兼容性，必须更新后才能继续使用。\n" +
                              $"点击「确定」立即下载并安装。";

                    MessageBox.Show(msg, "VisionGuard 强制更新",
                        MessageBoxButtons.OK, MessageBoxIcon.Warning);

                    var fullUrl = relUrl.StartsWith("http") ? relUrl : ServerBase + relUrl;
                    DownloadAndReplace(fullUrl, latest, apiKey);
                }
            }
            catch (Exception ex)
            {
                LogManager.StaticWarn($"[AutoUpdater] 检查更新失败: {ex.Message}");
            }
        }

        private static void DownloadAndReplace(string url, string newVersion, string apiKey)
        {
            var tempDir = Path.Combine(Path.GetTempPath(), "VisionGuardUpdate");
            try
            {
                if (Directory.Exists(tempDir)) Directory.Delete(tempDir, true);
                Directory.CreateDirectory(tempDir);

                var zipPath = Path.Combine(tempDir, $"VisionGuard-v{newVersion}.zip");
                LogManager.StaticInfo($"[AutoUpdater] 正在下载更新包…");
                using (var wc = new WebClient())
                {
                    wc.Headers.Add("X-API-Key", apiKey ?? "");
                    wc.DownloadFile(url, zipPath);
                }

                var extractDir = Path.Combine(tempDir, "extracted");
                Directory.CreateDirectory(extractDir);
                LogManager.StaticInfo("[AutoUpdater] 解压中…");
                System.IO.Compression.ZipFile.ExtractToDirectory(zipPath, extractDir);

                var appDir = AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\');
                var appExe = Path.Combine(appDir, "VisionGuard.exe");
                var script = $@"@echo off
timeout /t 2 /nobreak >nul
echo [updater] Replacing files...
xcopy /E /Y /Q ""{extractDir}\*"" ""{appDir}\""
echo [updater] Launching new version...
start """""" ""{appExe}""
";

                File.WriteAllText(Path.Combine(tempDir, "updater.bat"), script, System.Text.Encoding.Default);

                LogManager.StaticInfo("[AutoUpdater] 启动 updater，主程序即将退出…");
                Process.Start(new ProcessStartInfo
                {
                    FileName = "cmd.exe",
                    Arguments = $"/c \"{Path.Combine(tempDir, "updater.bat")}\"",
                    WorkingDirectory = tempDir,
                    CreateNoWindow = true,
                    UseShellExecute = false,
                    WindowStyle = ProcessWindowStyle.Hidden,
                });

                Application.Exit();
            }
            catch (Exception ex)
            {
                LogManager.StaticWarn($"[AutoUpdater] 更新失败: {ex.Message}");
                MessageBox.Show($"更新失败: {ex.Message}", "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
                try { if (Directory.Exists(tempDir)) Directory.Delete(tempDir, true); } catch { }
            }
        }
    }
}
