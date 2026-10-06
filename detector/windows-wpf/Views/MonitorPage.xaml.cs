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

        private void SourceName_OnPasting(object sender, DataObjectPastingEventArgs e) => GlobalSettingsPage.ValidateNamePaste(sender,e);

        private void CommitSourceName()
        {
            if (SourceNameEditor.Visibility != Visibility.Visible) return;
            if (Validation.GetHasError(SourceNameEditor)) return;
            if (SourceNameEditor.DataContext is SourceViewModel source)
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
            if (restoreFocus) SourceNameText.Focus();
        }

        private void ParameterEditor_OnKeyDown(object sender, KeyEventArgs e)
        {
            if ((sender as FrameworkElement)?.DataContext is not SourceParameterViewModel parameter) return;
            if (e.Key == Key.Escape) { parameter.CancelCommand.Execute(null); e.Handled = true; }
            else if (e.Key == Key.Enter && Keyboard.Modifiers == ModifierKeys.Control && parameter.SaveCommand.CanExecute(null)) { parameter.SaveCommand.Execute(null); e.Handled = true; }
        }
    }
}
