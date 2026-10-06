using System.Windows;
using System.Windows.Controls;

namespace VisionGuard.Detector.Windows.Views
{
    public partial class SourcePreviewCard : UserControl
    {
        public static readonly DependencyProperty IsPrimaryProperty = DependencyProperty.Register(nameof(IsPrimary), typeof(bool), typeof(SourcePreviewCard), new PropertyMetadata(false));
        public bool IsPrimary { get => (bool)GetValue(IsPrimaryProperty); set => SetValue(IsPrimaryProperty, value); }
        public SourcePreviewCard() => InitializeComponent();
        private void SourceMenu_OnClick(object sender, RoutedEventArgs e)
        {
            if (sender is Button button && button.ContextMenu != null) { button.ContextMenu.PlacementTarget = button; button.ContextMenu.IsOpen = true; }
            e.Handled = true;
        }
    }
}
