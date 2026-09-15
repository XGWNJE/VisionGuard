using System;
using System.Collections.Generic;
using System.IO;

namespace VisionGuard.Utils
{
    internal static class SettingsStore
    {
        private static readonly SharedSettingsFile Store = new SharedSettingsFile(ResolveSettingsPath());

        private static string ResolveSettingsPath()
        {
            string overridePath = Environment.GetEnvironmentVariable("VISIONGUARD_SETTINGS_PATH");
            if (!string.IsNullOrWhiteSpace(overridePath)) return Path.GetFullPath(overridePath.Trim());
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "VisionGuard", "settings.ini");
        }

        public static void Load() { Store.Load(); }
        public static void Save() { Store.Save(); }
        /// <summary>供共享的键名迁移助手使用；普通读写请走下面的方法。</summary>
        public static SharedSettingsFile Raw { get { return Store; } }
        public static int GetInt(string key, int defaultValue) { return Store.GetInt(key, defaultValue); }
        public static bool GetBool(string key, bool defaultValue) { return Store.GetBool(key, defaultValue); }
        public static string GetString(string key, string defaultValue) { return Store.GetString(key, defaultValue); }
        public static HashSet<string> GetStringList(string key) { return Store.GetStringList(key); }
        public static void Set(string key, int value) { Store.Set(key, value.ToString()); }
        public static void Set(string key, bool value) { Store.Set(key, value.ToString()); }
        public static void Set(string key, string value) { Store.Set(key, value); }
    }
}
