using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net.Http;
using System.Security.Cryptography;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using VisionGuard.Detector.Windows.Utils;

namespace VisionGuard.Detector.Windows.Launcher
{
    internal static class Program
    {
        private const string Version = "0.6.0";
        private static string ServerBase { get { return AccountSession.ServiceUrl; } }
        private static string DetectorShutdownEvent { get { return @"Local\VisionGuard." + AccountSession.ApplicationId + ".Shutdown"; } }
        private static string ResidentShutdownEvent { get { return AccountSession.ResidentShutdownName; } }
        private static readonly string InstallRoot = AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\');

        [STAThread]
        private static int Main(string[] args)
        {
            System.Net.ServicePointManager.SecurityProtocol |= System.Net.SecurityProtocolType.Tls12;
            try
            {
                string testEnvironment = GetOption(args, "--isolated-environment");
                if (!string.IsNullOrEmpty(testEnvironment)) AccountSession.ConfigureIsolatedEnvironment(testEnvironment);
                args = RemoveOption(args, "--isolated-environment");
                if (args.Length > 0 && args[0] == "--apply-update") return ApplyUpdate(args);
                if (args.Length > 0 && args[0] == "--check-update") return CheckUpdate(args);
                if (args.Length > 1 && args[0] == "--profile-probe") return WriteProfileProbe(args[1]);
                if (args.Length > 1 && args[0] == "--validate-package")
                {
                    ValidatePackage(Path.GetFullPath(args[1]));
                    return 0;
                }

                string marker = GetOption(args, "--post-update-marker");
                return LaunchRuntime(RemoveOption(args, "--post-update-marker"), marker);
            }
            catch (Exception ex)
            {
                WriteLog("fatal", ex.ToString());
                ThemedDialog.Show("视觉节点启动失败：\n" + ex.Message, "视觉节点", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }
        }

        private static int LaunchRuntime(string[] forwardedArgs, string postUpdateMarker)
        {
            string profile = SelectProfile();
            string runtimeExe = Path.Combine(InstallRoot, "runtimes", profile, "VisionGuard.Detector.Windows.exe");
            if (!File.Exists(runtimeExe))
                throw new FileNotFoundException("统一安装包缺少 " + profile + " 运行时，请重新完整解压安装包。", runtimeExe);

            var process = Process.Start(new ProcessStartInfo
            {
                FileName = runtimeExe,
                Arguments = string.Join(" ", forwardedArgs.Select(Quote)),
                WorkingDirectory = Path.GetDirectoryName(runtimeExe),
                UseShellExecute = true,
            });
            if (process == null) throw new InvalidOperationException("检测端进程未启动。");

            WriteLog("launch", "profile=" + profile + " pid=" + process.Id + " os=" + Environment.OSVersion.Version);
            if (!string.IsNullOrEmpty(postUpdateMarker))
            {
                if (process.WaitForExit(3000))
                    throw new InvalidOperationException("新版本检测端启动后立即退出，退出码 " + process.ExitCode + "。更新将回滚。");
                Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(postUpdateMarker)));
                File.WriteAllText(postUpdateMarker, DateTime.UtcNow.ToString("O"));
            }
            return 0;
        }

        private static string SelectProfile()
        {
            string forced = Environment.GetEnvironmentVariable("VISIONGUARD_RUNTIME_PROFILE") ?? "";
            if (string.Equals(forced, "legacy", StringComparison.OrdinalIgnoreCase)) return "legacy";
            if (string.Equals(forced, "modern", StringComparison.OrdinalIgnoreCase)) return "modern";
            return Environment.OSVersion.Version.Major < 10 ? "legacy" : "modern";
        }

        private static int CheckUpdate(string[] args)
        {
            bool interactive = args.Any(x => x == "--interactive");
            try { return CheckUpdateCore(args, interactive); }
            catch (Exception ex)
            {
                WriteLog("update-check-failed", ex.ToString());
                if (interactive) ThemedDialog.Show("检查更新失败：\n" + ex.Message, "视觉节点更新", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return 1;
            }
        }

        private static int CheckUpdateCore(string[] args, bool interactive)
        {
            int ownerPid;
            int.TryParse(GetOption(args, "--owner-pid"), out ownerPid);
            using (var mutex = new Mutex(false, @"Local\VisionGuard.Launcher.UpdateCheck", out bool created))
            {
                if (!created) return 0;
                UpdateInfo info = QueryUpdate();

                if (!info.HasUpdate)
                {
                    if (interactive) ThemedDialog.Show("当前已是最新版本。", "视觉节点更新", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    return 0;
                }
                if (string.IsNullOrWhiteSpace(info.LatestVersion) || string.IsNullOrWhiteSpace(info.DownloadUrl))
                    throw new InvalidDataException("服务器返回的更新版本或下载地址为空。旧版本未改动。");
                if (string.IsNullOrWhiteSpace(info.Sha256) || info.Sha256.Length != 64)
                    throw new InvalidDataException("服务器没有提供更新包 SHA256，已拒绝不完整的更新元数据。");

                var answer = ThemedDialog.Show(
                    "发现新版本 " + info.LatestVersion + "（当前 " + Version + "）。\n\n" +
                    "更新器会校验完整包、关闭检测端和驻留程序，并在失败时恢复旧版本。现在更新吗？",
                    "视觉节点更新", MessageBoxButtons.YesNo, MessageBoxIcon.Information);
                if (answer != DialogResult.Yes) return 0;

                string updateRoot = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "VisionGuard", "updates", info.LatestVersion + "-" + Guid.NewGuid().ToString("N"));
                Directory.CreateDirectory(updateRoot);
                string zipPath = Path.Combine(updateRoot, "package.zip");
                string stagePath = Path.Combine(updateRoot, "staged");
                DownloadAndVerify(info, zipPath);
                SafeExtract(zipPath, stagePath);
                ValidatePackage(stagePath);

                string updaterExe = Path.Combine(updateRoot, "VisionGuard.Updater.exe");
                File.Copy(Path.Combine(InstallRoot, "VisionGuard.Detector.Windows.exe"), updaterExe, true);
                string config = Path.Combine(InstallRoot, "VisionGuard.Detector.Windows.exe.config");
                if (File.Exists(config)) File.Copy(config, updaterExe + ".config", true);

                SignalShutdown(DetectorShutdownEvent);
                SignalShutdown(ResidentShutdownEvent);

                var updater = Process.Start(new ProcessStartInfo
                {
                    FileName = updaterExe,
                    Arguments = string.Join(" ", new[]
                    {
                        "--apply-update", stagePath, InstallRoot, info.LatestVersion,
                        Process.GetCurrentProcess().Id.ToString(), ownerPid.ToString()
                    }.Select(Quote)),
                    WorkingDirectory = updateRoot,
                    UseShellExecute = true,
                });
                if (updater == null) throw new InvalidOperationException("无法启动更新器。");
                WriteLog("update-staged", "version=" + info.LatestVersion + " updaterPid=" + updater.Id);
                return 0;
            }
        }

        private static UpdateInfo QueryUpdate()
        {
            using (var client = new HttpClient())
            {
                client.Timeout = TimeSpan.FromSeconds(20);
                var session = AccountSession.EnsureFresh();
                if (session != null) client.DefaultRequestHeaders.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", session.token);
                string json = client.GetStringAsync(
                    ServerBase + "/api/update?platform=wpf&version=" + Uri.EscapeDataString(Version)).GetAwaiter().GetResult();
                var data = new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(json);
                return new UpdateInfo
                {
                    HasUpdate = ReadBool(data, "hasUpdate"),
                    LatestVersion = ReadString(data, "latestVersion"),
                    DownloadUrl = ReadString(data, "downloadUrl"),
                    Size = ReadLong(data, "size"),
                    Sha256 = ReadString(data, "sha256").ToUpperInvariant(),
                };
            }
        }

        private static void DownloadAndVerify(UpdateInfo info, string destination)
        {
            string url = info.DownloadUrl.StartsWith("http", StringComparison.OrdinalIgnoreCase)
                ? info.DownloadUrl : ServerBase + info.DownloadUrl;
            using (var client = new HttpClient())
            using (var response = client.GetAsync(url, HttpCompletionOption.ResponseHeadersRead).GetAwaiter().GetResult())
            {
                response.EnsureSuccessStatusCode();
                using (var input = response.Content.ReadAsStreamAsync().GetAwaiter().GetResult())
                using (var output = new FileStream(destination, FileMode.CreateNew, FileAccess.Write, FileShare.None))
                    input.CopyTo(output);
            }
            var file = new FileInfo(destination);
            if (info.Size > 0 && file.Length != info.Size)
                throw new InvalidDataException("更新包大小不匹配：" + file.Length + " / " + info.Size + "。旧版本未改动。");
            string actual;
            using (var stream = File.OpenRead(destination))
            using (var sha = SHA256.Create()) actual = BitConverter.ToString(sha.ComputeHash(stream)).Replace("-", "");
            if (!string.Equals(actual, info.Sha256, StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("更新包 SHA256 校验失败。旧版本未改动。");
        }

        private static void SafeExtract(string zipPath, string destination)
        {
            Directory.CreateDirectory(destination);
            string root = Path.GetFullPath(destination).TrimEnd('\\') + "\\";
            using (var archive = ZipFile.OpenRead(zipPath))
            {
                foreach (var entry in archive.Entries)
                {
                    string output = Path.GetFullPath(Path.Combine(destination, entry.FullName.Replace('/', '\\')));
                    if (!output.StartsWith(root, StringComparison.OrdinalIgnoreCase))
                        throw new InvalidDataException("更新包包含越界路径：" + entry.FullName);
                    if (string.IsNullOrEmpty(entry.Name)) { Directory.CreateDirectory(output); continue; }
                    Directory.CreateDirectory(Path.GetDirectoryName(output));
                    entry.ExtractToFile(output, false);
                }
            }
        }

        private static void ValidatePackage(string root)
        {
            string[] required =
            {
                "VisionGuard.Detector.Windows.exe", "VisionGuard.Resident.Windows.exe", "VisionGuard.Resident.Windows.exe.config",
                @"runtimes\modern\VisionGuard.Detector.Windows.exe", @"runtimes\modern\native\modern\onnxruntime.dll",
                @"runtimes\legacy\VisionGuard.Detector.Windows.exe", @"runtimes\legacy\native\legacy\onnxruntime.dll",
            };
            foreach (string relative in required)
                if (!File.Exists(Path.Combine(root, relative)))
                    throw new InvalidDataException("更新包缺少必要文件：" + relative);
        }

        private static int ApplyUpdate(string[] args)
        {
            if (args.Length != 6) throw new ArgumentException("更新参数不完整。");
            string staged = Path.GetFullPath(args[1]);
            string target = Path.GetFullPath(args[2]).TrimEnd('\\');
            string version = args[3];
            int launcherPid = int.Parse(args[4]);
            int ownerPid = int.Parse(args[5]);
            return ApplyUpdateCore(staged, target, version, launcherPid, ownerPid);
        }

        private static int ApplyUpdateCore(string staged, string target, string version, int launcherPid, int ownerPid)
        {
            WaitForExit(launcherPid, 20000);
            WaitForExit(ownerPid, 20000);
            SignalShutdown(DetectorShutdownEvent);
            SignalShutdown(ResidentShutdownEvent);
            Thread.Sleep(1000);

            string parent = Path.GetDirectoryName(target);
            if (string.IsNullOrEmpty(parent)) throw new InvalidOperationException("安装目录没有可用的父目录，无法安全更新。");
            string name = Path.GetFileName(target);
            string incoming = Path.Combine(parent, name + ".incoming-" + Guid.NewGuid().ToString("N"));
            string backup = Path.Combine(parent, name + ".backup-" + DateTime.Now.ToString("yyyyMMddHHmmss"));
            CopyDirectory(staged, incoming);
            ValidatePackage(incoming);

            bool oldMoved = false;
            try
            {
                MoveDirectoryWithRetry(target, backup);
                oldMoved = true;
                MoveDirectoryWithRetry(incoming, target);

                string marker = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "VisionGuard", "updates", "started-" + version + "-" + Guid.NewGuid().ToString("N") + ".ok");
                var launched = Process.Start(new ProcessStartInfo
                {
                    FileName = Path.Combine(target, "VisionGuard.Detector.Windows.exe"),
                    Arguments = "--post-update-marker " + Quote(marker),
                    WorkingDirectory = target,
                    UseShellExecute = true,
                });
                if (launched == null || !WaitForFile(marker, 20000))
                    throw new InvalidOperationException("新版本未能完成启动确认。");

                TryDeleteDirectory(backup);
                WriteLog("update-complete", "version=" + version + " target=" + target);
                return 0;
            }
            catch (Exception ex)
            {
                WriteLog("update-rollback", ex.ToString());
                SignalShutdown(DetectorShutdownEvent);
                SignalShutdown(ResidentShutdownEvent);
                Thread.Sleep(1500);
                try
                {
                    if (oldMoved)
                    {
                        string failed = target + ".failed-" + Guid.NewGuid().ToString("N");
                        if (Directory.Exists(target)) MoveDirectoryWithRetry(target, failed);
                        MoveDirectoryWithRetry(backup, target);
                        TryDeleteDirectory(failed);
                        Process.Start(new ProcessStartInfo
                        {
                            FileName = Path.Combine(target, "VisionGuard.Detector.Windows.exe"), WorkingDirectory = target, UseShellExecute = true
                        });
                    }
                }
                catch (Exception rollbackError)
                {
                    throw new AggregateException("更新失败且自动回滚失败。旧目录：" + backup, ex, rollbackError);
                }
                ThemedDialog.Show("更新失败，已经恢复旧版本：\n" + ex.Message, "视觉节点更新", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return 1;
            }
            finally
            {
                TryDeleteDirectory(incoming);
            }
        }

        private static void MoveDirectoryWithRetry(string source, string destination)
        {
            Exception last = null;
            for (int i = 0; i < 10; i++)
            {
                try { Directory.Move(source, destination); return; }
                catch (Exception ex) { last = ex; Thread.Sleep(500); }
            }
            throw new IOException("无法切换程序目录：" + source + " → " + destination, last);
        }

        private static void CopyDirectory(string source, string destination)
        {
            foreach (string dir in Directory.GetDirectories(source, "*", SearchOption.AllDirectories))
                Directory.CreateDirectory(destination + dir.Substring(source.Length));
            Directory.CreateDirectory(destination);
            foreach (string file in Directory.GetFiles(source, "*", SearchOption.AllDirectories))
            {
                string target = destination + file.Substring(source.Length);
                Directory.CreateDirectory(Path.GetDirectoryName(target));
                File.Copy(file, target, false);
            }
        }

        private static void SignalShutdown(string name)
        {
            try { using (var handle = EventWaitHandle.OpenExisting(name)) handle.Set(); }
            catch (WaitHandleCannotBeOpenedException) { }
        }

        private static void WaitForExit(int pid, int timeoutMs)
        {
            if (pid <= 0 || pid == Process.GetCurrentProcess().Id) return;
            try { using (var process = Process.GetProcessById(pid)) process.WaitForExit(timeoutMs); }
            catch (ArgumentException) { }
        }

        private static bool WaitForFile(string path, int timeoutMs)
        {
            var deadline = DateTime.UtcNow.AddMilliseconds(timeoutMs);
            while (DateTime.UtcNow < deadline) { if (File.Exists(path)) return true; Thread.Sleep(200); }
            return File.Exists(path);
        }

        private static void TryDeleteDirectory(string path)
        {
            try { if (Directory.Exists(path)) Directory.Delete(path, true); } catch { }
        }

        private static string GetOption(string[] args, string name)
        {
            int index = Array.IndexOf(args, name);
            return index >= 0 && index + 1 < args.Length ? args[index + 1] : "";
        }

        private static string[] RemoveOption(string[] args, string name)
        {
            var result = new List<string>();
            for (int i = 0; i < args.Length; i++)
            {
                if (args[i] == name)
                {
                    if (i + 1 < args.Length) i++;
                    continue;
                }
                result.Add(args[i]);
            }
            return result.ToArray();
        }

        private static int WriteProfileProbe(string path)
        {
            string profile = SelectProfile();
            string runtimePath = Path.Combine(InstallRoot, "runtimes", profile, "VisionGuard.Detector.Windows.exe");
            var data = new Dictionary<string, object>
            {
                ["profile"] = profile,
                ["runtimePath"] = runtimePath,
                ["exists"] = File.Exists(runtimePath),
                ["osVersion"] = Environment.OSVersion.Version.ToString(),
            };
            string fullPath = Path.GetFullPath(path);
            string directory = Path.GetDirectoryName(fullPath);
            if (!string.IsNullOrEmpty(directory)) Directory.CreateDirectory(directory);
            File.WriteAllText(fullPath, new JavaScriptSerializer().Serialize(data));
            return File.Exists(runtimePath) ? 0 : 2;
        }

        private static string Quote(string value) => "\"" + (value ?? "").Replace("\"", "\\\"") + "\"";
        private static bool ReadBool(Dictionary<string, object> data, string key) => data.ContainsKey(key) && Convert.ToBoolean(data[key]);
        private static string ReadString(Dictionary<string, object> data, string key) => data.ContainsKey(key) ? Convert.ToString(data[key]) ?? "" : "";
        private static long ReadLong(Dictionary<string, object> data, string key) => data.ContainsKey(key) ? Convert.ToInt64(data[key]) : 0;

        private static void WriteLog(string action, string detail)
        {
            try
            {
                string dir = AccountSession.LogRoot;
                Directory.CreateDirectory(dir);
                File.AppendAllText(Path.Combine(dir, "launcher.log"),
                    DateTime.Now.ToString("O") + " " + action + " " + detail + Environment.NewLine);
            }
            catch { }
        }

        private sealed class UpdateInfo
        {
            public bool HasUpdate;
            public string LatestVersion = "";
            public string DownloadUrl = "";
            public long Size;
            public string Sha256 = "";
        }
    }
}
