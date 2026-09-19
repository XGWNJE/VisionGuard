using System;
using System.ComponentModel;
using System.Drawing;
using System.Windows;
using System.Windows.Controls.Primitives;
using System.Windows.Forms;
using VisionGuard.Utils;

namespace VisionGuard.Views
{
    public partial class MainWindow : Window
    {
        private NotifyIcon? _notifyIcon;
        private bool _resourcesDisposed;

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
            DisposeResourcesOnce();
            base.OnClosing(e);
        }

        private void ShowFromTray()
        {
            Show();
            WindowState = WindowState.Normal;
            Activate();
        }

        private void ExitApp()
        {
            Close();
        }

        /// <summary>
        /// 分隔条拖完立即落盘，并把卡片列恢复成自适应：只让「检查区宽度」成为持久化的事实，
        /// 卡片区永远占满剩余空间（否则拖动会把卡片列也变成固定像素，窗口放大时它不跟着变）。
        /// </summary>
        private void CardsSplitter_OnDragCompleted(object sender, DragCompletedEventArgs e)
        {
            CardsColumn.Width = new GridLength(1, GridUnitType.Star);
            VisionGuard.Utils.SettingsStore.Save();
        }

        private void DisposeResourcesOnce()
        {
            if (_resourcesDisposed) return;
            _resourcesDisposed = true;

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
        }

    }
}
