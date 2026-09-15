// ┌─────────────────────────────────────────────────────────┐
// │ WindowCapturer.cs                                       │
// │ 角色：通过 PrintWindow API 捕获窗口内容（支持遮挡/最小化）│
// │ 线程：在 MonitorService 的 ThreadPool 回调中调用         │
// │ 依赖：NativeMethods, WindowEnumerator (获取边界)         │
// │ 对外 API：CaptureWindow(hwnd, subRegion) → Bitmap       │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

namespace VisionGuard.Capture
{
    /// <summary>
    /// 使用 PrintWindow API 捕获目标窗口内容。
    /// 遮挡时仍可捕获；部分应用最小化后会返回黑帧，此时显式报告故障，恢复后可继续。
    /// 调用方负责 Dispose 返回的 Bitmap。
    /// </summary>
    public static class WindowCapturer
    {
        private static readonly Color UnpaintedSentinel = Color.FromArgb(255, 255, 0, 255);

        /// <summary>
        /// 捕获指定窗口的内容。
        /// </summary>
        /// <param name="hwnd">目标窗口句柄</param>
        /// <param name="subRegion">
        /// 要裁剪的子区域（相对于捕获 Bitmap 的坐标系）。
        /// Rectangle.Empty 表示返回整个窗口图像。
        /// </param>
        /// <returns>捕获得到的 Bitmap，调用方负责 Dispose。</returns>
        /// <exception cref="InvalidOperationException">PrintWindow 失败时抛出。</exception>
        public static Bitmap CaptureWindow(IntPtr hwnd, Rectangle subRegion)
        {
            // 1. PrintWindow 由目标窗口在自身客户区坐标系中重绘。不能使用 DWM 的屏幕边界：
            //    DPI 不感知窗口会被 DWM 拉伸，但 PrintWindow 不会复制这层拉伸，二者尺寸会错配。
            Rectangle bounds = WindowEnumerator.GetPrintWindowBounds(hwnd);
            if (bounds.IsEmpty || bounds.Width <= 0 || bounds.Height <= 0)
                throw new InvalidOperationException("无法获取目标窗口尺寸，窗口可能已关闭。");
            if (!CaptureSizeConstraints.IsValid(bounds))
                throw new InvalidOperationException("目标窗口宽度和高度必须都大于 100 像素。");

            // 2. 创建候选 Bitmap。跨进程 GetClientRect 仍可能按调用方 DPI 返回 DWM 拉伸尺寸，
            //    因此先铺哨兵色，PrintWindow 后再裁到目标窗口真正写入的范围。
            var bitmap  = new Bitmap(bounds.Width, bounds.Height, PixelFormat.Format32bppArgb);
            using (Graphics g = Graphics.FromImage(bitmap))
            {
                g.Clear(UnpaintedSentinel);
                IntPtr hdc = g.GetHdc();
                try
                {
                    // 3. 在客户区平面请求完整内容；返回 Bitmap 不包含标题栏/边框。
                    bool ok = NativeMethods.PrintWindow(
                        hwnd,
                        hdc,
                        NativeMethods.PW_CLIENTONLY | NativeMethods.PW_RENDERFULLCONTENT);
                    if (!ok)
                    {
                        // 回退：普通客户区重绘
                        ok = NativeMethods.PrintWindow(hwnd, hdc, NativeMethods.PW_CLIENTONLY);
                    }
                    if (!ok)
                    {
                        bitmap.Dispose();
                        throw new InvalidOperationException(
                            "PrintWindow 失败，目标窗口可能不支持该捕获方式。");
                    }
                }
                finally
                {
                    g.ReleaseHdc(hdc);
                }
            }

            bitmap = NormalizePrintedBounds(bitmap);

            // 4. 裁剪子区域
            if (subRegion != Rectangle.Empty && subRegion.Width > 0 && subRegion.Height > 0)
            {
                // 确保子区域在 Bitmap 范围内
                var clipped = Rectangle.Intersect(
                    subRegion,
                    new Rectangle(0, 0, bitmap.Width, bitmap.Height));

                if (clipped.IsEmpty)
                {
                    bitmap.Dispose();
                    throw new InvalidOperationException("子区域超出窗口边界。");
                }

                if (!CaptureSizeConstraints.IsValid(clipped))
                {
                    bitmap.Dispose();
                    throw new InvalidOperationException("窗口选区宽度和高度必须都大于 100 像素。");
                }

                Bitmap cropped = bitmap.Clone(clipped, PixelFormat.Format32bppArgb);
                bitmap.Dispose();
                ThrowIfLikelyBlack(cropped);
                return cropped;
            }

            ThrowIfLikelyBlack(bitmap);
            return bitmap;
        }

        private static Bitmap NormalizePrintedBounds(Bitmap bitmap)
        {
            Rectangle painted = FindPaintedBounds(bitmap);
            if (painted.IsEmpty)
            {
                bitmap.Dispose();
                throw new CaptureBlackFrameException();
            }
            if (painted.X == 0 && painted.Y == 0 && painted.Width == bitmap.Width && painted.Height == bitmap.Height)
                return bitmap;

            Bitmap normalized = bitmap.Clone(painted, PixelFormat.Format32bppArgb);
            bitmap.Dispose();
            return normalized;
        }

        private static Rectangle FindPaintedBounds(Bitmap bitmap)
        {
            var area = new Rectangle(0, 0, bitmap.Width, bitmap.Height);
            BitmapData data = bitmap.LockBits(area, ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
            try
            {
                int stride = Math.Abs(data.Stride);
                byte[] pixels = new byte[stride * bitmap.Height];
                Marshal.Copy(data.Scan0, pixels, 0, pixels.Length);
                int minX = bitmap.Width, minY = bitmap.Height, maxX = -1, maxY = -1;
                for (int y = 0; y < bitmap.Height; y++)
                {
                    int row = data.Stride >= 0 ? y * stride : (bitmap.Height - 1 - y) * stride;
                    for (int x = 0; x < bitmap.Width; x++)
                    {
                        int i = row + x * 4;
                        if (pixels[i] == UnpaintedSentinel.B && pixels[i + 1] == UnpaintedSentinel.G
                            && pixels[i + 2] == UnpaintedSentinel.R && pixels[i + 3] == UnpaintedSentinel.A) continue;
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
                return maxX < minX || maxY < minY
                    ? Rectangle.Empty
                    : Rectangle.FromLTRB(minX, minY, maxX + 1, maxY + 1);
            }
            finally { bitmap.UnlockBits(data); }
        }

        /// <summary>
        /// 采样10个点检测是否为全黑（判断 PrintWindow 黑屏情形）。
        /// </summary>
        private static void ThrowIfLikelyBlack(Bitmap bitmap)
        {
            if (!IsLikelyBlack(bitmap)) return;
            bitmap.Dispose();
            throw new CaptureBlackFrameException();
        }

        internal static bool IsLikelyBlack(Bitmap bmp)
        {
            if (bmp.Width == 0 || bmp.Height == 0) return true;

            int stepX = Math.Max(1, bmp.Width  / 5);
            int stepY = Math.Max(1, bmp.Height / 2);

            for (int x = stepX; x < bmp.Width;  x += stepX)
            for (int y = stepY; y < bmp.Height; y += stepY)
            {
                Color c = bmp.GetPixel(x, y);
                if (c.R > 5 || c.G > 5 || c.B > 5)
                    return false;
            }
            return true;
        }
    }
}
