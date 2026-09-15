using System.Drawing;

namespace VisionGuard.Capture
{
    /// <summary>
    /// 采集目标的统一像素尺寸边界。Windows 两个检测端共用同一份实现，
    /// 避免两端各自维护出不同的下限或缺失 DPI 映射。
    /// </summary>
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
        /// WinForms 端不使用该方法（它不做多 DPI 适配）。
        /// </summary>
        public static Rectangle MapToCapturePixels(
            double left,
            double top,
            double width,
            double height,
            double scaleX,
            double scaleY)
        {
            if (!IsUsableScale(scaleX) || !IsUsableScale(scaleY))
                return Rectangle.Empty;

            return new Rectangle(
                (int)(left * scaleX),
                (int)(top * scaleY),
                (int)(width * scaleX),
                (int)(height * scaleY));
        }

        // .NET Framework 4.7.2 没有 double.IsFinite，两端共用这份实现时只能用基础判断。
        private static bool IsUsableScale(double scale)
            => !double.IsNaN(scale) && !double.IsInfinity(scale) && scale > 0d;
    }
}
