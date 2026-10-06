using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using VisionGuard.Detector.Windows.Capture;

namespace VisionGuard.Detector.Windows.Views
{
    public partial class WindowPickerWindow : Window
    {
        public WindowInfo? SelectedWindow { get; private set; }
        private readonly IntPtr _excludeHwnd;

        public WindowPickerWindow(IntPtr excludeHwnd = default)
        {
            _excludeHwnd = excludeHwnd;
            InitializeComponent();
            SourceInitialized += (s, e) => Themes.ThemeManager.ApplyTitleBar(this);
            Loaded += OnLoaded;
        }

        private async void OnLoaded(object sender, RoutedEventArgs e)
        {
            var workArea = SystemParameters.WorkArea;
            Width = Math.Min(Width, Math.Max(MinWidth, workArea.Width - 24));
            Height = Math.Min(Height, Math.Max(MinHeight, workArea.Height - 24));
            Left = Math.Max(workArea.Left, Math.Min(Left, workArea.Right - Width));
            Top = Math.Max(workArea.Top, Math.Min(Top, workArea.Bottom - Height));
            await LoadWindowsAsync();
        }

        private async void Refresh_Click(object sender, RoutedEventArgs e)
        {
            await LoadWindowsAsync();
        }

        private async Task LoadWindowsAsync()
        {
            LoadingIndicator.Visibility = Visibility.Visible;
            EmptyIndicator.Visibility = Visibility.Collapsed;
            OkButton.IsEnabled = false;
            WindowList.IsEnabled = false;

            var pickerHandle = new System.Windows.Interop.WindowInteropHelper(this).Handle;
            var ownerHandle = Owner == null ? IntPtr.Zero : new System.Windows.Interop.WindowInteropHelper(Owner).Handle;
            var windows = await Task.Run(() => WindowEnumerator.GetWindows(_excludeHwnd)
                .Where(window => window.Handle != pickerHandle && window.Handle != ownerHandle).ToList());

            WindowList.ItemsSource = windows;
            WindowList.IsEnabled = true;
            LoadingIndicator.Visibility = Visibility.Collapsed;
            EmptyIndicator.Visibility = windows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            OkButton.IsEnabled = WindowList.SelectedItem != null;
            HeaderText.Text = $"找到 {windows.Count} 个有效窗口 · 宽高均须大于 100 像素";
        }

        private void WindowList_MouseDoubleClick(object sender, System.Windows.Input.MouseButtonEventArgs e)
        {
            ConfirmSelection();
        }

        private void WindowList_SelectionChanged(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
        {
            if (OkButton != null) OkButton.IsEnabled = WindowList.IsEnabled && WindowList.SelectedItem != null;
        }

        private void Ok_Click(object sender, RoutedEventArgs e)
        {
            ConfirmSelection();
        }

        private void Cancel_Click(object sender, RoutedEventArgs e)
        {
            DialogResult = false;
            Close();
        }

        private void ConfirmSelection()
        {
            if (WindowList.SelectedItem is WindowInfo win)
            {
                var currentBounds = WindowEnumerator.GetWindowBounds(win.Handle);
                if (!CaptureSizeConstraints.IsValid(currentBounds))
                {
                    VisionGuard.Detector.Windows.Views.ThemedMessageBox.Show("该窗口当前尺寸过小，宽度和高度必须都大于 100 像素。", "视觉节点",
                        MessageBoxButton.OK, MessageBoxImage.Information);
                    _ = LoadWindowsAsync();
                    return;
                }

                SelectedWindow = new WindowInfo(win.Handle, win.Title, win.ClassName, currentBounds);
                DialogResult = true;
                Close();
            }
        }
    }
}
