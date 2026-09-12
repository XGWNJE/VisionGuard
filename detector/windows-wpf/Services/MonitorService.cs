// ┌─────────────────────────────────────────────────────────┐
// │ MonitorService.cs                                       │
// │ 角色：主监控循环，定时截图→推理→报警                    │
// │ 线程：Timer回调在 ThreadPool 执行，UI 更新通过事件      │
// │ 依赖：OnnxInferenceEngine, AlertService, ImagePreprocessor│
// │ 对外 API：Start(), Stop()                               │
// │ 事件：FrameProcessed (每帧结果通知 Form1 更新 UI)       │
// └─────────────────────────────────────────────────────────┘
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

        private IInferenceEngine     _engine;
        private readonly Func<string, int, InferenceBackend, IInferenceEngine> _engineFactory;
        private AlertService         _alertService;
        private MonitorConfig        _config;
        private Timer                _timer;
        private int  _isRunning;   // 0=idle, 1=processing（Interlocked 防重入）
        private int  _isPaused;    // 0=running, 1=paused（Interlocked 远控暂停）
        private bool _disposed;
        // 停止同步：确保 OnTick 完全结束（包括 finally）后才能安全 Dispose _engine
        private readonly ManualResetEvent _tickCompleted = new ManualResetEvent(true);

        public bool IsStarted => _timer != null;
        public bool IsPaused => Interlocked.CompareExchange(ref _isPaused, 0, 0) == 1;
        public string ActiveBackend => _engine?.ActiveBackend.ToString() ?? "Unavailable";
        public string BackendFallbackReason => _engine?.BackendFallbackReason ?? string.Empty;

        /// <summary>选区/窗口是否已设定（用于心跳同步给 Android 显示准备状态）</summary>
        public bool IsReady
        {
            get
            {
                if (_config == null) return false;
                if (_config.CaptureMode == CaptureMode.WindowHandle)
                    return _config.TargetWindowHandle != IntPtr.Zero
                        && (_config.WindowSubRegion == Rectangle.Empty || CaptureSizeConstraints.IsValid(_config.WindowSubRegion));
                return CaptureSizeConstraints.IsValid(_config.CaptureRegion);
            }
        }

        public MonitorService(
            AlertService alertService,
            Func<string, int, InferenceBackend, IInferenceEngine>? engineFactory = null)
        {
            _alertService = alertService;
            _engineFactory = engineFactory ?? ((path, threads, backend) =>
                new OnnxInferenceEngine(path, intraOpNumThreads: threads, preferredBackend: backend));
        }

        /// <summary>
        /// 启动监控。modelPath = yolo26n.onnx 或 yolo26s.onnx 完整路径。
        /// </summary>
        public void Start(string modelPath, MonitorConfig config, InferenceBackend preferredBackend = InferenceBackend.DirectML)
        {
            if (_disposed) throw new ObjectDisposedException(nameof(MonitorService));
            if (_timer != null) return;

            if (config.CaptureMode == CaptureMode.ScreenRegion && !CaptureSizeConstraints.IsValid(config.CaptureRegion))
                throw new InvalidOperationException("屏幕选区宽度和高度必须都大于 100 像素。");
            if (config.CaptureMode == CaptureMode.WindowHandle
                && (config.TargetWindowHandle == IntPtr.Zero
                    || (config.WindowSubRegion != Rectangle.Empty && !CaptureSizeConstraints.IsValid(config.WindowSubRegion))))
                throw new InvalidOperationException("目标窗口或窗口选区无效，宽度和高度必须都大于 100 像素。");

            _config  = config;
            _engine  = _engineFactory(modelPath, 2, preferredBackend);

            int intervalMs = 1000 / Math.Max(1, config.TargetFps);
            _timer = new Timer(OnTick, null, 0, intervalMs);
        }

        public void Stop()
        {
            // 阻止新 OnTick 进入，并等待正在执行的 Tick 完全结束
            _tickCompleted.Reset();           // 未完成信号
            _timer?.Dispose();
            _timer = null;
            _tickCompleted.WaitOne(2000);     // 最多等2秒让 OnTick 退出
            // 超时保护：若 OnTick 仍未退出，等待 _isRunning 清零（再给 1 秒）
            if (Interlocked.CompareExchange(ref _isRunning, 0, 0) != 0)
            {
                for (int i = 0; i < 10 && Interlocked.CompareExchange(ref _isRunning, 0, 0) != 0; i++)
                    Thread.Sleep(100);
            }
            _engine?.Dispose();
            _engine = null;
            _isRunning = 0;
            _tickCompleted.Set();             // 恢复为已结束状态
        }

        public void Pause()
        {
            Interlocked.Exchange(ref _isPaused, 1);
        }

        public void Resume()
        {
            Interlocked.Exchange(ref _isPaused, 0);
        }

        public void UpdateConfig(MonitorConfig config)
        {
            Volatile.Write(ref _config, config);
        }

        // ── 每帧回调（ThreadPool 线程）──────────────────────────────

        private void OnTick(object state)
        {
            // 停止中：跳过本次Tick（Stop 已调用 WaitOne，这里直接返回）
            if (!_tickCompleted.WaitOne(0)) return;

            // 远控暂停：跳过帧处理但不停止 Timer（保留心跳上报）
            // 必须在 _isRunning 之前检查，否则暂停后 _isRunning 永远为 1，Resume 后也无法恢复
            if (Interlocked.CompareExchange(ref _isPaused, 0, 0) == 1) return;

            // 防重入：若上一帧还在推理，跳过本帧
            if (Interlocked.CompareExchange(ref _isRunning, 1, 0) != 0) return;

            // 标记 Tick 开始执行（Stop 会等待此信号）
            _tickCompleted.Reset();

            MonitorConfig cfg = Volatile.Read(ref _config);
            Bitmap frame    = null;
            MonitorFailureKind failureKind = MonitorFailureKind.Capture;

            try
            {
                var totalSw = Stopwatch.StartNew();
                var sw = Stopwatch.StartNew();

                // 1. 截图（根据捕获模式选择方式）
                if (cfg.CaptureMode == Models.CaptureMode.WindowHandle
                    && cfg.TargetWindowHandle != IntPtr.Zero)
                {
                    frame = WindowCapturer.CaptureWindow(cfg.TargetWindowHandle, cfg.WindowSubRegion);
                }
                else
                {
                    frame = ScreenCapturer.CaptureRegion(cfg.CaptureRegion);
                }
                long captureMs = sw.ElapsedMilliseconds;

                // 1.5 应用遮罩区域（in-place，同时影响推理 / 报警截图 / UI 预览）
                if (cfg.MaskRegions != null && cfg.MaskRegions.Count > 0)
                    MaskApplier.ApplyMasks(frame, cfg.MaskRegions);

                // 2. 预处理（内部 resize + 转张量）
                failureKind = MonitorFailureKind.Processing;
                int modelSize = _engine.ModelInputSize;
                sw.Restart();
                float[] tensor = ImagePreprocessor.ToTensor(frame, modelSize);
                long preprocessMs = sw.ElapsedMilliseconds;

                // 3. 推理
                failureKind = MonitorFailureKind.Inference;
                sw.Restart();
                float[] rawOutput = _engine.Run(tensor, ImagePreprocessor.InputShape(modelSize));
                long inferMs = sw.ElapsedMilliseconds;

                // 4. 解析（使用实际帧尺寸，避免窗口缩放导致坐标偏移）
                failureKind = MonitorFailureKind.Processing;
                sw.Restart();
                var frameRegion = new Rectangle(0, 0, frame.Width, frame.Height);
                List<Detection> detections = YoloOutputParser.Parse(
                    rawOutput,
                    frameRegion,
                    cfg.ConfidenceThreshold,
                    cfg.WatchedClasses,
                    modelSize);
                long parseMs = sw.ElapsedMilliseconds;

                // 5. 报警评估（使用推理帧绘制检测框，确保坐标匹配）
                var timings = new Dictionary<string, long>
                {
                    ["captureMs"]     = captureMs,
                    ["preprocessMs"]  = preprocessMs,
                    ["inferMs"]       = inferMs,
                    ["parseMs"]       = parseMs,
                };
                _alertService.Evaluate(detections, cfg, timings, frame);

                // 6. 通知 UI
                FrameProcessed?.Invoke(this, new FrameResultEventArgs(
                    detections, (Bitmap)frame.Clone(), inferMs, totalSw.ElapsedMilliseconds));
            }
            catch (ObjectDisposedException)
            {
                // 服务已停止，忽略
            }
            catch (Exception ex)
            {
                var actualKind = ex is CaptureBlackFrameException
                    ? MonitorFailureKind.BlackFrame
                    : failureKind;
                FrameProcessed?.Invoke(this, new FrameResultEventArgs(ex, actualKind));
            }
            finally
            {
                // 标记 Tick 结束：Stop() 可以安全 Dispose _engine
                _tickCompleted.Set();
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
        public long            ProcessingMs { get; }
        public Exception       Error       { get; }
        public bool            HasError    => Error != null;
        public MonitorFailureKind FailureKind { get; }

        public FrameResultEventArgs(List<Detection> dets, Bitmap frame, long inferMs, long processingMs = 0)
        {
            Detections  = dets;
            Frame       = frame;
            InferenceMs = inferMs;
            ProcessingMs = processingMs;
        }

        public FrameResultEventArgs(Exception error, MonitorFailureKind failureKind = MonitorFailureKind.Processing)
        {
            Error = error;
            FailureKind = failureKind;
        }
    }
}
