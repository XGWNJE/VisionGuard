using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Threading;

namespace VisionGuard.Detector.Windows.Utils
{
    internal sealed class SharedSettingsFile
    {
        private const string MutexName = "Local\\VisionGuard.SettingsFile";
        private readonly string _path;
        private readonly Dictionary<string, string> _data = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        private readonly HashSet<string> _dirtyKeys = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        public SharedSettingsFile(string path)
        {
            if (string.IsNullOrWhiteSpace(path)) throw new ArgumentException("settings path is required", "path");
            _path = path;
        }

        public void Load()
        {
            _data.Clear();
            MergeFileInto(_data, _path);
            _dirtyKeys.Clear();
        }

        public string GetString(string key, string defaultValue)
        {
            string value;
            return _data.TryGetValue(key, out value) ? value : defaultValue;
        }

        public int GetInt(string key, int defaultValue)
        {
            int value;
            return int.TryParse(GetString(key, null), out value) ? value : defaultValue;
        }

        public bool GetBool(string key, bool defaultValue)
        {
            bool value;
            return bool.TryParse(GetString(key, null), out value) ? value : defaultValue;
        }

        public void Set(string key, string value)
        {
            string normalized = value ?? string.Empty;
            string previous;
            if (_data.TryGetValue(key, out previous) && string.Equals(previous, normalized, StringComparison.Ordinal)) return;
            _data[key] = normalized;
            _dirtyKeys.Add(key);
        }

        public void Save()
        {
            if (_dirtyKeys.Count == 0) return;
            string directory = Path.GetDirectoryName(_path);
            Directory.CreateDirectory(directory);

            using (var mutex = new Mutex(false, MutexName))
            {
                bool acquired = false;
                try
                {
                    try { acquired = mutex.WaitOne(TimeSpan.FromSeconds(5)); }
                    catch (AbandonedMutexException) { acquired = true; }
                    if (!acquired) throw new IOException("等待 settings.ini 写入锁超时");

                    var merged = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                    MergeFileInto(merged, _path);
                    foreach (string key in _dirtyKeys) merged[key] = _data[key];

                    string temp = _path + ".tmp-" + Guid.NewGuid().ToString("N");
                    var lines = new List<string> { "# VisionGuard 用户设置（自动生成，可手动编辑）" };
                    foreach (var item in merged) lines.Add(item.Key + "=" + item.Value);
                    File.WriteAllLines(temp, lines.ToArray(), new UTF8Encoding(false));
                    if (File.Exists(_path))
                    {
                        for (int attempt = 0; ; attempt++)
                        {
                            try { File.Replace(temp, _path, null); break; }
                            catch (IOException error)
                            {
                                int code = error.HResult & 0xffff;
                                if (attempt >= 3 || (code != 32 && code != 33 && code != 1175) ||
                                    !File.Exists(temp) || !File.Exists(_path)) throw;
                                Thread.Sleep(100);
                            }
                        }
                    }
                    else File.Move(temp, _path);

                    _data.Clear();
                    foreach (var item in merged) _data[item.Key] = item.Value;
                    _dirtyKeys.Clear();
                }
                finally
                {
                    if (acquired) mutex.ReleaseMutex();
                }
            }
        }

        private static void MergeFileInto(Dictionary<string, string> target, string path)
        {
            if (!File.Exists(path)) return;
            foreach (string line in File.ReadAllLines(path))
            {
                if (string.IsNullOrWhiteSpace(line) || line.StartsWith("#")) continue;
                int separator = line.IndexOf('=');
                if (separator <= 0) continue;
                target[line.Substring(0, separator).Trim()] = line.Substring(separator + 1).Trim();
            }
        }
    }
}
