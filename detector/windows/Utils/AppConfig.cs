namespace VisionGuard.Utils
{
    /// <summary>应用级常量配置。</summary>
    internal static class AppConfig
    {
        public const string ServerUrl = "http://216.36.111.208:3000";
        public const string ApiKey    = "XG-VisionGuard-2024";

        /// <summary>运行时设备唯一 ID，基于机器名。</summary>
        public static string DeviceId => "windows-" + System.Environment.MachineName;
    }
}
