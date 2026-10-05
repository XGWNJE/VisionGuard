namespace VisionGuard.Detector.Windows.Utils
{
    /// <summary>应用级常量配置。</summary>
    internal static class AppConfig
    {
        public const string Version = "0.6.1";
        public static string ServerUrl => AccountSession.ServiceUrl;
        public static string Channel => AccountSession.Current?.channel ?? "signed-out";
        public static string SessionToken => AccountSession.Current?.token ?? "";

        /// <summary>设备身份只由账号服务登记；未登录时没有服务身份。</summary>
        public static string DeviceId => AccountSession.Current?.device.deviceId ?? "";
    }
}
