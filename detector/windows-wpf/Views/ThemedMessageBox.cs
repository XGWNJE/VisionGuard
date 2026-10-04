using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using VisionGuard.Detector.Windows.Themes;

namespace VisionGuard.Detector.Windows.Views
{
    internal static class ThemedMessageBox
    {
        internal static MessageBoxResult Show(string message, string title, MessageBoxButton buttons, MessageBoxImage image, string? confirmLabel = null, bool destructive = false)
        {
            var app = Application.Current;
            if (app == null || app.Dispatcher.HasShutdownStarted || app.Dispatcher.HasShutdownFinished)
                return buttons == MessageBoxButton.YesNo ? MessageBoxResult.No : MessageBoxResult.OK;
            if (!app.Dispatcher.CheckAccess())
                return app.Dispatcher.Invoke(() => Show(message, title, buttons, image, confirmLabel, destructive));

            var result = buttons == MessageBoxButton.YesNo ? MessageBoxResult.No : MessageBoxResult.OK;
            var dialog = new Window
            {
                Title = title, Width = Math.Min(520, SystemParameters.WorkArea.Width - 32),
                MinWidth = Math.Min(360, SystemParameters.WorkArea.Width - 32),
                SizeToContent = SizeToContent.Height, MaxHeight = Math.Max(240, SystemParameters.WorkArea.Height - 48),
                ResizeMode = ResizeMode.NoResize, ShowInTaskbar = false,
                WindowStartupLocation = WindowStartupLocation.CenterScreen
            };
            dialog.SetResourceReference(Window.BackgroundProperty, "BackgroundDark");
            var owner = app.MainWindow;
            foreach (Window window in app.Windows)
                if (window.IsActive && window.IsVisible && window.IsLoaded) { owner = window; break; }
            if (owner != null && owner.IsVisible && owner.IsLoaded)
            {
                dialog.Owner = owner;
                dialog.WindowStartupLocation = WindowStartupLocation.CenterOwner;
            }
            dialog.SourceInitialized += (s, e) => ThemeManager.ApplyTitleBar(dialog);
            var layout = new Grid { Margin = new Thickness(24) };
            layout.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            layout.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
            layout.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            var heading = new TextBlock { Text = title, FontSize = 20, LineHeight = 26, LineStackingStrategy = LineStackingStrategy.BlockLineHeight, FontWeight = FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 16) };
            heading.SetResourceReference(TextBlock.ForegroundProperty, image == MessageBoxImage.Error ? "DangerBrush" : image == MessageBoxImage.Warning ? "WarningBrush" : "TextPrimary");
            layout.Children.Add(heading);
            var text = new TextBlock { Text = message, TextWrapping = TextWrapping.Wrap, FontSize = 16, LineHeight = 24, LineStackingStrategy = LineStackingStrategy.BlockLineHeight };
            text.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimary");
            var scroll = new ScrollViewer { Content = text, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled, MaxHeight = Math.Max(100, SystemParameters.WorkArea.Height - 240) };
            Grid.SetRow(scroll, 1);
            layout.Children.Add(scroll);
            var actions = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 24, 0, 0) };
            Grid.SetRow(actions, 2);
            layout.Children.Add(actions);
            if (buttons == MessageBoxButton.YesNo)
            {
                AddAction("取消", MessageBoxResult.No, false, true);
                AddAction(confirmLabel ?? "继续", MessageBoxResult.Yes, true, false);
            }
            else AddAction("知道了", MessageBoxResult.OK, true, true);
            dialog.Content = new Border { Child = layout, CornerRadius = new CornerRadius(16) };
            dialog.PreviewKeyDown += (s, e) => { if (e.Key == Key.Escape) { dialog.Close(); e.Handled = true; } };
            dialog.ShowDialog();
            return result;

            void AddAction(string label, MessageBoxResult value, bool primary, bool cancel)
            {
                bool defaultAction = buttons == MessageBoxButton.YesNo ? cancel : primary;
                var button = new Button { Content = label, MinWidth = 88, Margin = new Thickness(8, 0, 0, 0), IsDefault = defaultAction, IsCancel = cancel };
                button.SetResourceReference(FrameworkElement.StyleProperty, primary ? destructive ? "DangerButton" : "PrimaryButton" : "DarkButton");
                if (defaultAction) dialog.Loaded += (s, e) => button.Focus();
                button.Click += (s, e) => { result = value; dialog.Close(); };
                actions.Children.Add(button);
            }
        }
    }
}
