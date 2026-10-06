using System;
using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using VisionGuard.Detector.Windows.ViewModels;
namespace VisionGuard.Detector.Windows.Views
{
    public partial class SourcePreviewCard : UserControl
    {
        public static readonly DependencyProperty IsPrimaryProperty = DependencyProperty.Register(
            nameof(IsPrimary), typeof(bool), typeof(SourcePreviewCard),
            new PropertyMetadata(false, (d, e) => ((SourcePreviewCard)d).UpdateLayoutState()));

        public bool IsPrimary
        {
            get => (bool)GetValue(IsPrimaryProperty);
            set => SetValue(IsPrimaryProperty, value);
        }

        private SourceViewModel? _source;

        private void CardContent_OnSizeChanged(object sender, SizeChangedEventArgs e)
        {
            CardContentClip.Rect = new Rect(new Point(), e.NewSize);
        }

        public SourcePreviewCard()
        {
            InitializeComponent();
            SizeChanged += (_, __) => UpdateLayoutState();
            LayoutUpdated += (_, __) => UpdateVisibility();
            DataContextChanged += (_, __) =>
            {
                UnsubscribeSource();
                _source = DataContext as SourceViewModel;
                if (_source != null) _source.PropertyChanged += SourceChanged;
                UpdateLayoutState();
            };
            Unloaded += (_, __) => UnsubscribeSource();
            Loaded += (_, __) =>
            {
                if (_source != null)
                {
                    _source.PropertyChanged -= SourceChanged;
                    _source.PropertyChanged += SourceChanged;
                }
                UpdateLayoutState();
            };
        }

        private void UnsubscribeSource()
        {
            if (_source == null) return;
            _source.PropertyChanged -= SourceChanged;
            if (!IsPrimary) _source.IsPreviewVisible = false;
        }

        private void SourceChanged(object? sender, PropertyChangedEventArgs e)
        {
            if (e.PropertyName == nameof(SourceViewModel.FrameWidth)
                || e.PropertyName == nameof(SourceViewModel.FrameHeight)) UpdateLayoutState();
        }

        private void UpdateLayoutState()
        {
            if (IsPrimary) Height = double.NaN;
            else
            {
                double ratio = _source?.FrameWidth > 0 && _source.FrameHeight > 0
                    ? _source.FrameWidth / _source.FrameHeight : 4d / 3;
                Height = Math.Max(156, Math.Min(420, Math.Max(1, ActualWidth) / ratio));
            }
            UpdateVisibility();
        }

        private void UpdateVisibility()
        {
            if (IsPrimary || _source == null) return;
            DependencyObject? parent = this;
            while (parent != null && parent is not ScrollViewer) parent = VisualTreeHelper.GetParent(parent);
            bool visible = false;
            if (parent is ScrollViewer viewer && IsVisible)
            {
                try
                {
                    var top = TranslatePoint(new Point(), viewer).Y;
                    visible = top < viewer.ActualHeight && top + ActualHeight > 0;
                }
                catch (InvalidOperationException) { }
            }
            _source.IsPreviewVisible = visible;
        }
    }
}
