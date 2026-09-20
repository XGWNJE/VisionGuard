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
        private bool _isClosing;

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
            menu.Items.Add("退出主体（保留驻留）", null, (s, e) => ExitApp());
            menu.Items.Add("完整退出（同时关闭驻留）", null, (s, e) => CompleteExitApp());
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
            // NotifyIcon 的 WinForms 消息可能已经排队；关闭开始后绝不能再尝试 Show/Activate 这个窗口。
            _isClosing = true;
            DisposeResourcesOnce();
            base.OnClosing(e);
        }

        private void ShowFromTray()
        {
            // WinForms NotifyIcon 的双击回调不经过 WPF DispatcherUnhandledException；若恰好和
            // Window.Close 竞争，Show() 会直接弹出 .NET Framework 未处理异常对话框。
            if (_isClosing || Dispatcher.HasShutdownStarted || Dispatcher.HasShutdownFinished) return;

            try
            {
                if (!IsVisible) Show();
                if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
                Activate();
            }
            catch (InvalidOperationException) when (_isClosing || Dispatcher.HasShutdownStarted || Dispatcher.HasShutdownFinished)
            {
                // 关闭期的最后一个托盘消息是预期竞争，不应把用户带到 JIT 调试对话框。
            }
        }

        private void ExitApp()
        {
            Close();
        }

        private void CompleteExitApp()
        {
            if (DataContext is ViewModels.MainViewModel vm
                && vm.GlobalSettingsVm.Connection.FullExitCommand.CanExecute(null))
            {
                vm.GlobalSettingsVm.Connection.FullExitCommand.Execute(null);
            }
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
