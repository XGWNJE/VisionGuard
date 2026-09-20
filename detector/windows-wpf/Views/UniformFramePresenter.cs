using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace VisionGuard.Views
{
    /// <summary>
    /// 在固定画面区内承载一张原始像素坐标的画布。
    ///
    /// 普通 <see cref="Viewbox"/> 会把子画布的自然尺寸参与 Measure；当选区特别高或特别宽时，
    /// 这会反向撑大卡片的星号行并挤压底部按钮。本容器仍按 Uniform 等比显示，
    /// 但 Measure 永远不把原始帧的尺寸上报给父布局，卡片尺寸只由 CardGridPanel 决定。
    /// </summary>
    public sealed class UniformFramePresenter : Decorator
    {
        private readonly MatrixTransform _transform = new MatrixTransform();

        public UniformFramePresenter()
        {
            ClipToBounds = true;
        }

        protected override Size MeasureOverride(Size availableSize)
        {
            if (Child != null)
            {
                // 子画布需要按原始像素坐标量测，检测框 Canvas 才能和图像共用同一坐标系；
                // 但它的自然大小不能成为来源卡片的最小尺寸。
                Child.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
            }

            return Size.Empty;
        }

        protected override Size ArrangeOverride(Size finalSize)
        {
            if (Child == null) return finalSize;

            Size natural = Child.DesiredSize;
            if (!IsUsable(natural.Width) || !IsUsable(natural.Height)
                || !IsUsable(finalSize.Width) || !IsUsable(finalSize.Height))
            {
                // Rect.Empty 会让 WPF 在 ArrangeCore 内部尝试修改 Size.Empty，启动时尚无帧的
                // 卡片因此抛出「无法在空大小上修改此属性」。普通的 0×0 Rect 才是合法的收起布局。
                Child.Arrange(new Rect(0d, 0d, 0d, 0d));
                return finalSize;
            }

            double scale = Math.Min(finalSize.Width / natural.Width, finalSize.Height / natural.Height);
            if (!IsUsable(scale))
            {
                Child.Arrange(new Rect(0d, 0d, 0d, 0d));
                return finalSize;
            }

            double offsetX = (finalSize.Width - natural.Width * scale) / 2d;
            double offsetY = (finalSize.Height - natural.Height * scale) / 2d;
            _transform.Matrix = new Matrix(scale, 0d, 0d, scale, offsetX, offsetY);
            Child.RenderTransform = _transform;
            Child.RenderTransformOrigin = new Point(0d, 0d);
            Child.Arrange(new Rect(0d, 0d, natural.Width, natural.Height));
            return finalSize;
        }

        private static bool IsUsable(double value)
            => !double.IsNaN(value) && !double.IsInfinity(value) && value > 0d;
    }
}
