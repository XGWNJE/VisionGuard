using System.Windows.Controls;

namespace VisionGuard.Detector.Windows.Views
{
    /// <summary>
    /// 全局设置子界面：按五个分类组织设备级设置。
    /// DataContext 由 MainViewModel 传入的 GlobalSettingsViewModel 提供，
    /// 页内绑定通过 Environment / Connection 两个子 ViewModel 定位。
    /// </summary>
    public partial class GlobalSettingsPage : UserControl
    {
        public GlobalSettingsPage()
        {
            InitializeComponent();
        }
        private void Appearance_OnClick(object sender, System.Windows.RoutedEventArgs e)
        {
            if (sender is Button button && DataContext is ViewModels.GlobalSettingsViewModel vm && int.TryParse(button.Tag?.ToString(), out var mode)) vm.AppearanceIndex = mode;
        }
        internal static void ValidateNamePaste(object sender, System.Windows.DataObjectPastingEventArgs e)
        {
            if (sender is not TextBox box) return;
            if (!e.DataObject.GetDataPresent(System.Windows.DataFormats.UnicodeText)) { e.CancelCommand(); return; }
            var text = e.DataObject.GetData(System.Windows.DataFormats.UnicodeText) as string ?? "";
            var draft = box.Text.Remove(box.SelectionStart, box.SelectionLength).Insert(box.SelectionStart, text);
            if (draft.Length > Utils.DisplayNamePolicy.MaximumLength || !Utils.DisplayNamePolicy.IsValid(draft))
            {
                e.CancelCommand();
                box.ToolTip = "粘贴内容未修改名称。" + Utils.DisplayNamePolicy.Hint;
            }
        }
        private void DeviceName_OnPasting(object sender, System.Windows.DataObjectPastingEventArgs e) => ValidateNamePaste(sender,e);
        private void AccountPasswordChanged(object sender, System.Windows.RoutedEventArgs e)
        {
            if (sender is PasswordBox box && box.DataContext is ViewModels.ServerViewModel vm)
            {
                vm.Password = box.Password;
            }
        }
        private void AccountPasswordLoaded(object sender, System.Windows.RoutedEventArgs e)
        {
            if (sender is not PasswordBox box || box.DataContext is not ViewModels.ServerViewModel vm || box.Tag != null) return;
            System.ComponentModel.PropertyChangedEventHandler handler = (_, change) => { if (change.PropertyName == nameof(vm.Password) && box.Password != vm.Password) box.Password = vm.Password; };
            box.Tag = handler; vm.PropertyChanged += handler; box.Password = vm.Password;
        }
        private void AccountPasswordUnloaded(object sender, System.Windows.RoutedEventArgs e)
        {
            if (sender is PasswordBox box && box.DataContext is ViewModels.ServerViewModel vm && box.Tag is System.ComponentModel.PropertyChangedEventHandler handler) { vm.PropertyChanged -= handler; box.Tag = null; }
        }
    }
}
