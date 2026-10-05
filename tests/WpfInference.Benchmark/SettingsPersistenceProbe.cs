using System;
using System.IO;
using System.Reflection;
using System.Threading;
using VisionGuard.Detector.Windows.ViewModels;

internal static class SettingsPersistenceProbe
{
    internal static int Run()
    {
        string directory = Path.Combine(Path.GetTempPath(), "vg-settings-probe-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        string path = Path.Combine(directory, "settings.ini");
        try
        {
            var type = typeof(MultiSourceViewModel).Assembly.GetType("VisionGuard.Detector.Windows.Utils.SharedSettingsFile", true)!;
            object store = Activator.CreateInstance(type, path)!;
            void Call(string method, params object[] values) => type.GetMethod(method)!.Invoke(store, values);
            Call("Set", "theme", "dark"); Call("Save"); Call("Load");
            using (var held = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read))
            {
                Call("Set", "theme", "dark"); Call("Save");
            }
            Console.WriteLine("PASS unchanged settings do not replace an existing locked file");

            Call("Set", "theme", "light");
            var transient = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
            var release = new Thread(() => { Thread.Sleep(150); transient.Dispose(); });
            release.Start();
            try { Call("Save"); } finally { release.Join(); }
            if (!File.ReadAllText(path).Contains("theme=light")) throw new Exception("Transient-lock save lost its value");
            Console.WriteLine("PASS transient replacement lock releases and the new value is persisted");

            Call("Set", "theme", "system");
            bool rejected = false;
            using (var held = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read))
            {
                try { Call("Save"); }
                catch (TargetInvocationException error) when (error.InnerException is IOException) { rejected = true; }
                if (!rejected || !File.ReadAllText(path).Contains("theme=light")) throw new Exception("Permanent lock did not preserve existing settings");
            }
            Call("Save");
            if (!File.ReadAllText(path).Contains("theme=system")) throw new Exception("Failed save cleared the pending value");
            Console.WriteLine("PASS permanent failure preserves the old file and pending value for a later save");
            return 0;
        }
        catch (Exception error) { Console.Error.WriteLine(error); return 1; }
        finally
        {
            foreach (string file in Directory.GetFiles(directory)) File.Delete(file);
            Directory.Delete(directory);
        }
    }
}
