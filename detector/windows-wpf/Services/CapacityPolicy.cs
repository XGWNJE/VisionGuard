using System;

namespace VisionGuard.Services
{
    public static class CapacityPolicy
    {
        public static string GetWarning(string backend, int runningSources, int configuredCapacity)
        {
            var capacity = Math.Max(1, configuredCapacity);
            return runningSources > capacity
                ? $"{backend} 容量提示：当前 {runningSources} 路，配置基线 {capacity} 路；允许继续运行，请关注实际帧率。"
                : "";
        }
    }
}
