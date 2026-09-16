using System;

namespace VisionGuard
{
    /// <summary>
    /// 显式入口点。
    /// WPF 自动生成的入口无法在最早时机设置进程级网络参数，而 Windows 7 上必须在使用
    /// 任何 HTTPS/WSS 之前开启 TLS 1.2：.NET Framework 默认 SystemDefault，在未启用
    /// SCHANNEL 客户端 TLS 1.2 时退化为 TLS 1.0，服务端只接受 1.2 及以上。
    /// </summary>
    internal static class Program
    {
        [STAThread]
        public static int Main(string[] args)
        {
            // Win7：.NET Framework 默认 SystemDefault，在未启用 SCHANNEL 客户端 TLS 1.2 时
            // 退化为 TLS 1.0，服务端只接受 1.2 及以上。必须在任何网络调用前设置。
            System.Net.ServicePointManager.SecurityProtocol |= System.Net.SecurityProtocolType.Tls12;

            // 按运行环境选择并预加载 ONNX Runtime 原生库；必须在任何推理调用之前。
            Runtime.NativeLibrarySelector.Initialize();

            // 只在主实例启动时拉起驻留：第二实例会因下方单实例守卫自行退出，不应重复拉起。
            // 驻留负责远程重新打开检测端，缺失或拉起失败都不影响检测本身。
            if (IsPrimaryInstance())
            {
                Runtime.ResidentLauncher.EnsureStarted();
            }

            var app = new App();
            app.InitializeComponent();
            return app.Run();
        }

        /// <summary>探测本进程是否为主实例（与 App 内的单实例守卫共用同一互斥体名）。</summary>
        private static bool IsPrimaryInstance()
        {
            try
            {
                bool createdNew;
                // initiallyOwned: false —— 只探测是否已存在，不抢占所有权，避免干扰 App 内的单实例守卫。
                using (var mutex = new System.Threading.Mutex(false, @"Local\VisionGuard." + Runtime.ResidentLauncher.ApplicationId, out createdNew))
                {
                    return createdNew;
                }
            }
            catch (Exception)
            {
                // 探测失败时按主实例处理，由 App 内的守卫做最终判定。
                return true;
            }
        }
    }
}
