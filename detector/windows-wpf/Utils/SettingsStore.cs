using System;
using System.IO;

namespace VisionGuard.Detector.Windows.Utils
{
    /// <summary>
    /// 唯一设置入口。读取前必须调用一次 <see cref="Load"/>：
    /// 生产路径由 Program 在拉起驻留之前调用；非界面宿主（验证探针）也必须先加载，
    /// 否则会读到空存储、把磁盘上已有的值当成缺失。
    /// </summary>
    internal static class SettingsStore
    {
        private static SharedSettingsFile Store = new SharedSettingsFile(ResolveSettingsPath());
        public static void SwitchAccount() { Store.Save(); Store = new SharedSettingsFile(ResolveSettingsPath()); Store.Load(); }

        /// <summary>
        /// 允许用 VISIONGUARD_SETTINGS_PATH 指向隔离配置，便于自动化验证互不干扰。
        /// </summary>
        private static string ResolveSettingsPath()
        {
            string overridePath = Environment.GetEnvironmentVariable("VISIONGUARD_SETTINGS_PATH");
            if (!string.IsNullOrWhiteSpace(overridePath))
            {
                var full = Path.GetFullPath(overridePath.Trim());
                return AccountSession.Current == null ? full : Path.Combine(Path.GetDirectoryName(full), "accounts", AccountSession.ScopeKey, Path.GetFileName(full));
            }
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "VisionGuard", "accounts", AccountSession.ScopeKey, "settings.ini");
        }

        public static void Load() { Store.Load(); }
        public static void Save() { Store.Save(); }
        /// <summary>供共享的键名迁移助手使用；普通读写请走下面的方法。</summary>
        public static SharedSettingsFile Raw { get { return Store; } }
        public static int GetInt(string key, int defaultValue) { return Store.GetInt(key, defaultValue); }
        public static bool GetBool(string key, bool defaultValue) { return Store.GetBool(key, defaultValue); }
        public static string GetString(string key, string defaultValue) { return Store.GetString(key, defaultValue); }
        public static void Set(string key, int value) { Store.Set(key, value.ToString()); }
        public static void Set(string key, bool value) { Store.Set(key, value.ToString()); }
        public static void Set(string key, string value) { Store.Set(key, value); }
    }
}
