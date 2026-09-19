using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using VisionGuard.Inference;
using VisionGuard.Models;
using VisionGuard.Runtime;

namespace VisionGuard.Services
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
                var runtime = new Runtime(source, alerts, monitor);
                alerts.AlertTriggered += (_, alert) => AlertTriggered?.Invoke(this, alert);
                monitor.FrameProcessed += (_, frame) => OnFrame(runtime, frame);
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
                if (runtime.Monitor.IsStarted) return;
            }

            try
            {
                runtime.Error = "";
                runtime.Monitor.Start(modelPath, runtime.Source.Config, runtime.Source.PreferredBackend);
            }
            catch (Exception ex)
            {
                runtime.Error = ex.Message;
                RaiseStatus(runtime);
                throw;
            }
            RaiseStatus(runtime);
        }

        public void Stop(string sourceId)
        {
            Runtime runtime;
            lock (_sync) runtime = Get(sourceId);
            runtime.Monitor.Stop();
            RaiseStatus(runtime);
        }

        private void OnFrame(Runtime runtime, FrameResultEventArgs frame)
        {
            var now = DateTime.UtcNow;
            lock (_sync)
            {
                if (!frame.HasError) runtime.FrameTimes.Enqueue(now);
                while (runtime.FrameTimes.Count > 0 && (now - runtime.FrameTimes.Peek()).TotalSeconds > 10) runtime.FrameTimes.Dequeue();
                runtime.Error = frame.HasError ? frame.Error?.Message ?? "捕获或推理失败" : "";
            }
            FrameProcessed?.Invoke(this, new SourceFrameEventArgs(runtime.Source.SourceId, frame));
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

            // 性能看门狗：实测帧率连续低于目标帧率才算不足（容量基线机制已于 2026-09-20 移除）。
            // 持续时间在这里累计：只要仍处于不足状态就保留起点，恢复正常即清零。
            double targetFps = Math.Max(1, runtime.Source.Config.TargetFps);
            bool belowTarget = PerformanceWatchdog.IsBelowTarget(fps, targetFps, runtime.Monitor.IsStarted);
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
                ModelKey = runtime.Source.ModelKey, IsMonitoring = runtime.Monitor.IsStarted,
                IsReady = IsConfigured(runtime.Source.Config), ActiveBackend = runtime.Monitor.ActiveBackend,
                ActualFps = Math.Round(fps, 2), TargetFps = targetFps,
                SecondsBelowTarget = Math.Round(secondsBelowTarget, 1),
                Error = runtime.Error,
                PerformanceWarning = PerformanceWatchdog.GetWarning(fps, targetFps, secondsBelowTarget, runtime.Monitor.IsStarted),
            };
        }

        private static bool IsConfigured(MonitorConfig config)
        {
            if (config.CaptureMode == CaptureMode.WindowHandle)
                return config.TargetWindowHandle != IntPtr.Zero
                    && (config.WindowSubRegion == System.Drawing.Rectangle.Empty || VisionGuard.Capture.CaptureSizeConstraints.IsValid(config.WindowSubRegion));
            if (config.CaptureMode == CaptureMode.ScreenRegion)
                return VisionGuard.Capture.CaptureSizeConstraints.IsValid(config.CaptureRegion);
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
            public Queue<DateTime> FrameTimes { get; } = new();
            public string Error { get; set; } = "";

            /// <summary>实测帧率开始低于目标的时刻；恢复正常时清空。用于累计“持续不足”的时长。</summary>
            public DateTime? BelowTargetSince { get; set; }

            public Runtime(MonitorSource source, AlertService alerts, MonitorService monitor) => (Source, Alerts, Monitor) = (source, alerts, monitor);
            public void Dispose() { Monitor.Dispose(); Alerts.Dispose(); }
        }
    }

    public sealed class SourceFrameEventArgs : EventArgs
    {
        public string SourceId { get; }
        public FrameResultEventArgs Frame { get; }
        public SourceFrameEventArgs(string sourceId, FrameResultEventArgs frame) => (SourceId, Frame) = (sourceId, frame);
    }
}
