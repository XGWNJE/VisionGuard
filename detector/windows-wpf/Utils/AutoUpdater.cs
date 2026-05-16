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
        private const string ServerBase = "https://xgwnje.cn";

        public static async Task CheckUpdateAsync()
        {
            try
            {
                using var client = new HttpClient();
                client.DefaultRequestHeaders.Add("X-API-Key", AppConfig.ApiKey);
                var json = await client.GetStringAsync(
                    $"{ServerBase}/api/update?platform=wpf&version={AppConfig.Version}");

                using var doc = System.Text.Json.JsonDocument.Parse(json);
                var root = doc.RootElement;

                if (!root.TryGetProperty("ok", out var okProp) || !okProp.GetBoolean()) return;
                if (!root.TryGetProperty("hasUpdate", out var hasProp) || !hasProp.GetBoolean()) return;

                var latest = root.TryGetProperty("latestVersion", out var lv) ? lv.GetString() ?? "" : "";
                var relUrl = root.TryGetProperty("downloadUrl", out var du) ? du.GetString() ?? "" : "";
                if (string.IsNullOrEmpty(relUrl)) return;

                var msg = $"发现新版本 {latest}（当前 {AppConfig.Version}）。\n\n" +
                          $"为保持兼容性，必须更新后才能继续使用。\n" +
                          $"点击「确定」立即下载并安装。";

                var result = MessageBoxResult.None;
                await Application.Current.Dispatcher.InvokeAsync(() =>
                {
                    result = MessageBox.Show(msg, "VisionGuard 强制更新",
                        MessageBoxButton.OK, MessageBoxImage.Warning);
                });
                if (result != MessageBoxResult.OK) return;

                var fullUrl = relUrl.StartsWith("http") ? relUrl : ServerBase + relUrl;
                await DownloadAsync(fullUrl, latest);
            }
            catch (Exception ex)
            {
                LogManager.StaticWarn($"[AutoUpdater] 检查更新失败: {ex.Message}");
            }
        }

        private static async Task DownloadAsync(string url, string newVersion)
        {
            var tempDir = Path.Combine(Path.GetTempPath(), "VisionGuardUpdate");
            try
            {
                if (Directory.Exists(tempDir)) Directory.Delete(tempDir, true);
                Directory.CreateDirectory(tempDir);

                // 下载
                LogManager.StaticInfo("[AutoUpdater] 正在下载更新包… 文件较大（约300MB），请耐心等待");
                var zipPath = Path.Combine(tempDir, "update.zip");
                var sw = Stopwatch.StartNew();
                using (var client = new HttpClient())
                {
                    var bytes = await client.GetByteArrayAsync(url);
                    await File.WriteAllBytesAsync(zipPath, bytes);
                }
                sw.Stop();
                LogManager.StaticInfo($"[AutoUpdater] 下载完成 ({new FileInfo(zipPath).Length / 1048576.0:F1} MB, 耗时 {sw.Elapsed.TotalSeconds:F1}s)");

                // 解压
                var extractDir = Path.Combine(tempDir, "extracted");
                Directory.CreateDirectory(extractDir);
                System.IO.Compression.ZipFile.ExtractToDirectory(zipPath, extractDir, true);
                LogManager.StaticInfo("[AutoUpdater] 解压完成");

                // 写 PowerShell updater 脚本
                var appDir = AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\');
                var appExe = Path.Combine(appDir, "VisionGuard.exe");
                var psPath = Path.Combine(tempDir, "updater.ps1");

                // 单引号转义：PowerShell 单引号字串中 '' 表示一个字面单引号
                string esc(string s) => s.Replace("'", "''");

                var psScript = $@"
$host.UI.RawUI.WindowTitle = 'VisionGuard Updater'
Write-Host 'Waiting for old app to exit...'
Start-Sleep -Seconds 3

Write-Host 'Copying files...'
$maxRetries = 3
$retry = 0
while ($retry -lt $maxRetries) {{
    try {{
        Copy-Item -Recurse -Force '{esc(extractDir)}\*' '{esc(appDir)}\' -ErrorAction Stop
        Write-Host 'Copy succeeded.'
        break
    }} catch {{
        $retry++
        if ($retry -ge $maxRetries) {{
            Write-Host ""Copy failed after $maxRetries retries: $_""
            throw
        }}
        Write-Host ""Copy failed, retry $retry/$maxRetries after 2s...""
        Start-Sleep -Seconds 2
    }}
}}

Write-Host 'Cleaning up temp files...'
Remove-Item -Recurse -Force '{esc(tempDir)}' -ErrorAction SilentlyContinue

Write-Host 'Starting new version...'
Start-Process -FilePath '{esc(appExe)}' -WorkingDirectory '{esc(appDir)}'

Write-Host 'Done.' | Out-File -LiteralPath '{esc(Path.Combine(tempDir, "done.txt"))}'
";
                await File.WriteAllTextAsync(psPath, psScript);

                var pid = Environment.ProcessId;
                LogManager.StaticInfo($"[AutoUpdater] 启动 updater，主进程 PID={pid} 即将退出");

                var startInfo = new ProcessStartInfo
                {
                    FileName = "powershell.exe",
                    Arguments = $"-NoProfile -ExecutionPolicy Bypass -File \"{psPath}\"",
                    UseShellExecute = true,
                };
                Process.Start(startInfo);

                // 强制退出：先尝试正常关闭，再强制杀进程
                Task.Delay(500).ContinueWith(_ => Environment.Exit(0));
                Application.Current?.Dispatcher.Invoke(() => Application.Current.Shutdown());
            }
            catch (Exception ex)
            {
                LogManager.StaticWarn($"[AutoUpdater] 更新失败: {ex.Message}");
                MessageBox.Show($"更新失败: {ex.Message}", "错误", MessageBoxButton.OK, MessageBoxImage.Error);
                try { if (Directory.Exists(tempDir)) Directory.Delete(tempDir, true); } catch { }
            }
        }
    }
}
