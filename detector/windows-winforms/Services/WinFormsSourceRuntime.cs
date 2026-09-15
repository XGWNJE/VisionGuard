using System;
using System.Collections.Generic;
using VisionGuard.Models;

namespace VisionGuard.Services
{
    public interface ISourceMonitorRuntime : IDisposable
    {
        MonitorSource Source { get; }
        bool IsStarted { get; }
        bool IsReady { get; }
        double ActualFps { get; }
        string Error { get; }
        event EventHandler<AlertEvent> AlertTriggered;
        event EventHandler<FrameResultEventArgs> FrameProcessed;
        void Rename(string sourceName);
        void Start(string modelPath);
        void Stop();
        void UpdateConfig(MonitorConfig config);
    }

    /// <summary>一条 WinForms 来源的完整运行边界：独立报警冷却、推理循环、状态和故障。</summary>
    public sealed class WinFormsSourceRuntime : ISourceMonitorRuntime
    {
        private readonly object _sync = new object();
        private readonly Queue<DateTime> _frameTimes = new Queue<DateTime>();
        private readonly AlertService _alerts;
        private readonly MonitorService _monitor;
        private string _error = string.Empty;
        private bool _disposed;

        public MonitorSource Source { get; private set; }
        public bool IsStarted { get { return _monitor.IsStarted; } }
        public bool IsReady { get { return _monitor.IsReady; } }
        public string Error { get { lock (_sync) return _error; } }

        public double ActualFps
        {
            get
            {
                lock (_sync)
                {
                    TrimFrames(DateTime.UtcNow);
                    if (_frameTimes.Count < 2) return 0d;
                    DateTime[] frames = _frameTimes.ToArray();
                    return Math.Round((frames.Length - 1) /
                        Math.Max(0.001d, (frames[frames.Length - 1] - frames[0]).TotalSeconds), 2);
                }
            }
        }

        public event EventHandler<AlertEvent> AlertTriggered;
        public event EventHandler<FrameResultEventArgs> FrameProcessed;

        public WinFormsSourceRuntime(MonitorSource source)
        {
            Source = source ?? throw new ArgumentNullException("source");
            _alerts = new AlertService(source.SourceId, source.SourceName);
            _monitor = new MonitorService(_alerts);
            _monitor.UpdateConfig(source.Config);
            _alerts.AlertTriggered += OnAlertTriggered;
            _monitor.FrameProcessed += OnFrameProcessed;
        }

        public void Rename(string sourceName)
        {
            Source.SourceName = string.IsNullOrWhiteSpace(sourceName) ? Source.SourceId : sourceName.Trim();
            _alerts.UpdateSourceName(Source.SourceName);
        }

        public void Start(string modelPath)
        {
            ThrowIfDisposed();
            lock (_sync)
            {
                _error = string.Empty;
                _frameTimes.Clear();
            }
            try
            {
                _monitor.Start(modelPath, Source.Config);
            }
            catch (Exception ex)
            {
                lock (_sync) _error = ex.Message;
                throw;
            }
        }

        public void Stop()
        {
            _monitor.Stop();
            lock (_sync) _frameTimes.Clear();
        }

        public void UpdateConfig(MonitorConfig config)
        {
            if (config == null) throw new ArgumentNullException("config");
            Source.Config = config;
            if (_monitor.IsStarted) _monitor.UpdateConfig(config);
        }

        private void OnAlertTriggered(object sender, AlertEvent alert)
        {
            EventHandler<AlertEvent> handler = AlertTriggered;
            if (handler != null) handler(this, alert);
        }

        private void OnFrameProcessed(object sender, FrameResultEventArgs frame)
        {
            lock (_sync)
            {
                DateTime now = DateTime.UtcNow;
                if (frame.HasError) _error = frame.Error == null ? "捕获或推理失败" : frame.Error.Message;
                else
                {
                    _error = string.Empty;
                    _frameTimes.Enqueue(now);
                    TrimFrames(now);
                }
            }
            EventHandler<FrameResultEventArgs> handler = FrameProcessed;
            if (handler != null) handler(this, frame);
        }

        private void TrimFrames(DateTime now)
        {
            while (_frameTimes.Count > 0 && (now - _frameTimes.Peek()).TotalSeconds > 10d)
                _frameTimes.Dequeue();
        }

        private void ThrowIfDisposed()
        {
            if (_disposed) throw new ObjectDisposedException(GetType().Name);
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            _alerts.AlertTriggered -= OnAlertTriggered;
            _monitor.FrameProcessed -= OnFrameProcessed;
            _monitor.Dispose();
            _alerts.Dispose();
        }
    }
}
