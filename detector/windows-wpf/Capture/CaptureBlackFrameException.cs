using System;

namespace VisionGuard.Capture
{
    public sealed class CaptureBlackFrameException : InvalidOperationException
    {
        public CaptureBlackFrameException()
            : base("疑似黑屏：目标窗口未返回可用画面，可能使用了不兼容的 GPU 加速渲染。")
        {
        }
    }
}
