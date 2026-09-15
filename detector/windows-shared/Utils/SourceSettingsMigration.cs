using System;

namespace VisionGuard.Utils
{
    /// <summary>
    /// 一次性的设置键前缀迁移：把早期使用的“信号”前缀迁到统一的“来源”前缀。
    /// 旧的键保留下来作为备份，只补写缺失的新键，因此可以重复执行。
    /// </summary>
    internal static class SourceSettingsMigration
    {
        /// <summary>
        /// 逐槽位迁移前缀；返回迁移后存在的最大来源序号（没有任何数据时返回 0）。
        /// </summary>
        public static int Migrate(SharedSettingsFile store, string oldPrefix, string newPrefix, string[] suffixes, int maxIndex)
        {
            if (store == null) throw new ArgumentNullException("store");
            if (string.IsNullOrEmpty(oldPrefix)) throw new ArgumentException("old prefix is required", "oldPrefix");
            if (string.IsNullOrEmpty(newPrefix)) throw new ArgumentException("new prefix is required", "newPrefix");
            if (suffixes == null) throw new ArgumentNullException("suffixes");

            int highest = 0;
            for (int index = 1; index <= maxIndex; index++)
            {
                bool hasSourceData = false;
                foreach (string suffix in suffixes)
                {
                    string legacy = store.GetString(oldPrefix + index + "." + suffix, null);
                    if (legacy == null) continue;
                    hasSourceData = true;
                    if (store.GetString(newPrefix + index + "." + suffix, null) == null)
                        store.Set(newPrefix + index + "." + suffix, legacy);
                }
                if (hasSourceData || store.GetString(newPrefix + index + ".Name", null) != null) highest = index;
            }
            return highest;
        }

        /// <summary>
        /// 迁移来源数量键；新键已存在时以新键为准，不覆盖用户当前的设置。
        /// </summary>
        public static int MigrateCount(SharedSettingsFile store, string oldKey, string newKey, int fallback)
        {
            if (store == null) throw new ArgumentNullException("store");
            int existing = store.GetInt(newKey, -1);
            if (existing >= 0) return existing;
            int legacy = store.GetInt(oldKey, -1);
            if (legacy < 0) return fallback;
            store.Set(newKey, legacy.ToString(System.Globalization.CultureInfo.InvariantCulture));
            return legacy;
        }
    }
}
