using System;
using VisionGuard.Detector.Windows.Inference;

namespace VisionGuard.Detector.Windows.Models
{
    public sealed class MonitorSource
    {
        public string SourceId { get; }
        public string SourceName { get; set; }
        public string ModelKey { get; set; }
        public MonitorConfig Config { get; set; }
        public InferenceBackend PreferredBackend { get; set; }

        /// <summary>
        /// 节点级状态：本机推理（默认）或推流到其他推理节点。推流态只采只推，不加载模型、不产生报警。
        /// 两个状态互斥，切换由协调器先停稳再切。
        /// </summary>
        public SourcePublishMode PublishMode { get; set; } = SourcePublishMode.Inference;

        public MonitorSource(string sourceId, string sourceName, string modelKey, MonitorConfig config,
            InferenceBackend preferredBackend = InferenceBackend.DirectML)
        {
            if (string.IsNullOrWhiteSpace(sourceId)) throw new ArgumentException("Source ID is required.", nameof(sourceId));
            SourceId = sourceId;
            SourceName = Utils.DisplayNamePolicy.Normalize(sourceName);
            ModelKey = modelKey;
            Config = config ?? throw new ArgumentNullException(nameof(config));
            PreferredBackend = preferredBackend;
        }
    }

    /// <summary>节点级状态：在本地推理，或把采集画面推流给其他推理节点。</summary>
    public enum SourcePublishMode { Inference, Push }

    public sealed class MonitorSourceStatus
    {
        public string SourceId { get; init; } = "";
        public string SourceName { get; init; } = "";
        public string ModelKey { get; init; } = "";
        public bool IsMonitoring { get; init; }
        public bool MonitoringExpected { get; init; }
        public DateTime? LastProgressAt { get; init; }
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

        /// <summary>节点级状态：本机推理或推流。</summary>
        public SourcePublishMode PublishMode { get; init; } = SourcePublishMode.Inference;

        /// <summary>推流态是否正在推流（已建立媒体连接并持续发送）。</summary>
        public bool IsPublishing { get; init; }

        /// <summary>推流状态说明（连接中 / 已停止 / 失败原因）。</summary>
        public string PublishStatusText { get; init; } = "";

        /// <summary>推流目标频率（服务按接收端来源状态下发，1–5 FPS）。</summary>
        public int PublishTargetFps { get; init; }

        /// <summary>最后收到确认的帧序号；用于确认中继确实在推进。</summary>
        public long PublishLastAcknowledgedSequence { get; init; }

        /// <summary>因在途额度或编码失败被放弃的帧数。</summary>
        public long PublishDroppedFrames { get; init; }

        /// <summary>服务端明确拒收（未绑定目标 / 目标未接入媒体通道），推流在发但没被收下。</summary>
        public bool PublishBlocked { get; init; }
    }
}
