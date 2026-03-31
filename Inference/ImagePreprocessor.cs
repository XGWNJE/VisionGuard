using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

namespace VisionGuard.Inference
{
    /// <summary>
    /// 将 Bitmap 转换为 YOLOv5nu 所需的 float[1,3,H,W] CHW RGB 张量。
    /// </summary>
    public static class ImagePreprocessor
    {
        private const int ModelSize = 320;

        /// <summary>
        /// 将 <paramref name="source"/> 缩放并转换为 float 张量（CHW, RGB, [0,1]）。
        /// 不修改 source，不持有 source 引用。
        /// </summary>
        public static float[] ToTensor(Bitmap source)
        {
            // 缩放到 320x320，Format24bppRgb 确保字节布局固定（BGR, 无 padding 问题需注意 stride）
            using (Bitmap resized = Resize(source, ModelSize, ModelSize))
            {
                return ExtractCHW(resized);
            }
        }

        public static int[] InputShape => new[] { 1, 3, ModelSize, ModelSize };

        // ── private ─────────────────────────────────────────────────

        private static Bitmap Resize(Bitmap src, int w, int h)
        {
            var dst = new Bitmap(w, h, PixelFormat.Format24bppRgb);
            using (Graphics g = Graphics.FromImage(dst))
            {
                g.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.Bilinear;
                g.PixelOffsetMode   = System.Drawing.Drawing2D.PixelOffsetMode.Half;
                g.DrawImage(src, 0, 0, w, h);
            }
            return dst;
        }

        private static float[] ExtractCHW(Bitmap bmp)
        {
            int w = bmp.Width;
            int h = bmp.Height;

            var rect = new Rectangle(0, 0, w, h);
            BitmapData data = bmp.LockBits(rect, ImageLockMode.ReadOnly, PixelFormat.Format24bppRgb);

            int stride     = data.Stride;             // 可能有 padding，必须用 stride
            int byteCount  = stride * h;
            byte[] pixels  = new byte[byteCount];
            Marshal.Copy(data.Scan0, pixels, 0, byteCount);
            bmp.UnlockBits(data);

            // pixels 布局: 每行 stride 字节，每像素 3 字节 BGR
            float[] tensor = new float[3 * h * w];
            int planeSize  = h * w;

            for (int row = 0; row < h; row++)
            {
                int rowBase = row * stride;
                for (int col = 0; col < w; col++)
                {
                    int byteIdx = rowBase + col * 3;
                    byte b = pixels[byteIdx];
                    byte g = pixels[byteIdx + 1];
                    byte r = pixels[byteIdx + 2];

                    int pixelIdx = row * w + col;
                    tensor[pixelIdx]                  = r / 255f; // R channel
                    tensor[planeSize  + pixelIdx]     = g / 255f; // G channel
                    tensor[planeSize * 2 + pixelIdx]  = b / 255f; // B channel
                }
            }

            return tensor;
        }
    }
}
