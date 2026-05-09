namespace VisionGuard.Utils
{
    /// <summary>应用级常量配置。</summary>
    internal static class AppConfig
    {
        public const string ServerUrl = "http://216.36.111.208:3000";
        public const string ApiKey    = "XG-VisionGuard-2024";

        /// <summary>运行时设备唯一 ID（UUID，首次生成后持久化到 settings.ini）。</summary>
        public static string DeviceId
        {
            get
            {
                string id = SettingsStore.GetString("DeviceId", string.Empty);
                if (string.IsNullOrEmpty(id))
                {
                    id = System.Guid.NewGuid().ToString();
                    SettingsStore.Set("DeviceId", id);
                    SettingsStore.Save();
                }
                return id;
            }
        }
    }
}
