using System.Drawing;

namespace VisionGuard.Capture
{
    /// <summary>采集目标的统一像素尺寸边界。</summary>
    public static class CaptureSizeConstraints
    {
        public const int MinimumDimensionExclusive = 100;

        public static bool IsValid(int width, int height)
            => width > MinimumDimensionExclusive && height > MinimumDimensionExclusive;

        public static bool IsValid(Rectangle rectangle)
            => IsValid(rectangle.Width, rectangle.Height);

        /// <summary>
        /// 将 WPF 画布中的 DIP 选区映射为实际采集像素。
        /// 拖拽提示和提交校验必须共用此方法，避免高 DPI 下显示尺寸与校验尺寸不一致。
        /// </summary>
        public static Rectangle MapToCapturePixels(
            double left,
            double top,
            double width,
            double height,
            double scaleX,
            double scaleY)
        {
            if (!double.IsFinite(scaleX) || !double.IsFinite(scaleY) || scaleX <= 0 || scaleY <= 0)
                return Rectangle.Empty;

            return new Rectangle(
                (int)(left * scaleX),
                (int)(top * scaleY),
                (int)(width * scaleX),
                (int)(height * scaleY));
        }
    }
}
