using System;
using System.Collections.Generic;
using System.Linq;
using VisionGuard.Inference;
using VisionGuard.Models;

namespace VisionGuard.Services
{
    public sealed class MultiSourceMonitorCoordinator : IDisposable
    {
        public const int MaxSources = 3;
        private readonly object _sync = new();
        private readonly Dictionary<string, Runtime> _runtimes = new(StringComparer.Ordinal);

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
                if (_runtimes.Count >= MaxSources) throw new InvalidOperationException("最多只能配置三个检测来源。");
                if (_runtimes.ContainsKey(source.SourceId)) throw new InvalidOperationException("来源 ID 已存在。");
                var alerts = new AlertService(source.SourceId, source.SourceName);
                var monitor = new MonitorService(alerts);
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

        public void Start(string sourceId, string modelPath)
        {
            Runtime runtime;
            lock (_sync)
            {
                runtime = Get(sourceId);
                if (runtime.Monitor.IsStarted) return;
                if (runtime.Source.PreferredBackend == InferenceBackend.Cpu && _runtimes.Values.Any(r => r.Monitor.IsStarted))
                    throw new InvalidOperationException("CPU 模式只允许运行一个来源。");
            }

            try
            {
                runtime.Error = "";
                runtime.Monitor.Start(modelPath, runtime.Source.Config, runtime.Source.PreferredBackend);
                if (runtime.Monitor.ActiveBackend == nameof(InferenceBackend.Cpu))
                {
                    lock (_sync)
                    {
                        if (_runtimes.Values.Any(r => !ReferenceEquals(r, runtime) && r.Monitor.IsStarted))
                        {
                            runtime.Monitor.Stop();
                            throw new InvalidOperationException("DirectML 回退 CPU 后禁止多来源并行；请停止其他来源后重试。");
                        }
                    }
                }
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
                runtime.FrameTimes.Enqueue(now);
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
            var frames = runtime.FrameTimes.ToArray();
            var fps = frames.Length < 2 ? 0 : (frames.Length - 1) / Math.Max(0.001, (frames[^1] - frames[0]).TotalSeconds);
            return new MonitorSourceStatus
            {
                SourceId = runtime.Source.SourceId, SourceName = runtime.Source.SourceName,
                ModelKey = runtime.Source.ModelKey, IsMonitoring = runtime.Monitor.IsStarted,
                IsReady = runtime.Monitor.IsReady, ActiveBackend = runtime.Monitor.ActiveBackend,
                ActualFps = Math.Round(fps, 2), Error = runtime.Error,
            };
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
