using System;
using System.Windows;
using System.Windows.Controls;

namespace VisionGuard.Views
{
    /// <summary>
    /// 「全局来源」的编号卡片面板：按可用空间求列数与行数，把卡片**铺满整个容器**
    /// （最后一行不满时按整行宽度均分拉伸），而不是固定尺寸靠左堆出一片空白。
    ///
    /// 编号卡片只显示编号、名称与状态，没有画面，所以可以压得比预览卡片小得多；
    /// 窗口最小尺寸已保证 16 个来源在任何允许的窗口比例下都排得下。
    /// </summary>
    public sealed class GlobalSourcePanel : Panel
    {
        /// <summary>编号卡片之间的间距，与卡片 DataTemplate 的 Margin 无关（间距由面板精确控制）。</summary>
        public const double Spacing = 4;

        /// <summary>编号卡片的下限：再小就看不清编号与状态文字，此时宁可压行数也不继续缩。</summary>
        public const double MinimumCardWidth = 118;
        public const double MinimumCardHeight = 64;

        /// <summary>编号卡片允许的宽高比区间：只放文字，比预览卡片宽松，但太扁或太高都不好读。</summary>
        public const double MinimumAspectRatio = 1d / 1.8;
        public const double MaximumAspectRatio = 1.8;

        protected override Size MeasureOverride(Size availableSize)
        {
            bool hasWidth = !double.IsInfinity(availableSize.Width) && !double.IsNaN(availableSize.Width);
            bool hasHeight = !double.IsInfinity(availableSize.Height) && !double.IsNaN(availableSize.Height);

            foreach (UIElement child in InternalChildren)
            {
                child.Measure(new Size(hasWidth ? availableSize.Width : 0, hasHeight ? availableSize.Height : 0));
            }

            return new Size(hasWidth ? availableSize.Width : 0, hasHeight ? availableSize.Height : 0);
        }

        protected override Size ArrangeOverride(Size finalSize)
        {
            int itemCount = InternalChildren.Count;
            if (itemCount == 0) return finalSize;

            var layout = ResolveLayout(itemCount, finalSize.Width, finalSize.Height);
            double cellWidth = (finalSize.Width - (layout.Columns - 1) * Spacing) / layout.Columns;
            double cellHeight = (finalSize.Height - (layout.Rows - 1) * Spacing) / layout.Rows;
            if (cellWidth <= 0 || cellHeight <= 0) return finalSize;

            for (int index = 0; index < itemCount; index++)
            {
                UIElement child = InternalChildren[index];
                int row = index / layout.Columns;
                int column = index % layout.Columns;
                int itemsInRow = Math.Min(layout.Columns, itemCount - row * layout.Columns);
                // 最后一行不满时把那几张按整行宽度均分，容器被铺满而不是留一条空白。
                double width = itemsInRow == layout.Columns
                    ? cellWidth
                    : (finalSize.Width - (itemsInRow - 1) * Spacing) / itemsInRow;

                child.Visibility = Visibility.Visible;
                child.Arrange(new Rect(
                    column * (cellWidth + Spacing),
                    row * (cellHeight + Spacing),
                    width,
                    cellHeight));
            }

            return finalSize;
        }

        /// <summary>
        /// 枚举列数，选「格子有效面积最大」的组合：有效面积按编号卡片的比例上限裁剪，
        /// 因此宽扁容器不会被一张超宽卡片填满，而是自动增加列数。
        /// </summary>
        private static (int Columns, int Rows) ResolveLayout(int count, double width, double height)
        {
            int bestColumns = 0;
            int bestRows = 0;
            double bestScore = -1;
            for (int columns = 1; columns <= count; columns++)
            {
                int rows = (count + columns - 1) / columns;
                double cellWidth = (width - (columns - 1) * Spacing) / columns;
                double cellHeight = (height - (rows - 1) * Spacing) / rows;
                if (cellWidth < MinimumCardWidth || cellHeight < MinimumCardHeight) continue;

                double effectiveWidth = Math.Min(cellWidth, cellHeight * MaximumAspectRatio);
                double effectiveHeight = Math.Min(cellHeight, cellWidth / MinimumAspectRatio);
                double score = effectiveWidth * effectiveHeight;
                if (score > bestScore)
                {
                    bestScore = score;
                    bestColumns = columns;
                    bestRows = rows;
                }
            }

            if (bestColumns == 0)
            {
                // 可用空间连卡片下限都不到（极端窗口）：退化成最多 4 列，保证全部来源仍然可见可点。
                bestColumns = Math.Max(1, Math.Min(count, 4));
                bestRows = (count + bestColumns - 1) / bestColumns;
            }
            return (bestColumns, bestRows);
        }
    }
}
