using System;

namespace VisionGuard.Detector.Windows.Services
{
    /// <summary>来源卡片的性能提示：低于目标 80% 持续 30 秒显示文字；主动分析由设置页提供。</summary>
    public static class PerformanceWatchdog
    {
        /// <summary>实际帧率低于目标帧率的这个比例即视为不达标。</summary>
        public const double TargetRatio = 0.8;

        /// <summary>不达标需要持续的秒数。</summary>
        public const double SustainedSeconds = 30;

        /// <summary>
        /// 比较实测帧率时容忍的浮点误差（FPS）。
        /// `3 × 0.8` 在 double 下是 `2.4000000000000004`，不加容差会把「正好等于阈值」误判成不达标
        /// （契约探针实测到过这一条）。0.0001 FPS 对真实判定没有任何影响。
        /// </summary>
        public const double ComparisonTolerance = 0.0001;

        /// <summary>
        /// 某一路当前是否不达标（尚未计入持续时间）。
        /// 未在运行、或还没有任何帧（fps ≤ 0）时返回 false。
        /// </summary>
        public static bool IsBelowTarget(double actualFps, double targetFps, bool isMonitoring)
        {
            if (!isMonitoring) return false;
            if (actualFps <= 0 || targetFps <= 0) return false;
            return actualFps < targetFps * TargetRatio - ComparisonTolerance;
        }

        /// <summary>
        /// 卡片上的性能提示文案。未达到持续时间阈值时返回空串：卡片只在「确认不足」之后才显示，
        /// 避免刚启动几秒就闪出一条警告。
        /// </summary>
        public static string GetWarning(double actualFps, double targetFps, double secondsBelowTarget, bool isMonitoring)
        {
            if (!IsBelowTarget(actualFps, targetFps, isMonitoring)) return "";
            if (secondsBelowTarget < SustainedSeconds) return "";
            return $"性能不足：目标 {targetFps:0.0} FPS，实际 {actualFps:0.0} FPS（已持续 {secondsBelowTarget:0} 秒）；" +
                   "允许继续运行，建议降低检测频率或减少同时运行的来源。";
        }
    }
}
