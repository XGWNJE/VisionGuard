using System;
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
                ReleaseSource();
                _source = DataContext as SourceViewModel;
                UpdateLayoutState();
            };
            Unloaded += (_, __) => ReleaseSource();
            Loaded += (_, __) => UpdateLayoutState();
        }

        private void ReleaseSource()
        {
            if (_source == null) return;
            if (!IsPrimary) _source.IsPreviewVisible = false;
        }

        private void UpdateLayoutState()
        {
            if (IsPrimary) Height = double.NaN;
            else
            {
                // 列表卡片按固定 4:3 布局，帧方向只影响内部等比缩放和留边。
                Height = Math.Max(156, Math.Min(420, Math.Max(1, ActualWidth) * 3d / 4d));
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
