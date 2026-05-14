// ┌─────────────────────────────────────────────────────────┐
// │ AutoUpdater.cs (WPF)                                    │
// │ 角色：自动更新检查与下载                                  │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Threading.Tasks;
using System.Windows;

namespace VisionGuard.Utils
{
    public static class AutoUpdater
    {
        private const string CurrentVersion = "4.0.1";
        private const string ServerBase = "https://xgwnje.cn";

        public static async Task CheckUpdateAsync()
        {
            try
            {
                using var client = new HttpClient();
                client.DefaultRequestHeaders.Add("X-API-Key", AppConfig.ApiKey);
                var json = await client.GetStringAsync(
                    $"{ServerBase}/api/update?platform=wpf&version={CurrentVersion}");

                using var doc = System.Text.Json.JsonDocument.Parse(json);
                var root = doc.RootElement;

                if (!root.TryGetProperty("ok", out var okProp) || !okProp.GetBoolean()) return;
                if (!root.TryGetProperty("hasUpdate", out var hasProp) || !hasProp.GetBoolean()) return;

                var latest = root.TryGetProperty("latestVersion", out var lv) ? lv.GetString() ?? "" : "";
                var relUrl = root.TryGetProperty("downloadUrl", out var du) ? du.GetString() ?? "" : "";
                if (string.IsNullOrEmpty(relUrl)) return;

                var msg = $"发现新版本 {latest}（当前 {CurrentVersion}）。\n\n" +
                          $"为保持兼容性，必须更新后才能继续使用。\n" +
                          $"点击「确定」立即下载并安装。";

                await Application.Current.Dispatcher.InvokeAsync(() =>
                {
                    MessageBox.Show(msg, "VisionGuard 强制更新",
                        MessageBoxButton.OK, MessageBoxImage.Warning);
                });

                var fullUrl = relUrl.StartsWith("http") ? relUrl : ServerBase + relUrl;
                await DownloadAndReplaceAsync(fullUrl, latest);
            }
            catch (Exception ex)
            {
                LogManager.StaticWarn($"[AutoUpdater] 检查更新失败: {ex.Message}");
            }
        }

        private static async Task DownloadAndReplaceAsync(string url, string newVersion)
        {
            var tempDir = Path.Combine(Path.GetTempPath(), "VisionGuardUpdate");
            try
            {
                Directory.CreateDirectory(tempDir);

                // 下载 ZIP
                LogManager.StaticInfo($"[AutoUpdater] 正在下载更新包…");
                using (var client = new HttpClient())
                {
                    var bytes = await client.GetByteArrayAsync(url);
                    await File.WriteAllBytesAsync(Path.Combine(tempDir, $"VisionGuard-WPF-v{newVersion}.zip"), bytes);
                }

                // 解压
                var zipPath = Path.Combine(tempDir, $"VisionGuard-WPF-v{newVersion}.zip");
                LogManager.StaticInfo("[AutoUpdater] 解压中…");
                var extractDir = Path.Combine(tempDir, "extracted");
                Directory.CreateDirectory(extractDir);
                System.IO.Compression.ZipFile.ExtractToDirectory(zipPath, extractDir, true);

                // 写 updater.bat
                var appDir = AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\');
                var appExe = Path.Combine(appDir, "VisionGuard.exe");
                var script = $@"@echo off
timeout /t 2 /nobreak >nul
echo [updater] Replacing files...
xcopy /E /Y /Q ""{extractDir}\*"" ""{appDir}\""
echo [updater] Launching new version...
start """""" ""{appExe}""
";
                await File.WriteAllTextAsync(Path.Combine(tempDir, "updater.bat"), script);

                // 启动 updater（bat 文件必须用 cmd.exe /c 执行）
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

                Application.Current?.Shutdown();
            }
            catch (Exception ex)
            {
                LogManager.StaticWarn($"[AutoUpdater] 更新失败: {ex.Message}");
                MessageBox.Show($"更新失败: {ex.Message}", "错误", MessageBoxButton.OK, MessageBoxImage.Error);
                // 清理残留
                try { if (Directory.Exists(tempDir)) Directory.Delete(tempDir, true); } catch { }
            }
        }
    }
}
