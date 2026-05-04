using System.Windows;

namespace VisionGuard.Views
{
    public partial class MainWindow : Window
    {
        public MainWindow()
        {
            InitializeComponent();
        }

        private void Window_Loaded(object sender, RoutedEventArgs e)
        {
            // 窗口加载完成后的初始化（如有需要）
        }

        private void BtnMonitor_Click(object sender, RoutedEventArgs e)
        {
            if (DataContext is ViewModels.MainViewModel vm)
                vm.CurrentPage = ViewModels.PageType.Monitor;
        }

        private void BtnSettings_Click(object sender, RoutedEventArgs e)
        {
            if (DataContext is ViewModels.MainViewModel vm)
                vm.CurrentPage = ViewModels.PageType.Settings;
        }

        private void BtnServer_Click(object sender, RoutedEventArgs e)
        {
            if (DataContext is ViewModels.MainViewModel vm)
                vm.CurrentPage = ViewModels.PageType.Server;
        }
    }
}
