namespace VisionGuard.Utils
{
    /// <summary>
    /// 更新查询用的平台标识。
    ///
    /// Windows 检测端是同一份源码的两个推理档位：modern（Windows 10+，原生 ONNX Runtime 1.19 + DirectML）
    /// 与 legacy（Windows 7 SP1，原生 ONNX Runtime 1.1.0，纯 CPU）。两档的原生库与模型互不兼容，
    /// 更新包必须按档位分发，否则 Win7 会下载到无法加载的 modern 包。
    /// 因此请求里除 platform=wpf 外还带上 profile，由 Server 解析为 wpf-<档位> 发布条目。
    /// </summary>
    internal static class UpdatePlatform
    {
#if ORT_LEGACY
        public const string Profile = "legacy";
#else
        public const string Profile = "modern";
#endif
    }
}
