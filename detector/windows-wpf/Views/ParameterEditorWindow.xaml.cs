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
            Closed += (_, __) => _editor.Dispose();
            PreviewKeyDown += (_, e) => { if (e.Key == Key.Enter && Keyboard.Modifiers == ModifierKeys.Control && _editor.CanSave) { Save(); e.Handled = true; } };
        }
        private void Save_OnClick(object sender, RoutedEventArgs e) => Save();
        private void Save() { if (_editor.TrySave()) DialogResult = true; }
    }
}
