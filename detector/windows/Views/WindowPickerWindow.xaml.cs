using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using System.Windows;
using VisionGuard.Capture;

namespace VisionGuard.Views
{
    public partial class WindowPickerWindow : Window
    {
        public WindowInfo? SelectedWindow { get; private set; }
        private readonly IntPtr _excludeHwnd;

        public WindowPickerWindow(IntPtr excludeHwnd = default)
        {
            _excludeHwnd = excludeHwnd;
            InitializeComponent();
            Loaded += OnLoaded;
        }

        private async void OnLoaded(object sender, RoutedEventArgs e)
        {
            await LoadWindowsAsync();
        }

        private async void Refresh_Click(object sender, RoutedEventArgs e)
        {
            await LoadWindowsAsync();
        }

        private async Task LoadWindowsAsync()
        {
            LoadingIndicator.Visibility = Visibility.Visible;
            WindowList.IsEnabled = false;

            var windows = await Task.Run(() => WindowEnumerator.GetWindows(_excludeHwnd));

            WindowList.ItemsSource = windows;
            WindowList.IsEnabled = true;
            LoadingIndicator.Visibility = Visibility.Collapsed;
            HeaderText.Text = $"找到 {windows.Count} 个窗口，双击或单击后点击确定";
        }

        private void WindowList_MouseDoubleClick(object sender, System.Windows.Input.MouseButtonEventArgs e)
        {
            ConfirmSelection();
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
                SelectedWindow = win;
                DialogResult = true;
                Close();
            }
        }
    }
}
