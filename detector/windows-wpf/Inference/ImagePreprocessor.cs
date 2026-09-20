// ┌─────────────────────────────────────────────────────────┐
// │ ImagePreprocessor.cs                                    │
// │ 角色：将 Bitmap 缩放并转换为 YOLOv5 输入张量            │
// │ 线程：在 MonitorService 的 ThreadPool 回调中调用         │
// │ 依赖：无                                                │
// │ 对外 API：ToTensor(), InputShape, ModelInputSize         │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

namespace VisionGuard.Inference
{
    /// <summary>
    /// 原始帧与正方形模型输入之间的几何关系。
    /// 画面先等比缩放、再居中放入黑色画布；解析结果必须使用这组数据回到原帧坐标。
    /// </summary>
    public sealed class LetterboxTransform
    {
        public int SourceWidth { get; }
        public int SourceHeight { get; }
        public int InputSize { get; }
        public int ResizedWidth { get; }
        public int ResizedHeight { get; }
        public int PadLeft { get; }
        public int PadTop { get; }

        /// <summary>模型坐标到原始帧坐标的水平缩放倒数所依赖的实际像素比例。</summary>
        public float ScaleX { get; }

        /// <summary>模型坐标到原始帧坐标的垂直缩放倒数所依赖的实际像素比例。</summary>
        public float ScaleY { get; }

        /// <summary>实际画面在模型正方形输入里占用的面积比例。</summary>
        public double EffectiveAreaRatio => (double)ResizedWidth * ResizedHeight / (InputSize * (double)InputSize);

        public LetterboxTransform(
            int sourceWidth,
            int sourceHeight,
            int inputSize,
            int resizedWidth,
            int resizedHeight,
            int padLeft,
            int padTop)
        {
            if (sourceWidth <= 0) throw new ArgumentOutOfRangeException(nameof(sourceWidth));
            if (sourceHeight <= 0) throw new ArgumentOutOfRangeException(nameof(sourceHeight));
            if (inputSize <= 0) throw new ArgumentOutOfRangeException(nameof(inputSize));
            if (resizedWidth <= 0 || resizedWidth > inputSize) throw new ArgumentOutOfRangeException(nameof(resizedWidth));
            if (resizedHeight <= 0 || resizedHeight > inputSize) throw new ArgumentOutOfRangeException(nameof(resizedHeight));
            if (padLeft < 0 || padTop < 0) throw new ArgumentOutOfRangeException(nameof(padLeft));

            SourceWidth = sourceWidth;
            SourceHeight = sourceHeight;
            InputSize = inputSize;
            ResizedWidth = resizedWidth;
            ResizedHeight = resizedHeight;
            PadLeft = padLeft;
            PadTop = padTop;
            ScaleX = resizedWidth / (float)sourceWidth;
            ScaleY = resizedHeight / (float)sourceHeight;
        }

        /// <summary>根据来源尺寸计算居中等比留白的输入几何。</summary>
        public static LetterboxTransform Create(int sourceWidth, int sourceHeight, int inputSize)
        {
            if (sourceWidth <= 0 || sourceHeight <= 0)
                throw new ArgumentOutOfRangeException("sourceWidth", "原始画面宽高必须大于 0。");
            if (inputSize <= 0) throw new ArgumentOutOfRangeException(nameof(inputSize));

            double scale = Math.Min(inputSize / (double)sourceWidth, inputSize / (double)sourceHeight);
            int resizedWidth = Math.Min(inputSize, Math.Max(1, (int)Math.Round(sourceWidth * scale, MidpointRounding.AwayFromZero)));
            int resizedHeight = Math.Min(inputSize, Math.Max(1, (int)Math.Round(sourceHeight * scale, MidpointRounding.AwayFromZero)));
            return new LetterboxTransform(
                sourceWidth,
                sourceHeight,
                inputSize,
                resizedWidth,
                resizedHeight,
                (inputSize - resizedWidth) / 2,
                (inputSize - resizedHeight) / 2);
        }

        /// <summary>
        /// 兼容旧调用方的“拉伸输入”变换。生产链路不使用它；它仅使旧解析验证在 1:1
        /// 或显式拉伸场景下保持可读。
        /// </summary>
        public static LetterboxTransform CreateStretched(int sourceWidth, int sourceHeight, int inputSize)
            => new LetterboxTransform(sourceWidth, sourceHeight, inputSize, inputSize, inputSize, 0, 0);

        /// <summary>把模型输入坐标中的检测框还原并裁剪到原始帧；完全落在黑边时返回 Empty。</summary>
        public RectangleF MapAndClipToSource(float left, float top, float width, float height)
        {
            if (width <= 0f || height <= 0f) return RectangleF.Empty;

            float mappedLeft = (left - PadLeft) / ScaleX;
            float mappedTop = (top - PadTop) / ScaleY;
            float mappedRight = (left + width - PadLeft) / ScaleX;
            float mappedBottom = (top + height - PadTop) / ScaleY;

            mappedLeft = Math.Max(0f, Math.Min(SourceWidth, mappedLeft));
            mappedTop = Math.Max(0f, Math.Min(SourceHeight, mappedTop));
            mappedRight = Math.Max(0f, Math.Min(SourceWidth, mappedRight));
            mappedBottom = Math.Max(0f, Math.Min(SourceHeight, mappedBottom));

            return mappedRight > mappedLeft && mappedBottom > mappedTop
                ? RectangleF.FromLTRB(mappedLeft, mappedTop, mappedRight, mappedBottom)
                : RectangleF.Empty;
        }
    }

    /// <summary>预处理生成的张量及其坐标变换；两者必须成对传给推理与解析。</summary>
    public sealed class PreprocessedImage
    {
        public float[] Tensor { get; }
        public LetterboxTransform Transform { get; }

        public PreprocessedImage(float[] tensor, LetterboxTransform transform)
        {
            Tensor = tensor ?? throw new ArgumentNullException(nameof(tensor));
            Transform = transform ?? throw new ArgumentNullException(nameof(transform));
        }
    }

    /// <summary>
    /// 将 Bitmap 转换为 YOLOv5nu 所需的 float[1,3,H,W] CHW RGB 张量。
    /// </summary>
    public static class ImagePreprocessor
    {
        /// <summary>
        /// 将 <paramref name="source"/> 缩放并转换为 float 张量（CHW, RGB, [0,1]）。
        /// 不修改 source，不持有 source 引用。
        /// <param name="modelSize">模型输入尺寸（宽=高），从 ONNX 模型 input shape 提取</param>
        /// </summary>
        public static PreprocessedImage Prepare(Bitmap source, int modelSize)
        {
            if (source == null) throw new ArgumentNullException(nameof(source));
            var transform = LetterboxTransform.Create(source.Width, source.Height, modelSize);
            using (Bitmap resized = ResizeAndLetterbox(source, transform))
            {
                return new PreprocessedImage(ExtractCHW(resized), transform);
            }
        }

        /// <summary>兼容旧调用方；生产代码应使用 <see cref="Prepare"/> 以保留坐标变换。</summary>
        public static float[] ToTensor(Bitmap source, int modelSize) => Prepare(source, modelSize).Tensor;

        public static int[] InputShape(int modelSize) => new[] { 1, 3, modelSize, modelSize };

        // ── private ─────────────────────────────────────────────────

        private static Bitmap ResizeAndLetterbox(Bitmap src, LetterboxTransform transform)
        {
            var dst = new Bitmap(transform.InputSize, transform.InputSize, PixelFormat.Format24bppRgb);
            using (Graphics g = Graphics.FromImage(dst))
            {
                g.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.Bilinear;
                g.PixelOffsetMode   = System.Drawing.Drawing2D.PixelOffsetMode.Half;
                g.Clear(Color.Black);
                g.DrawImage(src, new Rectangle(transform.PadLeft, transform.PadTop, transform.ResizedWidth, transform.ResizedHeight));
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
