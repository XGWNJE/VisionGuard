using System.Windows;
using VisionGuard.Detector.Windows.ViewModels;
namespace VisionGuard.Detector.Windows.Views
{
    public partial class AddSourceWindow : Window
    {
        private readonly SourceViewModel _draft;

        public AddSourceWindow(SourceViewModel draft)
        {
            _draft = draft;
            InitializeComponent();
            SourceInitialized += (_, __) => Themes.ThemeManager.ApplyTitleBar(this);
        }

        private void PickWindow_OnClick(object sender, RoutedEventArgs e) => Configure(_draft.PickWindowCommand);
        private void PickRegion_OnClick(object sender, RoutedEventArgs e) => Configure(_draft.SelectRegionCommand);

        private void Configure(RelayCommand command)
        {
            try
            {
                command.Execute(null);
                if (_draft.IsReady) DialogResult = true;
            }
            catch (System.Exception error)
            {
                ThemedMessageBox.Show("配置未保存：" + error.Message, "添加来源", MessageBoxButton.OK, MessageBoxImage.Warning);
            }
        }
    }
}
