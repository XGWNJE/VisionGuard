using System;

namespace VisionGuard.Services
{
    /// <summary>
    /// 卡片网格的求解输入。纯数据，不依赖 WPF，因此可以在无界面宿主里断言布局数学。
    /// </summary>
    public sealed class CardLayoutRequest
    {
        /// <summary>
        /// 要排布的卡片数 = 进入实时预览的来源数（1 到 <see cref="CardLayoutPlanner.MaximumVisibleCards"/>）。
        /// 不在预览里的来源不占卡片位，也不会因为放不下被裁掉。
        /// </summary>
        public int VisibleCount { get; set; }

        /// <summary>卡片区可用宽度（DIP，已扣除检查区与可拖分隔条）。</summary>
        public double TotalWidth { get; set; }

        /// <summary>
        /// 网格面板自身的可用高度（DIP，不含底部工具条）。
        /// 视图上报的是 ItemsControl 的 ActualHeight：工具条那一行已被 Grid 扣掉，所以这里拿到的是真实可用高度。
        /// </summary>
        public double TotalHeight { get; set; }

        /// <summary>
        /// 本屏画面的宽高比（宽/高）。比例不一致或尚无画面时为 null，此时按画面区填满估算。
        /// </summary>
        public double? UniformAspectRatio { get; set; }
    }

    /// <summary>求解结果：网格行列、单元格尺寸，以及画面在卡片内实际占用的尺寸。</summary>
    public sealed class CardLayoutPlan
    {
        public int Rows { get; set; }
        public int Columns { get; set; }

        /// <summary>单元格尺寸（= 卡片 DataTemplate 根 Border 外框 + 两侧 3px Margin）。</summary>
        public double CellWidth { get; set; }
        public double CellHeight { get; set; }

        /// <summary>卡片内留给画面的区域（已扣除标题行、操作行与内边距）。</summary>
        public double PictureAreaWidth { get; set; }
        public double PictureAreaHeight { get; set; }

        /// <summary>画面等比缩放后实际占用的尺寸。</summary>
        public double PictureWidth { get; set; }
        public double PictureHeight { get; set; }

        /// <summary>求解时采用的画面宽高比；0 表示比例未知或不一致。</summary>
        public double TargetAspectRatio { get; set; }

        /// <summary>画面较短的一边，是「最短边不低于 320」这条要求的判定值。</summary>
        public double MinimumPictureEdge { get { return Math.Min(PictureWidth, PictureHeight); } }

        /// <summary>画面最短边是否达到 <see cref="CardLayoutPlanner.MinimumPictureEdge"/>。</summary>
        public bool PictureMeetsMinimumEdge
        {
            get { return MinimumPictureEdge >= CardLayoutPlanner.MinimumPictureEdge - 0.5; }
        }

        /// <summary>画面占画面区的面积比（1 = 画面正好填满画面区）。</summary>
        public double PictureFillRatio
        {
            get
            {
                double area = PictureAreaWidth * PictureAreaHeight;
                return area <= 0 ? 0 : PictureWidth * PictureHeight / area;
            }
        }

        /// <summary>可用空间无效（窗口最小化、初始化早期）时为 false，调用方应跳过本次刷新。</summary>
        public bool IsValid { get { return CellWidth > 0 && CellHeight > 0; } }
    }

    /// <summary>
    /// 实时预览卡片区的求解器：给定「要预览几张」和可用像素，求网格行列、单元格尺寸与画面尺寸。
    ///
    /// 2026-09-20 的布局契约（由使用侧确认）：
    /// ① 卡片必须是接近方形的，宽高比限制在 1:1.2 ~ 1.2:1（最多偏 20%）；超出时把格子收窄、
    ///    多出来的空间留成间隔——宁可卡片之间有空白，也不把卡片拉成宽扁条。
    /// ② 卡片内**画面区域**的较短边不得低于 320 DIP；这条由窗口最小尺寸兜底（见
    ///    <see cref="MinimumWindowWidth"/> / <see cref="MinimumWindowHeight"/> 的推导），
    ///    求解器负责把实际值算出来并给出 <see cref="CardLayoutPlan.PictureMeetsMinimumEdge"/>。
    /// ③ 不再分页：超出 4 个的来源不在这一屏排布，由「全局来源」的编号卡片展示与勾选。
    /// ④ 网格是确定性的：1 张 = 1×1、2 张 = 1×2 或 2×1（按可用空间取更宽的一边）、3–4 张 = 2×2。
    /// </summary>
    public static class CardLayoutPlanner
    {
        /// <summary>同时进入实时预览的最大来源数；其余的照常采集、推理与报警，只是不占预览位。</summary>
        public const int MaximumVisibleCards = 4;

        /// <summary>
        /// 卡片内画面区域较短边的最小值（DIP）。320 是「看检测框不至于糊」的下限，
        /// 由窗口最小尺寸保证；小于它时求解器仍然给出布局，只是 <see cref="CardLayoutPlan.PictureMeetsMinimumEdge"/> 为 false。
        /// </summary>
        public const double MinimumPictureEdge = 320;

        /// <summary>
        /// 卡片（单元格）宽高比区间：接近方形，长边不超过短边的 1.2 倍。
        /// 超出区间时收窄格子并把空间留成间隔，而不是拉伸卡片。
        /// </summary>
        public const double MinimumCardAspectRatio = 1d / 1.2;
        public const double MaximumCardAspectRatio = 1.2;

        /// <summary>相邻卡片之间的间距，必须与卡片 DataTemplate 的 Margin（2+2）一致。</summary>
        public const double CardSpacing = 4;

        /// <summary>卡片自身的水平占位：两侧 Margin 2+2 与内边距 3+3。单元格宽减去它就是卡片内容宽度。</summary>
        public const double CardChromeWidth = 4 + 6;

        /// <summary>
        /// 卡片自身的垂直占位：两侧 Margin 2+2、内边距 3+3、标题行 26 + 2、
        /// 操作行 28 + 2（与 MainWindow 的卡片 DataTemplate 逐项对应）。
        ///
        /// 2026-09-20 第二次压缩：标题行 36→26、操作行 40→28、内边距 7→3、外边距 3→2，
        /// 合计从 103 降到 68——把省下来的 35 DIP 全部交给画面区，让 4 张同屏也能满足
        /// 「画面区短边 ≥320」（使用侧要求：压缩卡片内 UI 与信息量来换画面空间）。
        /// 改这里必须同步 MainWindow 的卡片 DataTemplate，否则画面区会被算错。
        /// </summary>
        public const double CardChromeHeight = 4 + 6 + (26 + 2) + (28 + 2);

        /// <summary>卡片区在页面里的外边距，与 MainWindow 中承载面板的 Grid Margin 一致。</summary>
        public const double HostMarginWidth = 10;
        public const double HostMarginHeight = 6;

        /// <summary>可拖分隔条宽度。</summary>
        public const double SplitterWidth = 6;

        /// <summary>检查区最小宽度，与 MainWindow 中该列的 MinWidth 一致。</summary>
        public const double InspectorMinWidth = 320;

        /// <summary>
        /// 卡片区（左侧预览区）宽度下限，与 MainWindow 中该列的 MinWidth 一致。
        /// 2 列画面区各需 330（320 + 水平占位 10）；卡片区本身是自适应列，宽度取「窗口 − 检查区 − 分隔条」。
        /// </summary>
        public const int MinimumCardsPanelWidth = 680;

        /// <summary>
        /// 窗口最小尺寸（DIP），与 MainWindow 的 MinWidth/MinHeight 一致。
        ///
        /// 推导（4 张 2×2、1:1 画面，垂直占位 68）：
        /// 高度：画面区 320 + 垂直占位 68 = 单元格 388 → 两行 780 → 加卡片区外边距 6、工具条 44、
        ///       窗口标题栏约 45 → 875，取 880；此时 4 张同屏的画面短边约 322，1 张约 717、2 张约 420。
        /// 宽度：画面区 330 + 水平占位 10 → 两列 664 → 加外边距 7、分隔条 6、检查区最窄 320、窗口边框约 16
        ///       → 1013，取 1200 给卡片区留余量。
        /// 本机屏幕工作区为 892 DIP（3008×1784 物理 @200%），880 放得下。
        /// </summary>
        public const double MinimumWindowWidth = 1200;
        public const double MinimumWindowHeight = 880;

        /// <summary>
        /// 右侧检查区宽度的默认值与上下限。默认取最窄：卡片区因此占满其余空间，
        /// 用户拖拽分隔条后按拖拽结果持久化。
        /// </summary>
        public const int MinimumInspectorPanelWidth = 320;
        public const int MaximumInspectorPanelWidth = 900;

        /// <summary>设置键：右侧检查区宽度，由可拖分隔条写入。</summary>
        public const string InspectorPanelWidthSettingKey = "Layout.InspectorPanelWidth";

        /// <summary>设置键：进入实时预览的来源槽位索引（最多 <see cref="MaximumVisibleCards"/> 个，逗号分隔）。</summary>
        public const string PreviewSourceIndexesSettingKey = "Layout.PreviewSourceIndexes";

        public static CardLayoutPlan Compute(CardLayoutRequest request)
        {
            if (request == null) throw new ArgumentNullException(nameof(request));

            var plan = new CardLayoutPlan();
            int count = request.VisibleCount;
            if (count <= 0) return plan;
            if (count > MaximumVisibleCards) count = MaximumVisibleCards;

            double usableWidth = request.TotalWidth;
            double usableHeight = request.TotalHeight;
            if (usableWidth <= 0 || usableHeight <= 0) return plan;

            int rows = 1, columns = 1;
            if (count == 2) { if (usableWidth >= usableHeight) columns = 2; else rows = 2; }
            else if (count >= 3) { rows = 2; columns = 2; }

            double cellWidth = (usableWidth - (columns - 1) * CardSpacing) / columns;
            double cellHeight = (usableHeight - (rows - 1) * CardSpacing) / rows;
            if (cellWidth <= 0 || cellHeight <= 0) return plan;

            // 卡片比例夹紧：长边不得超过短边的 1.2 倍；太扁收宽度、太瘦高收高度，
            // 收掉的尺寸由面板居中留成间隔。窗口再极端也不会出现宽扁条或细高条。
            ClampCardAspect(ref cellWidth, ref cellHeight);

            plan.Rows = rows;
            plan.Columns = columns;
            plan.CellWidth = cellWidth;
            plan.CellHeight = cellHeight;
            plan.PictureAreaWidth = Math.Max(1, cellWidth - CardChromeWidth);
            plan.PictureAreaHeight = Math.Max(1, cellHeight - CardChromeHeight);

            double aspectRatio = ResolveAspectRatio(request.UniformAspectRatio);
            plan.TargetAspectRatio = aspectRatio;
            if (aspectRatio > 0)
            {
                // 画面等比缩放：只被较紧的一边限制，另一边就是画面区里的留白。
                plan.PictureWidth = Math.Min(plan.PictureAreaWidth, plan.PictureAreaHeight * aspectRatio);
                plan.PictureHeight = Math.Min(plan.PictureAreaHeight, plan.PictureAreaWidth / aspectRatio);
            }
            else
            {
                // 比例未知（来源还没出画面或本屏比例不一致）：按画面区填满估算，卡片大小不受影响。
                plan.PictureWidth = plan.PictureAreaWidth;
                plan.PictureHeight = plan.PictureAreaHeight;
            }

            return plan;
        }

        /// <summary>
        /// 把单元格夹紧到 [<see cref="MinimumCardAspectRatio"/>, <see cref="MaximumCardAspectRatio"/>]：
        /// 太扁收宽度、太瘦高收高度，多出来的空间由面板居中留成间隔。
        /// 只改单元格不改画面：画面由 Viewbox 等比缩放，收窄后自然缩小居中。
        /// </summary>
        private static void ClampCardAspect(ref double width, ref double height)
        {
            if (width <= 0 || height <= 0) return;
            double ratio = width / height;
            if (ratio > MaximumCardAspectRatio) width = height * MaximumCardAspectRatio;
            else if (ratio < MinimumCardAspectRatio) height = width / MinimumCardAspectRatio;
        }

        /// <summary>画面比例异常值（NaN/Infinity/越界）一律按边界值拟合；未提供时返回 0 表示未知。</summary>
        private static double ResolveAspectRatio(double? aspectRatio)
        {
            if (!aspectRatio.HasValue) return 0;
            double value = aspectRatio.Value;
            if (double.IsNaN(value) || double.IsInfinity(value) || value <= 0) return 0;
            return value;
        }
    }
}
