using System;
using System.Windows;
using System.Windows.Documents;
using System.Windows.Media;

namespace VisionGuard.Detector.Windows.Icons
{
    // The geometry is generated from official SVGs; the complete 24-unit viewport is scaled.
    public sealed class LucideIcon : FrameworkElement
    {
        public static readonly DependencyProperty KindProperty = DependencyProperty.Register(
            nameof(Kind), typeof(LucideGlyph), typeof(LucideIcon),
            new FrameworkPropertyMetadata(LucideGlyph.Plus, FrameworkPropertyMetadataOptions.AffectsRender));
        public static readonly DependencyProperty ForegroundProperty = TextElement.ForegroundProperty.AddOwner(
            typeof(LucideIcon), new FrameworkPropertyMetadata(Brushes.Black,
                FrameworkPropertyMetadataOptions.Inherits | FrameworkPropertyMetadataOptions.AffectsRender));

        public LucideGlyph Kind { get => (LucideGlyph)GetValue(KindProperty); set => SetValue(KindProperty, value); }
        public Brush Foreground { get => (Brush)GetValue(ForegroundProperty); set => SetValue(ForegroundProperty, value); }

        public LucideIcon() { IsHitTestVisible = false; Focusable = false; }

        protected override Size MeasureOverride(Size availableSize) => new Size(24, 24);

        protected override void OnRender(DrawingContext drawingContext)
        {
            base.OnRender(drawingContext);
            var size = Math.Min(ActualWidth, ActualHeight);
            if (size <= 0 || Foreground == null) return;
            var pen = new Pen(Foreground, 2) { StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round, LineJoin = PenLineJoin.Round };
            drawingContext.PushTransform(new TranslateTransform((ActualWidth - size) / 2, (ActualHeight - size) / 2));
            drawingContext.PushTransform(new ScaleTransform(size / 24, size / 24));
            foreach (var geometry in LucideGeometry.Paths[Kind]) drawingContext.DrawGeometry(null, pen, geometry);
            drawingContext.Pop();
            drawingContext.Pop();
        }
    }
}
