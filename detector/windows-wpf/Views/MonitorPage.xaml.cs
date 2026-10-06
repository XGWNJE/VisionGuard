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
        private SourceViewModel? _nameSource;

        public MonitorPage()
        {
            InitializeComponent();
            SourceNameEditor.DataContextChanged += (_, e) =>
            {
                if (_nameSource == null || ReferenceEquals(_nameSource, e.NewValue)) return;
                CommitSourceName();
                // 无效草稿不能随选择切换带到另一个来源。
                if (_nameSource != null)
                {
                    _nameSource.CancelSourceNameEdit(_nameBeforeEdit);
                    EndSourceNameEdit();
                }
            };
        }

        private void SourceNameText_OnClick(object sender, RoutedEventArgs e)
        {
            if (SourceNameText.DataContext is not SourceViewModel source) return;

            _nameBeforeEdit = source.SourceName;
            _nameSource = source;
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
                _nameSource?.CancelSourceNameEdit(_nameBeforeEdit);
                EndSourceNameEdit();
                e.Handled = true;
            }
        }

        private void SourceName_OnPasting(object sender, DataObjectPastingEventArgs e) => GlobalSettingsPage.ValidateNamePaste(sender,e);

        private void CommitSourceName()
        {
            if (SourceNameEditor.Visibility != Visibility.Visible) return;
            if (Validation.GetHasError(SourceNameEditor)) return;
            if (_nameSource is SourceViewModel source)
            {
                if (!Utils.DisplayNamePolicy.IsValid(source.SourceName))
                {
                    SourceNameEditor.ToolTip = Utils.DisplayNamePolicy.Hint;
                    var binding = SourceNameEditor.GetBindingExpression(TextBox.TextProperty);
                    if (binding != null) Validation.MarkInvalid(binding, new ValidationError(new ExceptionValidationRule(), binding, Utils.DisplayNamePolicy.Hint, null));
                    return;
                }
                var currentBinding = SourceNameEditor.GetBindingExpression(TextBox.TextProperty);
                if (currentBinding != null) Validation.ClearInvalid(currentBinding);
                source.CommitSourceNameEdit();
            }
            EndSourceNameEdit();
        }

        private void EndSourceNameEdit()
        {
            var binding = SourceNameEditor.GetBindingExpression(TextBox.TextProperty);
            if (binding != null) Validation.ClearInvalid(binding);
            bool restoreFocus = SourceNameEditor.IsKeyboardFocusWithin;
            SourceNameEditor.Visibility = Visibility.Collapsed;
            SourceNameText.Visibility = Visibility.Visible;
            _nameSource = null;
            if (restoreFocus) SourceNameText.Focus();
        }

        private void AdjustParameters_OnClick(object sender, RoutedEventArgs e)
        {
            if ((sender as FrameworkElement)?.DataContext is not SourceViewModel source) return;
            var dialog = new ParameterEditorWindow(source) { Owner = Window.GetWindow(this) };
            dialog.ShowDialog();
            AdjustParametersButton.Focus();
            e.Handled = true;
        }
    }
}
