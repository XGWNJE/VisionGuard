using System.Windows.Controls;

namespace VisionGuard.Detector.Windows.Views
{
    /// <summary>
    /// 「全局设定」页：把原「运行环境」与「连接」两页合并成一页。
    /// DataContext 由 MainViewModel 传入的 GlobalSettingsViewModel 提供，
    /// 页内绑定通过 Environment / Connection 两个子 ViewModel 定位。
    /// </summary>
    public partial class GlobalSettingsPage : UserControl
    {
        public GlobalSettingsPage()
        {
            InitializeComponent();
        }
        private void AccountPasswordChanged(object sender, System.Windows.RoutedEventArgs e)
        {
            if (sender is PasswordBox box && box.DataContext is ViewModels.ServerViewModel vm)
            {
                vm.Password = box.Password;
                if (box.Tag == null)
                {
                    box.Tag = true;
                    vm.PropertyChanged += (_, change) => { if (change.PropertyName == nameof(vm.Password) && vm.Password.Length == 0) box.Password = ""; };
                }
            }
        }
    }
}
