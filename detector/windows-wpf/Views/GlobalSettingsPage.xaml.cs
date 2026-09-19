using System.Windows.Controls;

namespace VisionGuard.Views
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
    }
}
