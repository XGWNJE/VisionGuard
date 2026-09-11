using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;
using VisionGuard.ViewModels;

namespace VisionGuard.Views
{
    public partial class MonitorPage : UserControl
    {
        private string _nameBeforeEdit = string.Empty;

        public MonitorPage()
        {
            InitializeComponent();
        }

        private void SourceNameText_OnMouseLeftButtonDown(object sender, MouseButtonEventArgs e)
        {
            if (SourceNameText.DataContext is not SignalSourceViewModel source) return;

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
                if (SourceNameEditor.DataContext is SignalSourceViewModel source) source.CancelSourceNameEdit(_nameBeforeEdit);
                EndSourceNameEdit();
                e.Handled = true;
            }
        }

        private void CommitSourceName()
        {
            if (SourceNameEditor.Visibility != Visibility.Visible) return;
            if (SourceNameEditor.DataContext is SignalSourceViewModel source) source.CommitSourceNameEdit();
            EndSourceNameEdit();
        }

        private void EndSourceNameEdit()
        {
            SourceNameEditor.Visibility = Visibility.Collapsed;
            SourceNameText.Visibility = Visibility.Visible;
        }
    }
}
