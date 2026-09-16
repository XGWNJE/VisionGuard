using System;
using System.Collections.Generic;

namespace VisionGuard.Runtime
{
    /// <summary>
    /// 检测端能力声明。
    /// `directml` 必须与运行档位一致：legacy 档（Windows 7）没有 DirectML 提供程序，
    /// 不能声明该能力，否则接收端与服务端会以为可以切换 GPU 后端。
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
                "screenshot-on-demand",
                "source-control",
            };

            if (NativeLibrarySelector.SupportsDirectMl && !NativeLibrarySelector.IsLegacy)
                capabilities.Add("directml");

            return capabilities.ToArray();
        }
    }
}
