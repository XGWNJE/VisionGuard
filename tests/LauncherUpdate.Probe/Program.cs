using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using VisionGuard.Detector.Windows.Launcher;

internal class ProbeProgram {
    static void Require(bool result, string message) { if (!result) throw new Exception(message); }
    static Dictionary<string, object> Release(string version, int day, string prefix = "WPF", string digest = null) {
        string name = "VisionGuard-" + prefix + "-v" + version + ".zip";
        return new Dictionary<string, object> { ["tag_name"] = "v" + version, ["published_at"] = "2026-10-" + day.ToString("00") + "T00:00:00Z", ["draft"] = false, ["prerelease"] = false,
            ["assets"] = new object[] { new Dictionary<string, object> { ["name"] = name, ["browser_download_url"] = "https://github.com/XGWNJE/VisionGuard/releases/download/v" + version + "/" + name, ["state"] = "uploaded", ["size"] = 123L, ["digest"] = digest ?? "sha256:" + new string('a', 64) } } };
    }
    static int Main(string[] args) {
        string directory = Path.Combine(Path.GetTempPath(), "vg-update-probe-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(directory);
        try {
            Require(GitHubReleasePolicy.Select(new[] { Release("4.5.1", 1), Release("0.6.0", 3), Release("0.7.0", 4), Release("0.8.0", 5, "Detector") }, "0.6.0").Version == "0.7.0", "Partial release or historical high version selected incorrectly");
            Require(GitHubReleasePolicy.Select(new[] { Release("4.5.1", 1) }, "0.6.0") == null, "Unanchored development version selected legacy major");
            Require(GitHubReleasePolicy.Select(new[] { Release("0.6.0", 3), Release("1.0.0", 4) }, "0.6.0").Version == "1.0.0", "New published major rejected");
            bool damaged = false; try { GitHubReleasePolicy.Select(new[] { Release("0.6.0", 3), Release("0.7.0", 4), Release("0.8.0", 5, digest: "") }, "0.6.0"); } catch (InvalidDataException) { damaged = true; } Require(damaged, "Damaged latest file silently fell back");
            var draft = Release("0.7.0", 4); draft["draft"] = true; Require(GitHubReleasePolicy.Select(new[] { draft }, "0.6.0") == null, "Draft selected");
            foreach (string bad in new[] { "1", "1.0", "01.0.0", "1.0.0-beta", "999999999999.0.0" }) Require(GitHubReleasePolicy.StableVersion(bad) == null, "Malformed version accepted");
            var assembly = Assembly.LoadFrom(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "VisionGuard.Detector.Windows.exe"));
            var program = assembly.GetType("VisionGuard.Detector.Windows.Launcher.Program");
            if (args.Contains("--online")) { var update = program.GetMethod("QueryUpdate", BindingFlags.NonPublic | BindingFlags.Static).Invoke(null, null); Console.WriteLine("GitHub stable check: hasUpdate=" + update.GetType().GetField("HasUpdate").GetValue(update)); }
            var download = program.GetMethod("DownloadAndVerify", BindingFlags.NonPublic | BindingFlags.Static);
            var infoType = program.GetNestedType("UpdateInfo", BindingFlags.NonPublic);
            byte[] payload = Encoding.UTF8.GetBytes("actual download integrity fixture");
            string hash; using (var sha = SHA256.Create()) hash = BitConverter.ToString(sha.ComputeHash(payload)).Replace("-", "");
            for (int mode = 0; mode < 4; mode++) {
                var listener = new TcpListener(IPAddress.Loopback, 0); listener.Start(); int port = ((IPEndPoint)listener.LocalEndpoint).Port;
                int ownMode = mode;
                var server = Task.Run(async () => { using (var socket = await listener.AcceptTcpClientAsync()) using (var stream = socket.GetStream()) {
                    byte[] request = new byte[4096]; await stream.ReadAsync(request, 0, request.Length);
                    byte[] headers = Encoding.ASCII.GetBytes("HTTP/1.1 200 OK\r\nContent-Length: " + payload.Length + "\r\nConnection: close\r\n\r\n"); await stream.WriteAsync(headers, 0, headers.Length);
                    if (ownMode == 3) { await stream.WriteAsync(payload, 0, 1); await Task.Delay(1000); }
                    else await stream.WriteAsync(payload, 0, payload.Length);
                } });
                var info = Activator.CreateInstance(infoType, true); infoType.GetField("DownloadUrl").SetValue(info, "http://127.0.0.1:" + port + "/package.zip"); infoType.GetField("Sha256").SetValue(info, mode == 2 ? new string('0', 64) : hash); infoType.GetField("Size").SetValue(info, (long)payload.Length + (mode == 1 ? 1 : 0));
                bool rejected = false; using (var cancel = new CancellationTokenSource()) { if (mode == 3) cancel.CancelAfter(150);
                    try { download.Invoke(null, new object[] { info, Path.Combine(directory, "download-" + mode), cancel.Token, new Action<long>(_ => {}) }); } catch (TargetInvocationException) { rejected = true; }
                }
                Require(rejected == (mode != 0), "Download integrity/cancellation case failed: " + mode); server.GetAwaiter().GetResult(); listener.Stop();
            }
            string archive = Path.Combine(directory, "escape.zip"); using (var zip = ZipFile.Open(archive, ZipArchiveMode.Create)) using (var writer = new StreamWriter(zip.CreateEntry("../escape.txt").Open())) writer.Write("escape");
            bool escaped = false; try { program.GetMethod("SafeExtract", BindingFlags.NonPublic | BindingFlags.Static).Invoke(null, new object[] { archive, Path.Combine(directory, "unzip") }); } catch (TargetInvocationException e) { escaped = e.InnerException is InvalidDataException; } Require(escaped, "ZIP traversal accepted");
            var copy = program.GetMethod("CopyDirectory", BindingFlags.NonPublic | BindingFlags.Static);
            var install = program.GetMethod("InstallDirectory", BindingFlags.NonPublic | BindingFlags.Static);
            string package = Path.GetFullPath("detector/windows-package/bin/Release");
            var validateVersion = program.GetMethod("ValidateStagedVersion", BindingFlags.NonPublic | BindingFlags.Static);
            string sourceVersion = File.ReadAllText("VERSION").Trim();
            validateVersion.Invoke(null, new object[] { package, sourceVersion });
            var currentVersion = Version.Parse(sourceVersion);
            string differentVersion = currentVersion.Major + "." + currentVersion.Minor + "." + (currentVersion.Build + 1);
            bool wrongVersion = false;
            try { validateVersion.Invoke(null, new object[] { package, differentVersion }); }
            catch (TargetInvocationException e) { wrongVersion = e.InnerException is InvalidDataException; }
            Require(wrongVersion, "Staged executable with a different numeric version accepted");
            string target = Path.Combine(directory, "installed"), stage = Path.Combine(directory, "stage");
            copy.Invoke(null,new object[]{package,target});copy.Invoke(null,new object[]{package,stage});
            File.WriteAllText(Path.Combine(target,"acceptance-version"),"old"); File.WriteAllText(Path.Combine(stage,"acceptance-version"),"new");
            bool prepared=false, rolledBack=false, restarted=false;
            try { install.Invoke(null,new object[]{stage,target,new Action(()=>prepared=true),new Action<string>(root=>{Require(File.ReadAllText(Path.Combine(root,"acceptance-version"))=="new","New directory not activated");throw new IOException("fixture startup failed");}),new Action(()=>rolledBack=true),new Action<string>(root=>{restarted=File.ReadAllText(Path.Combine(root,"acceptance-version"))=="old";})});throw new Exception("Failed startup did not throw"); }
            catch(TargetInvocationException e){Require(e.InnerException is IOException,"Unexpected rollback error");}
            Require(prepared&&rolledBack&&restarted&&File.ReadAllText(Path.Combine(target,"acceptance-version"))=="old","Rollback did not preserve old files");
            Func<bool> noPartial = () => !Directory.GetDirectories(directory).Any(path => Path.GetFileName(path).StartsWith("installed.", StringComparison.OrdinalIgnoreCase));
            Require(noPartial(),"Rollback left partial directories");
            install.Invoke(null,new object[]{stage,target,new Action(()=>{}),new Action<string>(root=>Require(File.ReadAllText(Path.Combine(root,"acceptance-version"))=="new","Success did not activate stage")),new Action(()=>throw new Exception("Unexpected rollback")),new Action<string>(_=>{})});
            Require(noPartial(),"Success left backup or partial directories");
            string broken=Path.Combine(directory,"broken");Directory.CreateDirectory(broken);prepared=false;
            try { install.Invoke(null,new object[]{broken,target,new Action(()=>prepared=true),new Action<string>(_=>{}),new Action(()=>{}),new Action<string>(_=>{})});throw new Exception("Broken package accepted"); } catch(TargetInvocationException e){Require(e.InnerException is InvalidDataException&&!prepared,"Preparation failure stopped old program");}
            Require(File.ReadAllText(Path.Combine(target,"acceptance-version"))=="new"&&noPartial(),"Preparation failure changed old installation");
            var acquire=program.GetMethod("AcquireUpdate",BindingFlags.NonPublic|BindingFlags.Static);
            using(var held=new Mutex(false,"Local\\VisionGuard.Test.Update."+Guid.NewGuid().ToString("N"))) {
                var ready=new ManualResetEventSlim(); var release=new ManualResetEventSlim();
                var owner=new Thread(()=>{held.WaitOne();ready.Set();release.Wait();held.ReleaseMutex();});owner.Start();ready.Wait();
                Require(!(bool)acquire.Invoke(null,new object[]{held,0}),"Concurrent updater acquired an owned mutex");release.Set();owner.Join();
                Require((bool)acquire.Invoke(null,new object[]{held,0}),"Update lock did not recover");held.ReleaseMutex();
            }
            Console.WriteLine("PASS actual filesystem preparation, directory switch, startup-failure rollback, cleanup and update mutex contention; process callbacks simulated.");
            Console.WriteLine("PASS: stable chronology, partial releases, version lineage, damaged metadata, real download size/hash/cancel and ZIP traversal."); return 0;
        } catch (Exception e) { Console.Error.WriteLine(e); return 1; }
        finally { Directory.Delete(directory, true); }
    }
}
