using System;
using System.ComponentModel;
using System.Drawing;
using System.Windows;
using System.Windows.Forms;
using VisionGuard.Utils;

namespace VisionGuard.Views
{
    public partial class MainWindow : Window
    {
        private NotifyIcon? _notifyIcon;

        public MainWindow()
        {
            InitializeComponent();
            SetupTrayIcon();
        }

        private void SetupTrayIcon()
        {
            _notifyIcon = new NotifyIcon
            {
                Icon = System.Drawing.Icon.ExtractAssociatedIcon(
                    System.Reflection.Assembly.GetExecutingAssembly().Location)
                    ?? SystemIcons.Shield,
                Text = "VisionGuard",
                Visible = true,
            };

            _notifyIcon.DoubleClick += (s, e) => ShowFromTray();

            var menu = new ContextMenuStrip();
            menu.Items.Add("显示主窗口", null, (s, e) => ShowFromTray());
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("退出", null, (s, e) => ExitApp());
            _notifyIcon.ContextMenuStrip = menu;
        }

        private void Window_Loaded(object sender, RoutedEventArgs e)
        {
            // 窗口加载完成后的初始化（如有需要）
        }

        protected override void OnStateChanged(EventArgs e)
        {
            base.OnStateChanged(e);
            if (WindowState == WindowState.Minimized)
            {
                Hide();
            }
        }

        protected override void OnClosing(CancelEventArgs e)
        {
            // 不直接退出，最小化到托盘
            e.Cancel = true;
            WindowState = WindowState.Minimized;
            Hide();
        }

        private void ShowFromTray()
        {
            Show();
            WindowState = WindowState.Normal;
            Activate();
        }

        private void ExitApp()
        {
            if (DataContext is ViewModels.MainViewModel vm)
            {
                vm.Shutdown();
            }

            if (_notifyIcon != null)
            {
                _notifyIcon.Visible = false;
                _notifyIcon.Dispose();
                _notifyIcon = null;
            }

            System.Windows.Application.Current.Shutdown();
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
