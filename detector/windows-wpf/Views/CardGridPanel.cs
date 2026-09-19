using System;
using System.Windows;
using System.Windows.Controls;
using VisionGuard.Services;

namespace VisionGuard.Views
{
    /// <summary>
    /// 卡片网格面板需要宿主（<c>MultiSourceViewModel</c>）提供的少量信息。
    ///
    /// 只暴露画面比例：网格数学由面板按实际可用尺寸自己求解，避免面板与 ViewModel
    /// 各持一份过期状态而把卡片排错位置（面板排布先于 ViewModel 的状态回写）。
    /// </summary>
    public interface ICardGridHost
    {
        /// <summary>本屏来源的画面宽高比（宽/高）；比例不一致或无画面时为 null。</summary>
        double? UniformCardAspectRatio { get; }
    }

    /// <summary>
    /// 实时预览卡片网格：排布 ItemsSource 里的卡片（最多 <see cref="CardLayoutPlanner.MaximumVisibleCards"/> 张），
    /// 单元格比例夹紧在 1:1.2 ~ 1.2:1，多余空间居中留成间隔。
    ///
    /// 这里没有分页：只有进入实时预览的来源才会进入本面板的 ItemsSource，
    /// 其余来源由「全局来源」的编号卡片展示与勾选。
    /// </summary>
    public sealed class CardGridPanel : Panel
    {
        public static readonly DependencyProperty HostProperty = DependencyProperty.Register(
            nameof(Host), typeof(ICardGridHost), typeof(CardGridPanel),
            new FrameworkPropertyMetadata(null, FrameworkPropertyMetadataOptions.AffectsArrange));

        /// <summary>
        /// 卡片区宿主（<c>MultiSourceViewModel</c>），由 XAML 显式绑定。
        ///
        /// 不沿可视树上溯取 <c>ItemsControl.DataContext</c>：面板所在的 ItemsControl 的
        /// DataContext 是窗口级的 <c>MainViewModel</c>，而 <see cref="ICardGridHost"/> 由
        /// <c>MultiSourceViewModel</c> 实现，上溯取值永远是 null——那会让画面比例长期是「未知」。
        /// </summary>
        public ICardGridHost? Host
        {
            get { return (ICardGridHost?)GetValue(HostProperty); }
            set { SetValue(HostProperty, value); }
        }

        protected override Size MeasureOverride(Size availableSize)
        {
            bool hasWidth = !double.IsInfinity(availableSize.Width) && !double.IsNaN(availableSize.Width);
            bool hasHeight = !double.IsInfinity(availableSize.Height) && !double.IsNaN(availableSize.Height);

            foreach (UIElement child in InternalChildren)
            {
                // 无限尺寸直接传下去会把卡片里的 TextBlock 全部当成单行，量出巨大的期望宽度。
                child.Measure(new Size(hasWidth ? availableSize.Width : 0, hasHeight ? availableSize.Height : 0));
            }

            return new Size(hasWidth ? availableSize.Width : 0, hasHeight ? availableSize.Height : 0);
        }

        protected override Size ArrangeOverride(Size finalSize)
        {
            int itemCount = InternalChildren.Count;
            if (itemCount == 0) return finalSize;

            var host = Host;
            double? aspectRatio = host == null ? (double?)null : host.UniformCardAspectRatio;
            var plan = CardLayoutPlanner.Compute(new CardLayoutRequest
            {
                VisibleCount = itemCount,
                TotalWidth = finalSize.Width,
                TotalHeight = finalSize.Height,
                UniformAspectRatio = aspectRatio,
            });

            if (!plan.IsValid)
            {
                // 可用空间无效（最小化、初始化早期）：保持卡片可见但不排布，下一次布局会纠正。
                foreach (UIElement child in InternalChildren)
                {
                    child.Visibility = Visibility.Visible;
                    child.Arrange(Rect.Empty);
                }
                return finalSize;
            }

            double gridWidth = plan.Columns * plan.CellWidth + (plan.Columns - 1) * CardLayoutPlanner.CardSpacing;
            double gridHeight = plan.Rows * plan.CellHeight + (plan.Rows - 1) * CardLayoutPlanner.CardSpacing;
            double offsetX = Math.Max(0, (finalSize.Width - gridWidth) / 2);
            double offsetY = Math.Max(0, (finalSize.Height - gridHeight) / 2);
            int capacity = plan.Rows * plan.Columns;

            for (int index = 0; index < itemCount; index++)
            {
                UIElement child = InternalChildren[index];
                if (index >= capacity)
                {
                    // 防御：主视图只绑定最多 4 个来源，多出来的不排布也不显示。
                    child.Visibility = Visibility.Collapsed;
                    child.Arrange(Rect.Empty);
                    continue;
                }

                int row = index / plan.Columns;
                int column = index % plan.Columns;
                child.Visibility = Visibility.Visible;
                child.Arrange(new Rect(
                    offsetX + column * (plan.CellWidth + CardLayoutPlanner.CardSpacing),
                    offsetY + row * (plan.CellHeight + CardLayoutPlanner.CardSpacing),
                    plan.CellWidth,
                    plan.CellHeight));
            }

            return finalSize;
        }
    }
}
