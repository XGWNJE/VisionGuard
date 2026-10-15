using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Threading;
using VisionGuard.Detector.Windows.Inference;
using VisionGuard.Detector.Windows.Models;
using VisionGuard.Detector.Windows.Runtime;

namespace VisionGuard.Detector.Windows.Services
{
    public sealed class MultiSourceMonitorCoordinator : IDisposable
    {
        public const int DefaultSourceLimit = 4;
        public const int MaximumSourceLimit = 16;
        private readonly object _sync = new();
        private readonly Dictionary<string, Runtime> _runtimes = new(StringComparer.Ordinal);
        private readonly Func<string, int, InferenceBackend, IInferenceEngine>? _engineFactory;

        public MultiSourceMonitorCoordinator(
            Func<string, int, InferenceBackend, IInferenceEngine>? engineFactory = null)
        {
            _engineFactory = engineFactory;
        }

        public event EventHandler<AlertEvent>? AlertTriggered;
        public event EventHandler<MonitorSourceStatus>? StatusChanged;
        public event EventHandler<SourceFrameEventArgs>? FrameProcessed;
        public event EventHandler<SourcePerformanceSample>? PerformanceMeasured;

        public IReadOnlyList<MonitorSourceStatus> Statuses
        {
            get { lock (_sync) return _runtimes.Values.Select(BuildStatus).ToArray(); }
        }

        public void Add(MonitorSource source)
        {
            lock (_sync)
            {
                if (_runtimes.ContainsKey(source.SourceId)) throw new InvalidOperationException("来源 ID 已存在。");
                var alerts = new AlertService(source.SourceId, source.SourceName);
                var monitor = new MonitorService(alerts, _engineFactory);
                var publisher = new SourcePublisher(source);
                var runtime = new Runtime(source, alerts, monitor, publisher);
                alerts.AlertTriggered += (_, alert) => AlertTriggered?.Invoke(this, alert);
                monitor.FrameProcessed += (_, frame) => OnFrame(runtime, frame);
                publisher.PerformanceMeasured += stages => PerformanceMeasured?.Invoke(this,
                    new SourcePerformanceSample(source.SourceId, source.SourceName, "JPEG 推流", publisher.TargetFps, stages));
                publisher.StatusChanged += (_, __) => OnPublished(runtime);
                publisher.PreviewFrame += (_, frame) => OnPublishedPreview(runtime, frame);
                _runtimes.Add(source.SourceId, runtime);
                RaiseStatus(runtime);
            }
        }

        public void Remove(string sourceId)
        {
            Runtime runtime;
            lock (_sync)
            {
                if (!_runtimes.TryGetValue(sourceId, out runtime!)) return;
                _runtimes.Remove(sourceId);
            }
            runtime.Dispose();
        }

        public void Rename(string sourceId, string sourceName)
        {
            sourceName = Utils.DisplayNamePolicy.Normalize(sourceName);
            Runtime runtime;
            lock (_sync)
            {
                runtime = Get(sourceId);
                runtime.Source.SourceName = sourceName;
                runtime.Alerts.UpdateSourceName(sourceName);
            }
            RaiseStatus(runtime);
        }

        public void Start(string sourceId, string modelPath)
        {
            Runtime runtime;
            lock (_sync)
            {
                runtime = Get(sourceId);
                if (runtime.Monitor.IsStarted || runtime.Publisher.IsRunning) return;
            }

            // 推流态：只采只推，不加载模型、不产生报警。
            if (runtime.Source.PublishMode == SourcePublishMode.Push)
            {
                runtime.MonitoringExpected = true;
                runtime.LastProgressAt = null;
                runtime.Error = "";
                Utils.MediaDiagnostics.Write($"[Publish] start branch source={sourceId} capture={runtime.Source.Config.CaptureMode} hwnd={runtime.Source.Config.TargetWindowHandle}");
                runtime.Publisher.Start(runtime.Source);
                RaiseStatus(runtime);
                return;
            }

            try
            {
                runtime.MonitoringExpected = true;
                runtime.LastProgressAt = null;
                runtime.Error = "";
                runtime.Monitor.Start(modelPath, runtime.Source.Config, runtime.Source.PreferredBackend);
            }
            catch (Exception ex)
            {
                runtime.Error = NativeLibrarySelector.DescribeInferenceFailure(ex);
                RaiseStatus(runtime);
                throw new InvalidOperationException(runtime.Error, ex);
            }
            RaiseStatus(runtime);
        }

        public void Stop(string sourceId)
        {
            Runtime runtime;
            lock (_sync) runtime = Get(sourceId);
            runtime.MonitoringExpected = false;
            runtime.Publisher.Stop();
            runtime.Monitor.Stop();
            RaiseStatus(runtime);
        }

        /// <summary>切换节点级状态（本机推理 / 推流）：先停稳当前状态，再写入新模式。</summary>
        public void SetPublishMode(string sourceId, SourcePublishMode mode, string modelPath)
        {
            Runtime runtime;
            bool wasMonitoring;
            lock (_sync)
            {
                runtime = Get(sourceId);
                if (runtime.Source.PublishMode == mode) return;
                wasMonitoring = runtime.Monitor.IsStarted || runtime.Publisher.IsRunning;
            }
            if (wasMonitoring) Stop(sourceId);
            runtime.Source.PublishMode = (SourcePublishMode)mode;
            runtime.Publisher.Stop();
            runtime.Error = "";
            if (wasMonitoring) Start(sourceId, modelPath);
            else RaiseStatus(runtime);
        }

        private void OnFrame(Runtime runtime, FrameResultEventArgs frame)
        {
            var now = DateTime.UtcNow;
            lock (_sync)
            {
                if (!frame.HasError) runtime.FrameTimes.Enqueue(now);
                if (!frame.HasError) runtime.LastProgressAt = now;
                while (runtime.FrameTimes.Count > 0 && (now - runtime.FrameTimes.Peek()).TotalSeconds > 10) runtime.FrameTimes.Dequeue();
                runtime.Error = frame.HasError ? frame.Error?.Message ?? "捕获或推理失败" : "";
            }
            if (!frame.HasError) PerformanceMeasured?.Invoke(this,
                new SourcePerformanceSample(runtime.Source.SourceId, runtime.Source.SourceName, runtime.Monitor.ActiveBackend, runtime.Source.Config.TargetFps, frame.StageTimings));
            FrameProcessed?.Invoke(this, new SourceFrameEventArgs(runtime.Source.SourceId, frame));
            RaiseStatus(runtime);

        }

        /// <summary>推流状态或发送进度变化：只记进度与帧率，不产生任何本机报警。</summary>
        private void OnPublished(Runtime runtime)
        {
            var now = DateTime.UtcNow;
            lock (_sync)
            {
                if (runtime.Publisher.IsPublishing)
                {
                    runtime.FrameTimes.Enqueue(now);
                    runtime.LastProgressAt = now;
                    while (runtime.FrameTimes.Count > 0 && (now - runtime.FrameTimes.Peek()).TotalSeconds > 10) runtime.FrameTimes.Dequeue();
                }
                runtime.Error = runtime.Publisher.Status == PublishStatus.Faulted ? runtime.Publisher.StatusDetail : "";
            }
            RaiseStatus(runtime);
        }

        /// <summary>
        /// 推流态的本机预览：把采集画面交给界面显示，检测框为空、不做任何推理与报警。
        /// 每帧同时重建状态，让服务端的确认序号随回执在界面上推进。
        /// </summary>
        private void OnPublishedPreview(Runtime runtime, PublishedFrame frame)
        {
            var bitmap = frame.Bitmap;
            if (runtime.Source.PublishMode != SourcePublishMode.Push)
            {
                bitmap.Dispose();
                return;
            }
            var now = DateTime.UtcNow;
            lock (_sync)
            {
                runtime.FrameTimes.Enqueue(now);
                runtime.LastProgressAt = now;
                while (runtime.FrameTimes.Count > 0 && (now - runtime.FrameTimes.Peek()).TotalSeconds > 10) runtime.FrameTimes.Dequeue();
            }
            FrameProcessed?.Invoke(this, new SourceFrameEventArgs(runtime.Source.SourceId,
                new FrameResultEventArgs(new List<Detection>(), bitmap, -1)));
            RaiseStatus(runtime);
        }

        private Runtime Get(string sourceId) => _runtimes.TryGetValue(sourceId, out var runtime)
            ? runtime : throw new KeyNotFoundException("来源不存在。");
        private MonitorSourceStatus BuildStatus(Runtime runtime)
        {
            var now = DateTime.UtcNow;
            while (runtime.FrameTimes.Count > 0 && (now - runtime.FrameTimes.Peek()).TotalSeconds > 10)
                runtime.FrameTimes.Dequeue();
            var frames = runtime.FrameTimes.ToArray();
            // net472 的 Index/Range 不可用，用显式下标取代 ^1。
            var fps = frames.Length < 2 ? 0 : (frames.Length - 1) / Math.Max(0.001, (frames[frames.Length - 1] - frames[0]).TotalSeconds);

            // 性能看门狗：实测帧率连续低于目标帧率才算不足。
            // 持续时间在这里累计：只要仍处于不足状态就保留起点，恢复正常即清零。
            bool pushing = runtime.Source.PublishMode == SourcePublishMode.Push;
            bool running = pushing ? runtime.Publisher.IsRunning : runtime.Monitor.IsStarted;
            double targetFps = Math.Max(1, runtime.Source.Config.TargetFps);
            bool belowTarget = !pushing && PerformanceWatchdog.IsBelowTarget(fps, targetFps, running);
            if (belowTarget)
            {
                if (!runtime.BelowTargetSince.HasValue) runtime.BelowTargetSince = now;
            }
            else
            {
                runtime.BelowTargetSince = null;
            }
            double secondsBelowTarget = belowTarget && runtime.BelowTargetSince.HasValue
                ? (now - runtime.BelowTargetSince.Value).TotalSeconds
                : 0;

            return new MonitorSourceStatus
            {
                SourceId = runtime.Source.SourceId, SourceName = runtime.Source.SourceName,
                ModelKey = runtime.Source.ModelKey, IsMonitoring = running,
                MonitoringExpected = runtime.MonitoringExpected, LastProgressAt = runtime.LastProgressAt,
                IsReady = IsConfigured(runtime.Source.Config), ActiveBackend = runtime.Monitor.ActiveBackend,
                ActualFps = Math.Round(fps, 2), TargetFps = targetFps,
                SecondsBelowTarget = Math.Round(secondsBelowTarget, 1),
                Error = runtime.Error,
                PerformanceWarning = PerformanceWatchdog.GetWarning(fps, targetFps, secondsBelowTarget, running),
                PublishMode = runtime.Source.PublishMode,
                IsPublishing = runtime.Publisher.IsPublishing,
                PublishStatusText = DescribePublish(runtime),
                PublishTargetFps = runtime.Publisher.TargetFps,
                PublishLastAcknowledgedSequence = runtime.Publisher.LastAcknowledgedSequence,
                PublishDroppedFrames = runtime.Publisher.DroppedFrames,
                PublishBlocked = runtime.Publisher.Rejected,
            };
        }

        /// <summary>把推流状态翻成界面可读文案；故障与未绑定分开表达。</summary>
        private static string DescribePublish(Runtime runtime)
        {
            var publisher = runtime.Publisher;
            if (publisher.TargetFps <= 0 && publisher.Status == PublishStatus.Idle) return "未推流";
            if (!string.IsNullOrEmpty(publisher.StatusDetail)) return publisher.StatusDetail;
            switch (publisher.Status)
            {
                case PublishStatus.Streaming: return "推流中";
                case PublishStatus.Connecting: return "连接媒体通道";
                case PublishStatus.Stopped: return "已停止推流";
                case PublishStatus.Faulted: return "推流失败";
                default: return "未推流";
            }
        }

        private static bool IsConfigured(MonitorConfig config)
        {
            if (config.CaptureMode == CaptureMode.RemoteStream)
                return !string.IsNullOrWhiteSpace(config.RemoteStreamId) && Capture.RemoteFrameStore.Shared.IsBound(config.RemoteStreamId);
            if (config.CaptureMode == CaptureMode.WindowHandle)
                return config.TargetWindowHandle != IntPtr.Zero
                    && (config.WindowSubRegion == System.Drawing.Rectangle.Empty || VisionGuard.Detector.Windows.Capture.CaptureSizeConstraints.IsValid(config.WindowSubRegion));
            if (config.CaptureMode == CaptureMode.ScreenRegion)
                return VisionGuard.Detector.Windows.Capture.CaptureSizeConstraints.IsValid(config.CaptureRegion);
            return false;
        }

        private void RaiseStatus(Runtime runtime)
        {
            MonitorSourceStatus status;
            lock (_sync) status = BuildStatus(runtime);
            StatusChanged?.Invoke(this, status);
        }

        public void Dispose()
        {
            Runtime[] values;
            lock (_sync) { values = _runtimes.Values.ToArray(); _runtimes.Clear(); }
            foreach (var runtime in values) runtime.Dispose();
        }

        private sealed class Runtime : IDisposable
        {
            public MonitorSource Source { get; }
            public AlertService Alerts { get; }
            public MonitorService Monitor { get; }
            public SourcePublisher Publisher { get; }
            public Queue<DateTime> FrameTimes { get; } = new();
            public string Error { get; set; } = "";
            public bool MonitoringExpected { get; set; }
            public DateTime? LastProgressAt { get; set; }

            /// <summary>实测帧率开始低于目标的时刻；恢复正常时清空。用于累计“持续不足”的时长。</summary>
            public DateTime? BelowTargetSince { get; set; }

            public Runtime(MonitorSource source, AlertService alerts, MonitorService monitor, SourcePublisher publisher)
                => (Source, Alerts, Monitor, Publisher) = (source, alerts, monitor, publisher);
            public void Dispose() { Publisher.Dispose(); Monitor.Dispose(); Alerts.Dispose(); }
        }
    }

    public sealed class SourceFrameEventArgs : EventArgs
    {
        public string SourceId { get; }
        public FrameResultEventArgs Frame { get; }
        public SourceFrameEventArgs(string sourceId, FrameResultEventArgs frame) => (SourceId, Frame) = (sourceId, frame);
    }
}
