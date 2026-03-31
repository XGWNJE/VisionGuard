using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Threading;
using VisionGuard.Capture;
using VisionGuard.Inference;
using VisionGuard.Models;

namespace VisionGuard.Services
{
    /// <summary>
    /// 主监控循环：定时截图 → 推理 → 报警。
    /// 所有推理在 ThreadPool 线程执行，UI 线程不受阻塞。
    /// </summary>
    public sealed class MonitorService : IDisposable
    {
        public event EventHandler<FrameResultEventArgs> FrameProcessed;

        private OnnxInferenceEngine _engine;
        private AlertService        _alertService;
        private MonitorConfig       _config;
        private Timer               _timer;
        private int                 _isRunning;   // 0=idle, 1=processing（Interlocked 防重入）
        private bool                _disposed;

        // 统计
        private long  _totalFrames;
        private long  _totalInferenceMs;

        public bool IsStarted => _timer != null;

        public MonitorService(AlertService alertService)
        {
            _alertService = alertService;
        }

        /// <summary>
        /// 启动监控。modelPath = yolov5nu.onnx 完整路径。
        /// </summary>
        public void Start(string modelPath, MonitorConfig config)
        {
            if (_disposed) throw new ObjectDisposedException(nameof(MonitorService));
            if (_timer != null) return;

            _config  = config;
            _engine  = new OnnxInferenceEngine(modelPath, config.IntraOpNumThreads);

            int intervalMs = 1000 / Math.Max(1, config.TargetFps);
            _timer = new Timer(OnTick, null, 0, intervalMs);
        }

        public void Stop()
        {
            _timer?.Dispose();
            _timer = null;
            _engine?.Dispose();
            _engine = null;
            _isRunning = 0;
        }

        public void UpdateConfig(MonitorConfig config)
        {
            Volatile.Write(ref _config, config);
        }

        // ── 每帧回调（ThreadPool 线程）──────────────────────────────

        private void OnTick(object state)
        {
            // 防重入：若上一帧还在推理，跳过本帧
            if (Interlocked.CompareExchange(ref _isRunning, 1, 0) != 0) return;

            MonitorConfig cfg = Volatile.Read(ref _config);
            Bitmap frame    = null;
            Bitmap snapshot = null;

            try
            {
                // 1. 截图
                frame = ScreenCapturer.CaptureRegion(cfg.CaptureRegion);

                // 2. 预处理（内部 resize + 转张量）
                float[] tensor = ImagePreprocessor.ToTensor(frame);

                // 3. 推理（计时）
                var sw = Stopwatch.StartNew();
                float[] rawOutput = _engine.Run(tensor, ImagePreprocessor.InputShape);
                sw.Stop();
                long inferMs = sw.ElapsedMilliseconds;

                // 4. 解析
                List<Detection> detections = YoloOutputParser.Parse(
                    rawOutput,
                    cfg.CaptureRegion,
                    cfg.ConfidenceThreshold,
                    cfg.IouThreshold,
                    cfg.WatchedClassIds);

                // 5. 报警评估（传 frame 供 AlertService clone 快照）
                _alertService.Evaluate(detections, frame, cfg);

                // 6. 统计 & 通知 UI
                Interlocked.Increment(ref _totalFrames);
                Interlocked.Add(ref _totalInferenceMs, inferMs);

                FrameProcessed?.Invoke(this, new FrameResultEventArgs(
                    detections, (Bitmap)frame.Clone(), inferMs));
            }
            catch (ObjectDisposedException)
            {
                // 服务已停止，忽略
            }
            catch (Exception ex)
            {
                FrameProcessed?.Invoke(this, new FrameResultEventArgs(ex));
            }
            finally
            {
                frame?.Dispose();
                Interlocked.Exchange(ref _isRunning, 0);
            }
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            Stop();
        }
    }

    // ── 事件参数 ──────────────────────────────────────────────────────

    public class FrameResultEventArgs : EventArgs
    {
        public List<Detection> Detections  { get; }
        public Bitmap          Frame       { get; }   // 调用方负责 Dispose
        public long            InferenceMs { get; }
        public Exception       Error       { get; }
        public bool            HasError    => Error != null;

        public FrameResultEventArgs(List<Detection> dets, Bitmap frame, long inferMs)
        {
            Detections  = dets;
            Frame       = frame;
            InferenceMs = inferMs;
        }

        public FrameResultEventArgs(Exception error)
        {
            Error = error;
        }
    }
}
