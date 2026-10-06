using System;
using System.ComponentModel;
using System.Drawing;
using System.Windows;
using System.Windows.Controls.Primitives;
using System.Windows.Forms;
using VisionGuard.Detector.Windows.Utils;

namespace VisionGuard.Detector.Windows.Views
{
    public partial class MainWindow : Window
    {
        private NotifyIcon? _notifyIcon;
        private bool _resourcesDisposed;
        private bool _isClosing;
        private double _inspectorWidth = 320;
        private System.Windows.Controls.Control? _settingsReturnControl;

        public MainWindow()
        {
            InitializeComponent();
            SourceInitialized += (s, e) => Themes.ThemeManager.ApplyTitleBar(this);
            Loaded += (s, e) => { FitWorkArea(); AdaptPanes(); };
            SizeChanged += (s, e) => AdaptPanes();
            SourceList.SelectionChanged += (_, e) =>
            {
                if (e.AddedItems.Count > 0) SourceList.ScrollIntoView(e.AddedItems[0]);
            };
            SetupTrayIcon();
            PreviewKeyDown += (_, e) =>
            {
                if (e.Key != System.Windows.Input.Key.Escape) return;
                if (GlobalHost.Visibility == Visibility.Visible) { CloseGlobalSettings_OnClick(this, new RoutedEventArgs()); e.Handled = true; }
                else if (InspectorDrawer.Visibility == Visibility.Visible) { CloseInspector_OnClick(this, new RoutedEventArgs()); e.Handled = true; }
            };
        }

        private void SetupTrayIcon()
        {
            _notifyIcon = new NotifyIcon
            {
                Icon = System.Drawing.Icon.ExtractAssociatedIcon(
                    System.Reflection.Assembly.GetExecutingAssembly().Location)
                    ?? SystemIcons.Shield,
                Text = "视觉节点",
                Visible = true,
            };

            _notifyIcon.DoubleClick += (s, e) => ShowFromTray();

            var menu = new ContextMenuStrip();
            Themes.ThemedTrayMenu.Apply(menu);
            menu.Items.Add("显示主窗口", null, (s, e) => ShowFromTray());
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("退出主体（保留驻留）", null, (s, e) => ExitApp());
            menu.Items.Add("完整退出（同时关闭驻留）", null, (s, e) => CompleteExitApp());
            _notifyIcon.ContextMenuStrip = menu;
        }

        private void FitWorkArea()
        {
            // Fit the current work area; layout depends on usable width rather than a fixed resolution.
            var workArea = SystemParameters.WorkArea;
            Width = Math.Min(Width, Math.Max(MinWidth, workArea.Width - 16));
            Height = Math.Min(Height, Math.Max(MinHeight, workArea.Height - 16));
            Left = Math.Max(workArea.Left, Math.Min(Left, workArea.Right - Width));
            Top = Math.Max(workArea.Top, Math.Min(Top, workArea.Bottom - Height));
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
        /// 限制两列侧栏的可用宽度，让主画面继续随窗口伸缩。
        /// </summary>
        private void CardsSplitter_OnDragCompleted(object sender, DragCompletedEventArgs e)
        {
            InspectorColumn.Width = new GridLength(Math.Max(300, Math.Min(420, InspectorColumn.ActualWidth)));
            _inspectorWidth = InspectorColumn.Width.Value;
            SourcesColumn.Width = new GridLength(Math.Max(240, Math.Min(380, SourcesColumn.ActualWidth)));
            CardsColumn.Width = new GridLength(1, GridUnitType.Star);
        }
        private void SourcesSplitter_OnDragCompleted(object sender, DragCompletedEventArgs e)
        {
            SourcesColumn.Width = new GridLength(Math.Max(240, Math.Min(380, SourcesColumn.ActualWidth)));
            CardsColumn.Width = new GridLength(1, GridUnitType.Star);
        }
        private void OpenGlobalSettings_OnClick(object sender, RoutedEventArgs e) { _settingsReturnControl = sender as System.Windows.Controls.Control; InspectorDrawer.Visibility = Visibility.Collapsed; GlobalHost.Visibility = Visibility.Visible; MainLayout.Visibility = Visibility.Collapsed; }
        private void CloseGlobalSettings_OnClick(object sender, RoutedEventArgs e)
        {
            GlobalHost.Visibility = Visibility.Collapsed;
            MainLayout.Visibility = Visibility.Visible;
            AdaptPanes();
            if (_settingsReturnControl?.IsVisible == true) _settingsReturnControl.Focus();
            else if (InspectorToggle.IsVisible) InspectorToggle.Focus();
        }
        private void ToggleInspector_OnClick(object sender, RoutedEventArgs e) => InspectorDrawer.Visibility = Visibility.Visible;
        private void CloseInspector_OnClick(object sender, RoutedEventArgs e) { InspectorDrawer.Visibility = Visibility.Collapsed; InspectorToggle.Focus(); }
        private void AdaptPanes()
        {
            if (MainLayout == null) return;
            bool compact = ActualWidth < 1080;
            InspectorHost.Visibility = InspectorSplitter.Visibility = compact ? Visibility.Collapsed : Visibility.Visible;
            InspectorToggle.Visibility = CompactActions.Visibility = compact ? Visibility.Visible : Visibility.Collapsed;
            InspectorColumn.Width = compact ? new GridLength(0) : new GridLength(_inspectorWidth);
            SourceFooter.Columns = compact ? 2 : 1;
            InspectorGap.Width = new GridLength(compact ? 0 : 8);
            if (!compact) InspectorDrawer.Visibility = Visibility.Collapsed;
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
