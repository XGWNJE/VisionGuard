using System;
using System.Drawing;
using System.Windows.Media.Imaging;
using VisionGuard.Detector.Windows.Capture;

namespace VisionGuard.Detector.Windows.Utils
{
    internal static class BitmapSourceConverter
    {
        internal static BitmapSource Convert(Bitmap bitmap)
        {
            // GDI+ 转换与发布线程的缩放共用同一道闸门：两者同时进 GDI+ 会互等（见 GdiGate 注释）。
            return GdiGate.Run(() =>
            {
                var hBitmap = bitmap.GetHbitmap();
                try
                {
                    var source = System.Windows.Interop.Imaging.CreateBitmapSourceFromHBitmap(
                        hBitmap, IntPtr.Zero, System.Windows.Int32Rect.Empty,
                        BitmapSizeOptions.FromEmptyOptions());
                    source.Freeze();
                    return source;
                }
                finally
                {
                    NativeMethods.DeleteObject(hBitmap);
                }
            });
        }
    }
}
