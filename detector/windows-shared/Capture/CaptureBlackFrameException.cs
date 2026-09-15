using System;

namespace VisionGuard.Capture
{
    /// <summary>
    /// PrintWindow 只返回全黑画面时抛出的采集故障。Windows 两个检测端共用同一份实现，
    /// 保证错误文案与判定语义一致。
    /// </summary>
    public sealed class CaptureBlackFrameException : InvalidOperationException
    {
        public CaptureBlackFrameException()
            : base("疑似黑屏：目标窗口未返回可用画面，可能使用了不兼容的 GPU 加速渲染。")
        {
        }
    }
}
