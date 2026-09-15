using System;
using System.Collections.Generic;
using System.Linq;
using VisionGuard.Models;

namespace VisionGuard.Services
{
    /// <summary>管理 WinForms 多来源；协议上限限制新增，性能容量只告警、不拒绝运行。</summary>
    public sealed class MultiSourceMonitorCoordinator : IDisposable
    {
        public const int DefaultSourceLimit = 4;
        public const int MaximumSourceLimit = 16;

        private readonly object _sync = new object();
        private readonly Dictionary<string, ISourceMonitorRuntime> _runtimes =
            new Dictionary<string, ISourceMonitorRuntime>(StringComparer.Ordinal);
        private readonly HashSet<string> _starting = new HashSet<string>(StringComparer.Ordinal);
        private readonly Func<MonitorSource, ISourceMonitorRuntime> _runtimeFactory;
        private readonly Func<int> _capacityProvider;
        private int _sourceLimit = DefaultSourceLimit;
        private bool _disposed;

        public event EventHandler<AlertEvent> AlertTriggered;
        public event EventHandler<SourceFrameEventArgs> FrameProcessed;
        public event EventHandler<MonitorSourceStatusEventArgs> StatusChanged;

        public MultiSourceMonitorCoordinator(
            Func<MonitorSource, ISourceMonitorRuntime> runtimeFactory = null,
            Func<int> capacityProvider = null)
        {
            _runtimeFactory = runtimeFactory ?? (source => new WinFormsSourceRuntime(source));
            _capacityProvider = capacityProvider ?? (() => DefaultSourceLimit);
        }

        public int SourceLimit { get { lock (_sync) return _sourceLimit; } }
        public int Count { get { lock (_sync) return _runtimes.Count; } }

        public IList<MonitorSourceStatus> Statuses
        {
            get
            {
                lock (_sync)
                {
                    int running = _runtimes.Values.Count(runtime => runtime.IsStarted);
                    return _runtimes.Values.Select(runtime => BuildStatus(runtime, running)).ToArray();
                }
            }
        }

        public void UpdateSourceLimit(int sourceLimit)
        {
            lock (_sync) _sourceLimit = Math.Max(1, Math.Min(MaximumSourceLimit, sourceLimit));
            RaiseAllStatuses();
        }

        public void NotifyCapacityChanged() { RaiseAllStatuses(); }

        public void Add(MonitorSource source)
        {
            ThrowIfDisposed();
            ISourceMonitorRuntime runtime;
            lock (_sync)
            {
                if (_runtimes.ContainsKey(source.SourceId))
                    throw new InvalidOperationException("来源 ID 已存在。");
                if (_runtimes.Count >= _sourceLimit)
                    throw new InvalidOperationException("已达到服务端允许的来源数量上限。");
                runtime = _runtimeFactory(source);
                runtime.AlertTriggered += OnAlertTriggered;
                runtime.FrameProcessed += OnFrameProcessed;
                _runtimes.Add(source.SourceId, runtime);
            }
            RaiseStatus(runtime);
        }

        public void Remove(string sourceId)
        {
            ISourceMonitorRuntime runtime;
            lock (_sync)
            {
                if (!_runtimes.TryGetValue(sourceId, out runtime)) return;
                _runtimes.Remove(sourceId);
                _starting.Remove(sourceId);
            }
            DetachAndDispose(runtime);
            RaiseAllStatuses();
        }

        public void Rename(string sourceId, string sourceName)
        {
            ISourceMonitorRuntime runtime = Get(sourceId);
            runtime.Rename(sourceName);
            RaiseStatus(runtime);
        }

        public void UpdateConfig(string sourceId, MonitorConfig config)
        {
            ISourceMonitorRuntime runtime = Get(sourceId);
            runtime.UpdateConfig(config);
            RaiseStatus(runtime);
        }

        public void UpdateModel(string sourceId, string modelKey)
        {
            ISourceMonitorRuntime runtime = Get(sourceId);
            runtime.Source.ModelKey = modelKey ?? string.Empty;
            RaiseStatus(runtime);
        }

        public void Start(string sourceId, string modelPath)
        {
            ISourceMonitorRuntime runtime = Get(sourceId);
            lock (_sync) _starting.Add(sourceId);
            RaiseStatus(runtime);
            try { runtime.Start(modelPath); }
            finally
            {
                lock (_sync) _starting.Remove(sourceId);
                RaiseStatus(runtime);
            }
        }

        public IDictionary<string, string> StartAll(Func<MonitorSource, string> modelPathProvider)
        {
            var failures = new Dictionary<string, string>(StringComparer.Ordinal);
            ISourceMonitorRuntime[] runtimes;
            lock (_sync) runtimes = _runtimes.Values.ToArray();
            foreach (ISourceMonitorRuntime runtime in runtimes)
            {
                lock (_sync) _starting.Add(runtime.Source.SourceId);
                RaiseStatus(runtime);
                try { runtime.Start(modelPathProvider(runtime.Source)); }
                catch (Exception ex) { failures[runtime.Source.SourceId] = ex.Message; }
                finally
                {
                    lock (_sync) _starting.Remove(runtime.Source.SourceId);
                    RaiseStatus(runtime);
                }
            }
            return failures;
        }

        public void Stop(string sourceId)
        {
            ISourceMonitorRuntime runtime = Get(sourceId);
            runtime.Stop();
            RaiseStatus(runtime);
        }

        public IDictionary<string, string> StopAll()
        {
            var failures = new Dictionary<string, string>(StringComparer.Ordinal);
            ISourceMonitorRuntime[] runtimes;
            lock (_sync) runtimes = _runtimes.Values.ToArray();
            foreach (ISourceMonitorRuntime runtime in runtimes)
            {
                try { runtime.Stop(); }
                catch (Exception ex) { failures[runtime.Source.SourceId] = ex.Message; }
                finally { RaiseStatus(runtime); }
            }
            return failures;
        }

        private ISourceMonitorRuntime Get(string sourceId)
        {
            lock (_sync)
            {
                ISourceMonitorRuntime runtime;
                if (_runtimes.TryGetValue(sourceId, out runtime)) return runtime;
            }
            throw new KeyNotFoundException("来源不存在。");
        }

        private MonitorSourceStatus BuildStatus(ISourceMonitorRuntime runtime, int running)
        {
            int capacity = Math.Max(1, _capacityProvider());
            return new MonitorSourceStatus
            {
                SourceId = runtime.Source.SourceId,
                SourceName = runtime.Source.SourceName,
                ModelKey = runtime.Source.ModelKey,
                IsMonitoring = runtime.IsStarted,
                IsStarting = _starting.Contains(runtime.Source.SourceId),
                IsReady = runtime.IsReady,
                ActiveBackend = "Cpu",
                ActualFps = runtime.ActualFps,
                Error = runtime.Error ?? string.Empty,
                PerformanceWarning = running > capacity
                    ? string.Format("当前 {0} 路超过建议容量 {1} 路；允许继续运行，请关注实际帧率。", running, capacity)
                    : string.Empty,
            };
        }

        private void OnAlertTriggered(object sender, AlertEvent alert)
        {
            EventHandler<AlertEvent> handler = AlertTriggered;
            if (handler != null) handler(this, alert);
        }

        private void OnFrameProcessed(object sender, FrameResultEventArgs frame)
        {
            ISourceMonitorRuntime runtime = (ISourceMonitorRuntime)sender;
            EventHandler<SourceFrameEventArgs> handler = FrameProcessed;
            if (handler != null) handler(this, new SourceFrameEventArgs(runtime.Source.SourceId, frame));
            RaiseStatus(runtime);
        }

        private void RaiseStatus(ISourceMonitorRuntime runtime)
        {
            MonitorSourceStatus status;
            lock (_sync) status = BuildStatus(runtime, _runtimes.Values.Count(item => item.IsStarted));
            EventHandler<MonitorSourceStatusEventArgs> handler = StatusChanged;
            if (handler != null) handler(this, new MonitorSourceStatusEventArgs(status));
        }

        private void RaiseAllStatuses()
        {
            ISourceMonitorRuntime[] runtimes;
            lock (_sync) runtimes = _runtimes.Values.ToArray();
            foreach (ISourceMonitorRuntime runtime in runtimes) RaiseStatus(runtime);
        }

        private void DetachAndDispose(ISourceMonitorRuntime runtime)
        {
            runtime.AlertTriggered -= OnAlertTriggered;
            runtime.FrameProcessed -= OnFrameProcessed;
            runtime.Dispose();
        }

        private void ThrowIfDisposed()
        {
            if (_disposed) throw new ObjectDisposedException(GetType().Name);
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            ISourceMonitorRuntime[] runtimes;
            lock (_sync)
            {
                runtimes = _runtimes.Values.ToArray();
                _runtimes.Clear();
            }
            foreach (ISourceMonitorRuntime runtime in runtimes) DetachAndDispose(runtime);
        }
    }

    public sealed class SourceFrameEventArgs : EventArgs
    {
        public string SourceId { get; private set; }
        public FrameResultEventArgs Frame { get; private set; }
        public SourceFrameEventArgs(string sourceId, FrameResultEventArgs frame)
        {
            SourceId = sourceId;
            Frame = frame;
        }
    }

    public sealed class MonitorSourceStatusEventArgs : EventArgs
    {
        public MonitorSourceStatus Status { get; private set; }
        public MonitorSourceStatusEventArgs(MonitorSourceStatus status) { Status = status; }
    }
}
