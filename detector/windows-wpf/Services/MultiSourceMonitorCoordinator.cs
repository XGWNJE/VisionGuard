using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using VisionGuard.Inference;
using VisionGuard.Models;

namespace VisionGuard.Services
{
    public sealed class MultiSourceMonitorCoordinator : IDisposable
    {
        public const int DefaultSourceLimit = 4;
        public const int MaximumSourceLimit = 16;
        private readonly object _sync = new();
        private readonly Dictionary<string, Runtime> _runtimes = new(StringComparer.Ordinal);
        private readonly Func<string, int, InferenceBackend, IInferenceEngine>? _engineFactory;
        private readonly Func<InferenceBackend, int> _capacityProvider;

        public MultiSourceMonitorCoordinator(
            Func<string, int, InferenceBackend, IInferenceEngine>? engineFactory = null,
            Func<InferenceBackend, int>? capacityProvider = null)
        {
            _engineFactory = engineFactory;
            _capacityProvider = capacityProvider ?? (_ => DefaultSourceLimit);
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
            var fps = frames.Length < 2 ? 0 : (frames.Length - 1) / Math.Max(0.001, (frames[^1] - frames[0]).TotalSeconds);
            var activeBackend = runtime.Monitor.ActiveBackend;
            var runningOnBackend = _runtimes.Values.Count(item => item.Monitor.IsStarted
                && string.Equals(item.Monitor.ActiveBackend, activeBackend, StringComparison.OrdinalIgnoreCase));
            var backend = string.Equals(activeBackend, nameof(InferenceBackend.Cpu), StringComparison.OrdinalIgnoreCase)
                ? InferenceBackend.Cpu : InferenceBackend.DirectML;
            var warning = runtime.Monitor.IsStarted
                ? CapacityPolicy.GetWarning(activeBackend, runningOnBackend, _capacityProvider(backend)) : "";
            return new MonitorSourceStatus
            {
                SourceId = runtime.Source.SourceId, SourceName = runtime.Source.SourceName,
                ModelKey = runtime.Source.ModelKey, IsMonitoring = runtime.Monitor.IsStarted,
                IsReady = IsConfigured(runtime.Source.Config), ActiveBackend = runtime.Monitor.ActiveBackend,
                ActualFps = Math.Round(fps, 2), Error = runtime.Error, PerformanceWarning = warning,
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
