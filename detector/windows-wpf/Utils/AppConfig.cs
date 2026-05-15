namespace VisionGuard.Utils
{
    /// <summary>应用级常量配置。</summary>
    internal static class AppConfig
    {
        public const string Version = "4.0.1";
        public const string ServerUrl = "https://xgwnje.cn";
        public static readonly string ApiKey =
            System.Environment.GetEnvironmentVariable("VISIONGUARD_API_KEY") ?? "XG-VisionGuard-2024";

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
