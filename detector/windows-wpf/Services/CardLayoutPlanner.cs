using System;

namespace VisionGuard.Detector.Windows.Services
{
    public sealed class CardLayoutRequest
    {
        public int VisibleCount { get; set; }
        public double TotalWidth { get; set; }
        public double TotalHeight { get; set; }
        public double? UniformAspectRatio { get; set; }
    }

    public sealed class CardLayoutPlan
    {
        public int VisibleCount { get; set; }
        public double PrimaryWidth { get; set; }
        public double SecondaryWidth { get; set; }
        public double SecondaryHeight { get; set; }
        public double ContentWidth { get; set; }
        public double ContentHeight { get; set; }
        public double PictureWidth { get; set; }
        public double PictureHeight { get; set; }
        public bool IsValid => VisibleCount > 0 && PrimaryWidth > 0 && ContentHeight > 0;
    }

    /// <summary>One focused picture on the left, up to three compact previews on the right.</summary>
    public static class CardLayoutPlanner
    {
        public const int MaximumVisibleCards = 4;
        public const double MinimumPictureEdge = 240;
        public const double MinimumPrimaryWidth = 320;
        public const double MinimumSecondaryWidth = 240;
        public const double MinimumSecondaryHeight = 120;
        public const double CardSpacing = 8;
        public const double CardChromeWidth = 4;
        public const double CardChromeHeight = 4;
        public const double HostMarginWidth = 24;
        public const double HostMarginHeight = 24;
        public const double SplitterWidth = 6;
        public const double InspectorMinWidth = 320;
        public const int MinimumCardsPanelWidth = 680;
        public const double MinimumWindowWidth = 1040;
        public const double MinimumWindowHeight = 540;
        public const int MinimumInspectorPanelWidth = 320;
        public const int MaximumInspectorPanelWidth = 900;
        public const string InspectorPanelWidthSettingKey = "Layout.InspectorPanelWidth";
        public const string PreviewSourceIndexesSettingKey = "Layout.PreviewSourceIndexes";

        public static CardLayoutPlan ComputeScrollable(CardLayoutRequest request)
        {
            if (request == null) throw new ArgumentNullException(nameof(request));
            if (request.VisibleCount <= 0 || !FinitePositive(request.TotalWidth) || !FinitePositive(request.TotalHeight))
                return new CardLayoutPlan();
            int count = Math.Min(request.VisibleCount, MaximumVisibleCards);
            int secondaryCount = count - 1;
            double width = Math.Max(request.TotalWidth, count == 1 ? MinimumPrimaryWidth : MinimumPrimaryWidth + MinimumSecondaryWidth + CardSpacing);
            double height = Math.Max(request.TotalHeight, Math.Max(MinimumPictureEdge + CardChromeHeight,
                secondaryCount * MinimumSecondaryHeight + Math.Max(0, secondaryCount - 1) * CardSpacing));
            double primaryWidth = count == 1 ? width : Math.Max(MinimumPrimaryWidth, Math.Min((width - CardSpacing) * .58, width - CardSpacing - MinimumSecondaryWidth));
            var plan = new CardLayoutPlan
            {
                VisibleCount = count, PrimaryWidth = primaryWidth,
                SecondaryWidth = count == 1 ? 0 : width - primaryWidth - CardSpacing,
                SecondaryHeight = count == 1 ? 0 : (height - (secondaryCount - 1) * CardSpacing) / secondaryCount,
                ContentWidth = width, ContentHeight = height,
                PictureWidth = primaryWidth - CardChromeWidth, PictureHeight = height - CardChromeHeight
            };
            double aspect = request.UniformAspectRatio ?? 0;
            if (FinitePositive(aspect))
            {
                double pictureWidth = Math.Min(plan.PictureWidth, plan.PictureHeight * aspect);
                plan.PictureHeight = pictureWidth / aspect;
                plan.PictureWidth = pictureWidth;
            }
            return plan;
        }

        private static bool FinitePositive(double value) => value > 0 && !double.IsNaN(value) && !double.IsInfinity(value);
    }
}
