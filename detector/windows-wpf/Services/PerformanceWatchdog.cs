using System;

namespace VisionGuard.Services
{
    /// <summary>
    /// 推理性能看门狗：把「实测推理帧率」与这一路设定的目标帧率对比，判断设备是否已经跑不动当前配置。
    ///
    /// 2026-09-20 起取代原先的「容量基线」：容量基线要用户自己猜一个数字（DirectML 几路、CPU 几路），
    /// 猜错就会误报（基线给大了）或漏报（基线给小了），而且和真实算力没有直接关系。
    /// 这里直接看实测帧率。
    ///
    /// 判定口径（owner 确认）：
    /// ① 实际 &lt; 目标 × 0.8 且**连续持续 30 秒**才算不足——过滤掉瞬间空转与单帧抖动；
    /// ② 任一路不足即提醒，弹窗里列出是哪几路（冷却 10 分钟，见 <see cref="AlertCooldownMinutes"/>）；
    /// ③ 尚未出帧（fps ≤ 0）不按性能判定，那是启动中或故障，由来源的 Error 表达。
    ///
    /// 纯计算、不依赖 WPF，因此净室探针可以直接断言这些边界。
    /// </summary>
    public static class PerformanceWatchdog
    {
        /// <summary>实际帧率低于目标帧率的这个比例即视为不达标。</summary>
        public const double TargetRatio = 0.8;

        /// <summary>不达标需要持续的秒数。</summary>
        public const double SustainedSeconds = 30;

        /// <summary>两次性能弹窗之间的最小间隔（分钟）：持续不足时不要反复打扰。</summary>
        public const double AlertCooldownMinutes = 10;

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
