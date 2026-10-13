// ┌─────────────────────────────────────────────────────────────────────┐
// │ GdiGate.cs                                                          │
// │ 角色：把短时 GDI+ 位图操作串行化，防止跨线程互等                     │
// │ 线程：任意线程可调用；闸门内不得等待其它线程（不做 UI 派发、不加锁  │
// │       顺序依赖），否则会把普通争用变成死锁                            │
// │ 对外 API：GdiGate.Run(Func<T>) / GdiGate.Run(Action)                 │
// └─────────────────────────────────────────────────────────────────────┘
using System;

namespace VisionGuard.Detector.Windows.Utils
{
    /// <summary>
    /// GDI+ 位图操作的全局串行闸门。
    ///
    /// 背景：`Bitmap.GetHbitmap`、`Graphics.DrawImage`、`Bitmap.Clone` 这些 GDI+ 操作内部会取全局锁与位图的
    /// pipe 锁。推流态下界面线程把采集副本转成 `BitmapSource`、发布线程同时把原图缩放到契约尺寸时，两条线程
    /// 可能互等：实测挂起栈为界面线程卡在 `GdipCreateHBITMAPFromBitmap` 等临界区、发布线程卡在
    /// `GpGraphics::DrawImage → GpBitmap::PipeUnlockBits`，界面不再泵消息、推流一并停住
    /// （见[验证记录](../../../../docs/90-验证记录.md)）。
    ///
    /// 闸门只包住这些毫秒级操作，内部不等待其它线程，因此自身不会死锁；`lock` 可重入，
    /// 嵌套调用（如 EncodeJpeg 内部再调 ScaleToContract）安全。
    /// </summary>
    internal static class GdiGate
    {
        private static readonly object Sync = new object();

        internal static T Run<T>(Func<T> action)
        {
            lock (Sync) { return action(); }
        }

        internal static void Run(Action action)
        {
            lock (Sync) { action(); }
        }
    }
}
