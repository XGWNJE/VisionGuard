// ┌─────────────────────────────────────────────────────────┐
// │ MonitorService.cs                                       │
// │ 角色：主监控循环，定时截图→推理→报警                    │
// │ 线程：Timer回调在 ThreadPool 执行，UI 更新通过事件      │
// │ 依赖：OnnxInferenceEngine, AlertService, ImagePreprocessor│
// │ 对外 API：Start(), Stop()                               │
// │ 事件：FrameProcessed (每帧结果通知来源 UI)       │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Threading;
using VisionGuard.Detector.Windows.Capture;
using VisionGuard.Detector.Windows.Inference;
using VisionGuard.Detector.Windows.Models;
using VisionGuard.Detector.Windows.Runtime;
using VisionGuard.Detector.Windows.Utils;

namespace VisionGuard.Detector.Windows.Services
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
        private bool _disposed;
        // 输出形态日志：只在形态变化时写一次，避免每帧刷屏；形态与档位不匹配是漏检头号根因，必须可诊断。
        private string _loggedOutputLayout;
        // 停止同步：确保 OnTick 完全结束（包括 finally）后才能安全 Dispose _engine
        private readonly ManualResetEvent _tickCompleted = new ManualResetEvent(true);
        private readonly object _tickSync = new object();

        public bool IsStarted => _timer != null;
        public string ActiveBackend => _engine?.ActiveBackend.ToString() ?? "Unavailable";
        public string BackendFallbackReason => _engine?.BackendFallbackReason ?? string.Empty;

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
            if (_engine != null) Stop();

            if (config.CaptureMode == CaptureMode.ScreenRegion && !CaptureSizeConstraints.IsValid(config.CaptureRegion))
                throw new InvalidOperationException("屏幕选区宽度和高度必须都大于 100 像素。");
            if (config.CaptureMode == CaptureMode.WindowHandle
                && (config.TargetWindowHandle == IntPtr.Zero
                    || (config.WindowSubRegion != Rectangle.Empty && !CaptureSizeConstraints.IsValid(config.WindowSubRegion))))
                throw new InvalidOperationException("目标窗口或窗口选区无效，宽度和高度必须都大于 100 像素。");

            // 启动阶段的预加载若遇到短暂文件占用，这里会重试；仍失败时必须在触发 ORT
            // NativeMethods 静态初始化前停止，否则类型会在本进程内永久保持失败状态。
            NativeLibrarySelector.Initialize();
            if (!NativeLibrarySelector.IsReady)
                throw new InvalidOperationException(NativeLibrarySelector.FailureReason);

            _config  = config;
            _engine  = _engineFactory(modelPath, 2, preferredBackend);

            // 建会话后再校验一次原生库来源：系统级同名 onnxruntime.dll 会抢占档位目录，
            // 与托管程序集不同代时是一推理就无诊断信息的进程终止，必须在启动来源时就报成可读故障。
            string nativeMismatch = NativeLibrarySelector.DescribeUnexpectedLoadedLibrary();
            if (!string.IsNullOrEmpty(nativeMismatch))
            {
                var broken = _engine;
                _engine = null;
                broken?.Dispose();
                throw new InvalidOperationException(nativeMismatch);
            }

            int intervalMs = 1000 / Math.Max(1, config.TargetFps);
            lock (_tickSync) _timer = new Timer(OnTick, null, 0, intervalMs);
        }

        public void Stop()
        {
            // 与帧入口同步：关闭定时器后，排队的回调不能再使用推理引擎。
            lock (_tickSync)
            {
                _timer?.Dispose();
                _timer = null;
            }
            // 空闲时信号已经置位，立即返回；只有实际正在处理的帧才需要等待。
            if (!_tickCompleted.WaitOne(2000))
                throw new TimeoutException("当前帧仍在处理，暂停未完成，请稍后重试。");
            _engine?.Dispose();
            _engine = null;
        }

        // ── 每帧回调（ThreadPool 线程）──────────────────────────────

        private void OnTick(object state)
        {
            lock (_tickSync)
            {
                if (_timer == null || _isRunning != 0) return;
                _isRunning = 1;
                _tickCompleted.Reset();
            }

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

                // 2. 预处理（等比缩放 + 居中黑边填充 + 转张量）。变换必须和张量一起传给解析器，
                    failureKind = MonitorFailureKind.Processing;
                int modelSize = _engine.ModelInputSize;
                sw.Restart();
                PreprocessedImage preprocessed = ImagePreprocessor.Prepare(frame, modelSize);
                long preprocessMs = sw.ElapsedMilliseconds;

                // 3. 推理
                failureKind = MonitorFailureKind.Inference;
                sw.Restart();
                float[] rawOutput = _engine.Run(preprocessed.Tensor, ImagePreprocessor.InputShape(modelSize));
                long inferMs = sw.ElapsedMilliseconds;
                LogOutputLayoutOnce(rawOutput != null ? rawOutput.Length : 0, modelSize);

                // 4. 解析（按预处理的等比留白变换还原到实际帧尺寸）
                failureKind = MonitorFailureKind.Processing;
                sw.Restart();
                List<Detection> detections = YoloOutputParser.Parse(
                    rawOutput,
                    preprocessed.Transform,
                    cfg.ConfidenceThreshold,
                    cfg.WatchedClasses);
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
                frame?.Dispose();
                lock (_tickSync)
                {
                    _isRunning = 0;
                    _tickCompleted.Set();
                }
            }
        }

        /// <summary>
        /// 记录模型输出张量形态与实际解析分支（仅在变化时记录一次）。
        /// 档位与模型不匹配会让每一帧都解析不出目标却无任何报错，形态日志是这种静默漏检的唯一现场证据。
        /// </summary>
        private void LogOutputLayoutOnce(int rawLength, int modelSize)
        {
            try
            {
                string layout = YoloOutputParser.Describe(rawLength);
                if (string.Equals(layout, _loggedOutputLayout, StringComparison.Ordinal)) return;

                _loggedOutputLayout = layout;
                LogManager.StaticInfo(string.Format(
                    "[Inference] 模型输出={0} 输入尺寸={1} 解析分支={2}",
                    layout, modelSize, YoloOutputParser.DescribeBranch(rawLength)));
            }
            catch (InvalidOperationException)
            {
                // 形态本身无法识别：这是纯诊断日志，不能抢在解析层的报错前面把本帧变成日志异常。
                // 真正的原因由紧接其后的 YoloOutputParser.Parse 抛出，并显示在对应来源上。
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
        // 两个构造分支各自只设置其中一组字段：正常帧只设 Detections/Frame，故障帧只设 Error。
        public Exception       Error       { get; } = null!;
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
