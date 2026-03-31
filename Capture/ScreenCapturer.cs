using System;
using System.Drawing;
using System.Drawing.Imaging;

namespace VisionGuard.Capture
{
    /// <summary>
    /// 通过 GDI BitBlt 捕获屏幕指定区域。
    /// 调用方负责 Dispose 返回的 Bitmap。
    /// </summary>
    public static class ScreenCapturer
    {
        /// <summary>
        /// 捕获 <paramref name="region"/> 对应的屏幕区域，返回新 Bitmap。
        /// 调用方必须 Dispose 返回值。
        /// </summary>
        public static Bitmap CaptureRegion(Rectangle region)
        {
            IntPtr desktop  = IntPtr.Zero;
            IntPtr screenDC = IntPtr.Zero;
            IntPtr memDC    = IntPtr.Zero;
            IntPtr hBitmap  = IntPtr.Zero;
            IntPtr oldBmp   = IntPtr.Zero;

            try
            {
                desktop  = NativeMethods.GetDesktopWindow();
                screenDC = NativeMethods.GetDC(desktop);

                memDC   = NativeMethods.CreateCompatibleDC(screenDC);
                hBitmap = NativeMethods.CreateCompatibleBitmap(screenDC, region.Width, region.Height);
                oldBmp  = NativeMethods.SelectObject(memDC, hBitmap);

                NativeMethods.BitBlt(
                    memDC, 0, 0, region.Width, region.Height,
                    screenDC, region.X, region.Y,
                    NativeMethods.SRCCOPY);

                // 先包装成托管 Bitmap（内部复制像素），再释放 HBITMAP
                Bitmap result = Image.FromHbitmap(hBitmap);

                NativeMethods.SelectObject(memDC, oldBmp);
                return result;
            }
            finally
            {
                if (hBitmap  != IntPtr.Zero) NativeMethods.DeleteObject(hBitmap);
                if (memDC    != IntPtr.Zero) NativeMethods.DeleteDC(memDC);
                if (screenDC != IntPtr.Zero) NativeMethods.ReleaseDC(desktop, screenDC);
            }
        }

        /// <summary>
        /// 将 Bitmap 缩放到目标尺寸（双线性插值）。
        /// 调用方负责 Dispose 返回值。
        /// </summary>
        public static Bitmap Resize(Bitmap source, int width, int height)
        {
            var result = new Bitmap(width, height, PixelFormat.Format24bppRgb);
            using (Graphics g = Graphics.FromImage(result))
            {
                g.InterpolationMode  = System.Drawing.Drawing2D.InterpolationMode.Bilinear;
                g.PixelOffsetMode    = System.Drawing.Drawing2D.PixelOffsetMode.Half;
                g.DrawImage(source, 0, 0, width, height);
            }
            return result;
        }
    }
}
