using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;
using VisionGuard.Detector.Windows.ViewModels;

namespace VisionGuard.Detector.Windows.Views
{
    public partial class MonitorPage : UserControl
    {
        private string _nameBeforeEdit = string.Empty;
        private bool _restoreTargetMenuFocus;

        public MonitorPage()
        {
            InitializeComponent();
        }

        private void SourceNameText_OnClick(object sender, RoutedEventArgs e)
        {
            if (SourceNameText.DataContext is not SourceViewModel source) return;

            _nameBeforeEdit = source.SourceName;
            SourceNameText.Visibility = Visibility.Collapsed;
            SourceNameEditor.Visibility = Visibility.Visible;
            Dispatcher.BeginInvoke(() =>
            {
                SourceNameEditor.Focus();
                SourceNameEditor.SelectAll();
            }, DispatcherPriority.Input);
            e.Handled = true;
        }

        private void SourceNameEditor_OnLostKeyboardFocus(object sender, KeyboardFocusChangedEventArgs e)
            => CommitSourceName();

        private void SourceNameEditor_OnKeyDown(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Enter)
            {
                CommitSourceName();
                e.Handled = true;
            }
            else if (e.Key == Key.Escape)
            {
                if (SourceNameEditor.DataContext is SourceViewModel source) source.CancelSourceNameEdit(_nameBeforeEdit);
                EndSourceNameEdit();
                e.Handled = true;
            }
        }

        private void CommitSourceName()
        {
            if (SourceNameEditor.Visibility != Visibility.Visible) return;
            if (SourceNameEditor.DataContext is SourceViewModel source) source.CommitSourceNameEdit();
            EndSourceNameEdit();
        }

        private void EndSourceNameEdit()
        {
            bool restoreFocus = SourceNameEditor.IsKeyboardFocusWithin;
            SourceNameEditor.Visibility = Visibility.Collapsed;
            SourceNameText.Visibility = Visibility.Visible;
            if (restoreFocus) SourceNameText.Focus();
        }

        private void TargetMenuPopup_OnOpened(object sender, System.EventArgs e)
        {
            Dispatcher.BeginInvoke(() =>
            {
                if (TargetMenuPopup.IsOpen) TargetOptionsHost.MoveFocus(new TraversalRequest(FocusNavigationDirection.First));
            }, DispatcherPriority.Input);
        }

        private void TargetMenuPopup_OnPreviewKeyDown(object sender, KeyEventArgs e)
        {
            if (e.Key != Key.Escape) return;
            _restoreTargetMenuFocus = true;
            TargetMenuPopup.IsOpen = false;
            e.Handled = true;
        }

        private void TargetMenuPopup_OnClosed(object sender, System.EventArgs e)
        {
            if (_restoreTargetMenuFocus && TargetMenuButton.IsEnabled) TargetMenuButton.Focus();
            _restoreTargetMenuFocus = false;
        }

        private void TargetMenuButton_OnIsEnabledChanged(object sender, DependencyPropertyChangedEventArgs e)
        {
            if (TargetMenuPopup != null && !TargetMenuButton.IsEnabled) TargetMenuPopup.IsOpen = false;
        }
    }
}
