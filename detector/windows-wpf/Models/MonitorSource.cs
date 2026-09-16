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
        public string Error { get; init; } = "";
        public string PerformanceWarning { get; init; } = "";
    }
}
