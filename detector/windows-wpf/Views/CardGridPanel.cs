using System;
using System.Windows;
using System.Windows.Controls;
using VisionGuard.Detector.Windows.Services;

namespace VisionGuard.Detector.Windows.Views
{
    public interface ICardGridHost { double? UniformCardAspectRatio { get; } }

    public sealed class CardGridPanel : Panel
    {
        public static readonly DependencyProperty HostProperty = DependencyProperty.Register(nameof(Host), typeof(ICardGridHost), typeof(CardGridPanel), new FrameworkPropertyMetadata(null, FrameworkPropertyMetadataOptions.AffectsMeasure));
        public ICardGridHost? Host { get => (ICardGridHost?)GetValue(HostProperty); set => SetValue(HostProperty, value); }
        public static readonly DependencyProperty ViewportWidthProperty = DependencyProperty.Register(nameof(ViewportWidth), typeof(double), typeof(CardGridPanel), new FrameworkPropertyMetadata(0d, FrameworkPropertyMetadataOptions.AffectsMeasure));
        public static readonly DependencyProperty ViewportHeightProperty = DependencyProperty.Register(nameof(ViewportHeight), typeof(double), typeof(CardGridPanel), new FrameworkPropertyMetadata(0d, FrameworkPropertyMetadataOptions.AffectsMeasure));
        public double ViewportWidth { get => (double)GetValue(ViewportWidthProperty); set => SetValue(ViewportWidthProperty, value); }
        public double ViewportHeight { get => (double)GetValue(ViewportHeightProperty); set => SetValue(ViewportHeightProperty, value); }
        public static readonly DependencyProperty FocusedIndexProperty = DependencyProperty.Register(nameof(FocusedIndex), typeof(int), typeof(CardGridPanel), new FrameworkPropertyMetadata(0, FrameworkPropertyMetadataOptions.AffectsMeasure));
        public int FocusedIndex { get => (int)GetValue(FocusedIndexProperty); set => SetValue(FocusedIndexProperty, value); }
        public static readonly DependencyProperty IsPrimaryCardProperty = DependencyProperty.RegisterAttached("IsPrimaryCard", typeof(bool), typeof(CardGridPanel), new FrameworkPropertyMetadata(false));
        public static bool GetIsPrimaryCard(DependencyObject element) => (bool)element.GetValue(IsPrimaryCardProperty);
        public static void SetIsPrimaryCard(DependencyObject element, bool value) => element.SetValue(IsPrimaryCardProperty, value);

        private CardLayoutPlan CreatePlan() => CardLayoutPlanner.ComputeScrollable(new CardLayoutRequest
        { VisibleCount = InternalChildren.Count, TotalWidth = ViewportWidth, TotalHeight = ViewportHeight, UniformAspectRatio = Host?.UniformCardAspectRatio });
        private int PrimaryIndex => Math.Max(0, Math.Min(FocusedIndex, InternalChildren.Count - 1));

        protected override Size MeasureOverride(Size availableSize)
        {
            var plan = CreatePlan();
            if (!plan.IsValid) return new Size();
            for (int index = 0; index < InternalChildren.Count; index++)
            {
                bool primary = index == PrimaryIndex;
                SetIsPrimaryCard(InternalChildren[index], primary);
                InternalChildren[index].Measure(new Size(primary ? plan.PrimaryWidth : plan.SecondaryWidth, primary ? plan.ContentHeight : plan.SecondaryHeight));
            }
            return new Size(plan.ContentWidth, plan.ContentHeight);
        }

        protected override Size ArrangeOverride(Size finalSize)
        {
            var plan = CreatePlan();
            if (!plan.IsValid) return finalSize;
            int secondary = 0;
            for (int index = 0; index < InternalChildren.Count; index++)
            {
                var child = InternalChildren[index];
                bool visible = index < plan.VisibleCount;
                child.Visibility = visible ? Visibility.Visible : Visibility.Collapsed;
                if (!visible) continue;
                child.Arrange(index == PrimaryIndex
                    ? new Rect(0, 0, plan.PrimaryWidth, plan.ContentHeight)
                    : new Rect(plan.PrimaryWidth + CardLayoutPlanner.CardSpacing, secondary++ * (plan.SecondaryHeight + CardLayoutPlanner.CardSpacing), plan.SecondaryWidth, plan.SecondaryHeight));
            }
            return finalSize;
        }
    }
}
