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
        private double _sourcesWidth = 280;
        private bool _compact;
        private double _paneDragOrigin;
        private double _paneDragWidth;
        private System.Windows.Controls.Control? _settingsReturnControl;

        public MainWindow()
        {
            InitializeComponent();
            SourceInitialized += (s, e) => Themes.ThemeManager.ApplyTitleBar(this);
            Loaded += (s, e) => { FitWorkArea(); AdaptPanes(); };
            MainLayout.SizeChanged += (_, e) => { if (e.WidthChanged) AdaptPanes(); };
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

        // 两个侧栏只调整各自宽度，主画面始终使用剩余空间。
        // 在每次移动中限制宽度，不让默认 GridSplitter 先挤压相邻侧栏、松手再回跳。
        internal static double LimitSidePaneWidth(double requested, double otherWidth,
            double layoutWidth, double minimum, double maximum, bool compact)
        {
            double available = layoutWidth - otherWidth - 240 - (compact ? 8 : 16);
            return Math.Max(minimum, Math.Min(requested, Math.Min(maximum, available)));
        }

        private void SetSidePaneWidth(object handle, double requested)
        {
            if (ReferenceEquals(handle, SourcesSplitter))
            {
                _sourcesWidth = LimitSidePaneWidth(requested, InspectorColumn.ActualWidth,
                    MainLayout.ActualWidth, 240, 380, _compact);
                SourcesColumn.Width = new GridLength(_sourcesWidth);
            }
            else if (!_compact)
            {
                _inspectorWidth = LimitSidePaneWidth(requested, SourcesColumn.ActualWidth,
                    MainLayout.ActualWidth, 300, 420, false);
                InspectorColumn.Width = new GridLength(_inspectorWidth);
            }
        }

        private void PaneResize_OnDragStarted(object sender, DragStartedEventArgs e)
        {
            _paneDragOrigin = System.Windows.Input.Mouse.GetPosition(MainLayout).X;
            _paneDragWidth = ReferenceEquals(sender, SourcesSplitter)
                ? SourcesColumn.ActualWidth : InspectorColumn.ActualWidth;
        }

        private void PaneResize_OnDragDelta(object sender, DragDeltaEventArgs e)
            => SetSidePaneWidth(sender, _paneDragWidth
                - (System.Windows.Input.Mouse.GetPosition(MainLayout).X - _paneDragOrigin));

        private void PaneResize_OnDragCompleted(object sender, DragCompletedEventArgs e)
        {
            if (e.Canceled) SetSidePaneWidth(sender, _paneDragWidth);
        }

        private void PaneResize_OnKeyDown(object sender, System.Windows.Input.KeyEventArgs e)
        {
            double width = ReferenceEquals(sender, SourcesSplitter)
                ? SourcesColumn.ActualWidth : InspectorColumn.ActualWidth;
            double step = (System.Windows.Input.Keyboard.Modifiers & System.Windows.Input.ModifierKeys.Shift) != 0 ? 40 : 10;
            switch (e.Key)
            {
                case System.Windows.Input.Key.Left: width += step; break;
                case System.Windows.Input.Key.Right: width -= step; break;
                case System.Windows.Input.Key.Home: width = 0; break;
                case System.Windows.Input.Key.End: width = double.MaxValue; break;
                default: return;
            }
            SetSidePaneWidth(sender, width);
            e.Handled = true;
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
            _compact = compact;
            InspectorHost.Visibility = InspectorSplitter.Visibility = compact ? Visibility.Collapsed : Visibility.Visible;
            InspectorToggle.Visibility = CompactActions.Visibility = compact ? Visibility.Visible : Visibility.Collapsed;
            SourceFooter.Columns = compact ? 2 : 1;
            InspectorGap.Width = new GridLength(compact ? 0 : 8);
            if (!compact) InspectorDrawer.Visibility = Visibility.Collapsed;
            if (MainLayout.ActualWidth <= 0) return;
            double inspectorWidth = compact ? 0 : LimitSidePaneWidth(_inspectorWidth, 240,
                MainLayout.ActualWidth, 300, 420, false);
            InspectorColumn.Width = new GridLength(inspectorWidth);
            SourcesColumn.Width = new GridLength(LimitSidePaneWidth(_sourcesWidth, inspectorWidth,
                MainLayout.ActualWidth, 240, 380, compact));
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
