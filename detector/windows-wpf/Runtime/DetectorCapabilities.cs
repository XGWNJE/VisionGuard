using System;
using System.Collections.Generic;

namespace VisionGuard.Detector.Windows.Runtime
{
    /// <summary>
    /// 检测端能力声明。
    /// `directml` 必须与运行档位一致：legacy 档（Windows 7）没有 DirectML 提供程序，
    /// 不能声明该能力，否则接收端与服务端会以为可以切换 GPU 后端。
    /// `video-publish` / `stream-control` 表示本机可以在「推流」态把采集画面推给其他推理节点；
    /// 发布与订阅在节点级互斥，由本机状态决定当前方向。
    /// </summary>
    internal static class DetectorCapabilities
    {
        public static string[] Build()
        {
            var capabilities = new List<string>
            {
                "monitor-control",
                "config-control",
                "request-correlation",
                "cache-maintenance",
                "screenshot-on-demand",
                "source-control",
                "video-subscribe",
                "video-publish",
                "stream-control",
                "visual-inference",
            };

            if (NativeLibrarySelector.SupportsDirectMl && !NativeLibrarySelector.IsLegacy)
                capabilities.Add("directml");

            return capabilities.ToArray();
        }
    }
}
