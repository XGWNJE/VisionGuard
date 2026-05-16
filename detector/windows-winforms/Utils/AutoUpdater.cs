// ┌─────────────────────────────────────────────────────────┐
// │ AutoUpdater.cs                                          │
// │ 角色：自动更新检查与下载                                  │
// │ 职责：启动时检查新版本 → 下载 ZIP → 启动 updater 替换    │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace VisionGuard.Utils
{
    public static class AutoUpdater
    {
        private static readonly string CurrentVersion =
            System.Environment.GetEnvironmentVariable("VISIONGUARD_TEST_VERSION") ??
            System.Reflection.Assembly.GetExecutingAssembly().GetName().Version.ToString(3);
        private const string ServerBase = "https://xgwnje.cn";

        public static async Task CheckUpdate(string apiKey)
        {
            try
            {
                // 版本号保护：AssemblyVersion 未设置时返回 0.0.0.x，跳过以避免误触发更新
                if (CurrentVersion.StartsWith("0.0.0"))
                {
                    LogManager.StaticWarn("[AutoUpdater] 版本号异常 (0.0.0)，跳过更新检查。请检查 AssemblyInfo.cs");
                    return;
                }

                string json;
                using (var client = new HttpClient())
                {
                    client.DefaultRequestHeaders.Add("X-API-Key", apiKey ?? "");
                    json = await client.GetStringAsync(
                        $"{ServerBase}/api/update?platform=winforms&version={CurrentVersion}");
                }

                var d = SimpleJson.ParseDict(json);

                if (!d.TryGetValue("ok", out var v1) || !(v1 is bool b1) || !b1) return;
                if (!d.TryGetValue("hasUpdate", out var v2) || !(v2 is bool b2) || !b2) return;

                string latest = SimpleJson.GetString(d, "latestVersion");
                string relUrl = SimpleJson.GetString(d, "downloadUrl");
                if (string.IsNullOrEmpty(relUrl)) return;

                var msg = $"发现新版本 {latest}（当前 {CurrentVersion}）。\n\n" +
                          $"为保持兼容性，必须更新后才能继续使用。\n" +
                          $"点击「确定」立即下载并安装。";

                // MessageBox 必须在 UI 线程（从 Task.Run 调用，需 Invoke）
                DialogResult result = DialogResult.None;
                var owner = Application.OpenForms.Count > 0 ? Application.OpenForms[0] : null;
                if (owner != null && owner.InvokeRequired)
                    owner.Invoke((Action)(() => result = MessageBox.Show(owner, msg,
                        "VisionGuard 强制更新", MessageBoxButtons.OK, MessageBoxIcon.Warning)));
                else
                    result = MessageBox.Show(msg, "VisionGuard 强制更新",
                        MessageBoxButtons.OK, MessageBoxIcon.Warning);

                if (result != DialogResult.OK) return;

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
                LogManager.StaticInfo("[AutoUpdater] 正在下载更新包… 文件较大（约250MB），请耐心等待");
                var zipPath = Path.Combine(tempDir, "update.zip");
                var sw = Stopwatch.StartNew();
                using (var client = new HttpClient())
                {
                    var bytes = await client.GetByteArrayAsync(url);
                    File.WriteAllBytes(zipPath, bytes);
                }
                sw.Stop();
                LogManager.StaticInfo($"[AutoUpdater] 下载完成 ({new FileInfo(zipPath).Length / 1048576.0:F1} MB, 耗时 {sw.Elapsed.TotalSeconds:F1}s)");

                // 解压
                var extractDir = Path.Combine(tempDir, "extracted");
                Directory.CreateDirectory(extractDir);
                System.IO.Compression.ZipFile.ExtractToDirectory(zipPath, extractDir);
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

                // UTF-8 无 BOM
                File.WriteAllText(psPath, psScript, System.Text.Encoding.UTF8);

                var pid = Process.GetCurrentProcess().Id;
                LogManager.StaticInfo($"[AutoUpdater] 启动 updater，主进程 PID={pid} 即将退出");

                var startInfo = new ProcessStartInfo
                {
                    FileName = "powershell.exe",
                    Arguments = $"-NoProfile -ExecutionPolicy Bypass -File \"{psPath}\"",
                    UseShellExecute = true,
                };
                Process.Start(startInfo);

                // 优雅退出：先发 Close 让窗体释放资源，延迟 500ms 再强杀
                _ = Task.Delay(500).ContinueWith(_ => Environment.Exit(0));
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
