using System;
using System.Windows;
using System.Windows.Input;
using VisionGuard.Detector.Windows.ViewModels;

namespace VisionGuard.Detector.Windows.Views
{
    public partial class ParameterEditorWindow : Window
    {
        private readonly SourceParametersEditorViewModel _editor;
        public ParameterEditorWindow(SourceViewModel source)
        {
            _editor = new SourceParametersEditorViewModel(source);
            DataContext = _editor;
            InitializeComponent();
            SourceInitialized += (_, __) => Themes.ThemeManager.ApplyTitleBar(this);
            Loaded += (_, __) => { MaxHeight = Math.Max(MinHeight, SystemParameters.WorkArea.Height - 32); Height = Math.Min(Height, MaxHeight); };
            Closing += (_, e) => {
                if (DialogResult == true || !_editor.HasChanges) return;
                var decision = ThemedMessageBox.Show("检测规则尚未保存。", "保留修改？", MessageBoxButton.YesNoCancel, MessageBoxImage.Question, confirmEnabled: _editor.CanSave);
                if (decision == MessageBoxResult.Cancel || decision == MessageBoxResult.Yes && !_editor.TrySave()) e.Cancel = true;
            };
            Closed += (_, __) => _editor.Dispose();
            PreviewKeyDown += (_, e) => { if (e.Key == Key.Enter && Keyboard.Modifiers == ModifierKeys.Control && _editor.CanSave) { Save(); e.Handled = true; } };
        }
        private void Save_OnClick(object sender, RoutedEventArgs e) => Save();
        private void Save() { if (_editor.TrySave()) DialogResult = true; }
    }
}
