// ┌─────────────────────────────────────────────────────────────┐
// │ SourcePublisher.cs                                          │
// │ 角色：推流态的采集支路——只采集，不遮罩、不预处理、不推理     │
// │ 线程：发布线程由 MediaPublishService 持有，本类不做线程调度  │
// │ 对外 API：Start/Stop/IsRunning/Status，事件 FramePublished   │
// └─────────────────────────────────────────────────────────────┘
using System;
using System.Drawing;
using VisionGuard.Detector.Windows.Capture;
using VisionGuard.Detector.Windows.Models;

namespace VisionGuard.Detector.Windows.Services
{
    /// <summary>
    /// 推流态的帧源：按来源配置采集窗口 / 屏幕选区，交给发布服务缩放编码。
    /// 与本地推理支路互斥，因此这里不施加遮罩、不做黑帧以外以外的判定，只保证采集失败能被看到。
    /// </summary>
    public sealed class SourcePublisher : IDisposable
    {
        private readonly MonitorSource _source;
        private MediaPublishService? _service;
        private bool _disposed;

        public SourcePublisher(MonitorSource source) { _source = source; }

        /// <summary>推流状态或帧进度变化；发布服务在后台线程触发，界面线程由调用方切换。</summary>
        public event EventHandler? StatusChanged;

        /// <summary>采集到的本机画面副本，供界面预览；订阅方负责 Dispose。</summary>
        public event EventHandler<PublishedFrame>? PreviewFrame;

        public PublishStatus Status => _service?.Status ?? PublishStatus.Idle;
        public string StatusDetail => _service?.StatusDetail ?? "";
        public bool IsRunning => _service?.Status == PublishStatus.Connecting || _service?.Status == PublishStatus.Streaming;
        public bool IsPublishing => _service?.Status == PublishStatus.Streaming;
        public double ActualFps => _service?.ActualFps ?? 0;
        public int TargetFps => _service?.TargetFps ?? 0;
        public long LastAcknowledgedSequence => _service?.LastAcknowledgedSequence ?? 0;
        public long DroppedFrames => _service?.DroppedFrames ?? 0;
        public bool Rejected => _service?.Rejected ?? false;

        public void Start(MonitorSource source)
        {
            if (_disposed) return;
            if (_service != null) return;
            var service = new MediaPublishService(
                Utils.AppConfig.ServerUrl, Utils.AppConfig.SessionToken, CaptureFrame, source.Config.TargetFps);
            service.StatusChanged += (_, __) => StatusChanged?.Invoke(this, EventArgs.Empty);
            _service = service;
            Utils.MediaDiagnostics.Write($"[Publish] publisher service created source={source.SourceId} service={Utils.AppConfig.ServerUrl} fps={source.Config.TargetFps}");
            service.Start();
        }

        public void Stop()
        {
            var service = _service;
            _service = null;
            if (service != null) service.Dispose();
        }

        /// <summary>按当前采集配置抓一帧；调用方（发布服务）负责 Dispose。</summary>
        private PublishedFrame? CaptureFrame()
        {
            var config = _source.Config;
            Bitmap bitmap;
            if (config.CaptureMode == CaptureMode.WindowHandle && config.TargetWindowHandle != IntPtr.Zero)
                bitmap = WindowCapturer.CaptureWindow(config.TargetWindowHandle, config.WindowSubRegion);
            else if (config.CaptureMode == CaptureMode.ScreenRegion)
                bitmap = ScreenCapturer.CaptureRegion(config.CaptureRegion);
            // 远程镜头来源不能二次推流，避免环路；这里明确失败而不是静默发黑帧。
            else throw new InvalidOperationException("远程镜头来源不能作为推流源。");
            // 推流态同样给界面一份画面：预览用副本、编码用原件，生命周期互不干扰。
            var preview = PreviewFrame;
            if (preview != null)
            {
                try { preview(this, new PublishedFrame(CloneIndependent(bitmap))); }
                catch { }
            }
            return new PublishedFrame(bitmap);
        }

        /// <summary>
        /// 预览副本必须是**独立像素缓冲**：`Bitmap.Clone()` 走 GDI+ 的 copy-on-write，会与编码线程共享同一块像素，
        /// 界面线程转 HBITMAP 与发布线程缩放同时发生时就可能互等挂死（实测栈见 GdiGate 注释）。
        /// 这里显式画到一张新位图，彻底断开共享。
        /// </summary>
        private static Bitmap CloneIndependent(Bitmap source)
        {
            return Utils.GdiGate.Run(() =>
            {
                var copy = new Bitmap(source.Width, source.Height, System.Drawing.Imaging.PixelFormat.Format24bppRgb);
                try
                {
                    using (var graphics = Graphics.FromImage(copy))
                    {
                        graphics.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.NearestNeighbor;
                        graphics.DrawImageUnscaled(source, 0, 0);
                    }
                    return copy;
                }
                catch
                {
                    copy.Dispose();
                    throw;
                }
            });
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            Stop();
        }
    }
}
