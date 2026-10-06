using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace VisionGuard.Detector.Windows.Views
{
    /// <summary>Keep the label inside the source frame without moving its detection box.</summary>
    public sealed class FrameLabel : TextBlock
    {
        public static readonly DependencyProperty SourceLeftProperty = DependencyProperty.Register(nameof(SourceLeft), typeof(double), typeof(FrameLabel), new PropertyMetadata(0d, Reposition));
        public static readonly DependencyProperty SourceTopProperty = DependencyProperty.Register(nameof(SourceTop), typeof(double), typeof(FrameLabel), new PropertyMetadata(0d, Reposition));
        public static readonly DependencyProperty FrameWidthProperty = DependencyProperty.Register(nameof(FrameWidth), typeof(double), typeof(FrameLabel), new PropertyMetadata(0d, Reposition));
        public double SourceLeft { get => (double)GetValue(SourceLeftProperty); set => SetValue(SourceLeftProperty, value); }
        public double SourceTop { get => (double)GetValue(SourceTopProperty); set => SetValue(SourceTopProperty, value); }
        public double FrameWidth { get => (double)GetValue(FrameWidthProperty); set => SetValue(FrameWidthProperty, value); }
        public FrameLabel() { HorizontalAlignment = HorizontalAlignment.Left; SizeChanged += (_, __) => UpdatePosition(); }
        private static void Reposition(DependencyObject element, DependencyPropertyChangedEventArgs args) => ((FrameLabel)element).UpdatePosition();
        private void UpdatePosition() => RenderTransform = new TranslateTransform(Math.Min(0, Math.Max(-SourceLeft, FrameWidth - SourceLeft - ActualWidth)), Math.Max(0, 19 - SourceTop));
    }
}
