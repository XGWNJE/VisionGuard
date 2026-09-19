using System;
using VisionGuard.Inference;

namespace VisionGuard.Models
{
    public sealed class MonitorSource
    {
        public string SourceId { get; }
        public string SourceName { get; set; }
        public string ModelKey { get; set; }
        public MonitorConfig Config { get; set; }
        public InferenceBackend PreferredBackend { get; set; }

        public MonitorSource(string sourceId, string sourceName, string modelKey, MonitorConfig config,
            InferenceBackend preferredBackend = InferenceBackend.DirectML)
        {
            if (string.IsNullOrWhiteSpace(sourceId)) throw new ArgumentException("Source ID is required.", nameof(sourceId));
            SourceId = sourceId;
            SourceName = string.IsNullOrWhiteSpace(sourceName) ? sourceId : sourceName;
            ModelKey = modelKey;
            Config = config ?? throw new ArgumentNullException(nameof(config));
            PreferredBackend = preferredBackend;
        }
    }

    public sealed class MonitorSourceStatus
    {
        public string SourceId { get; init; } = "";
        public string SourceName { get; init; } = "";
        public string ModelKey { get; init; } = "";
        public bool IsMonitoring { get; init; }
        public bool IsReady { get; init; }
        public string ActiveBackend { get; init; } = "Unavailable";
        public double ActualFps { get; init; }

        /// <summary>这一路当前设定的目标推理频率（次/秒），用于判断实测帧率是否达标。</summary>
        public double TargetFps { get; init; }

        /// <summary>
        /// 实测帧率连续低于目标（低于目标的 80%）的秒数；没有处于不足状态时为 0。
        /// 达到 <see cref="Services.PerformanceWatchdog.SustainedSeconds"/> 之后
        /// <see cref="PerformanceWarning"/> 才会有文案。
        /// </summary>
        public double SecondsBelowTarget { get; init; }

        public string Error { get; init; } = "";

        /// <summary>实测帧率不足的可见提示（未确认不足时为空串）。</summary>
        public string PerformanceWarning { get; init; } = "";
    }
}
