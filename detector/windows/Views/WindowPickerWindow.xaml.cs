using System;
using System.Collections.Generic;
using System.Windows;
using VisionGuard.Capture;

namespace VisionGuard.Views
{
    public partial class WindowPickerWindow : Window
    {
        public WindowInfo? SelectedWindow { get; private set; }

        public WindowPickerWindow()
        {
            InitializeComponent();
            Loaded += OnLoaded;
        }

        private void OnLoaded(object sender, RoutedEventArgs e)
        {
            var windows = WindowEnumerator.GetWindows(System.IntPtr.Zero);
            WindowList.ItemsSource = windows;
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
