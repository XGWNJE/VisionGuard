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
        }
    }
}
