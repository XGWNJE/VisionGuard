using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using Microsoft.Win32;

namespace VisionGuard.Detector.Windows.Themes
{
    internal static class ThemeManager
    {
        internal static bool IsDark { get; private set; }
        internal static event EventHandler? Changed;
        private static string PreferencePath => Utils.AccountSession.IsIsolated
            ? Path.Combine(Environment.GetEnvironmentVariable("VISIONGUARD_ACCOUNT_DIR")!, "appearance.txt")
            : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VisionGuard", "appearance.txt");
        internal static string Mode { get; private set; } = "system";
        internal static bool SetMode(string mode)
        {
            if (mode != "system" && mode != "light" && mode != "dark") return false;
            try { Directory.CreateDirectory(Path.GetDirectoryName(PreferencePath)!); File.WriteAllText(PreferencePath, mode); }
            catch { return false; }
            Mode = mode; Apply(); return true;
        }

        internal static void Start()
        {
            try { string mode = File.ReadAllText(PreferencePath).Trim(); if (mode == "light" || mode == "dark") Mode = mode; } catch { }
            Apply();
            SystemEvents.UserPreferenceChanged += OnPreferenceChanged;
        }

        internal static void Stop() => SystemEvents.UserPreferenceChanged -= OnPreferenceChanged;

        private static void OnPreferenceChanged(object sender, UserPreferenceChangedEventArgs e)
        {
            var app = Application.Current;
            if (app == null || app.Dispatcher.HasShutdownStarted) return;
            app.Dispatcher.BeginInvoke(new Action(Apply));
        }

        private static void Apply()
        {
            var app = Application.Current;
            if (app == null || app.Dispatcher.HasShutdownStarted) return;
            IsDark = Mode == "dark" || Mode == "system" && ReadDarkPreference();
            var palette = IsDark
                ? new Dictionary<string, string>
                {
                    ["BackgroundDark"] = "#121212", ["SurfaceDark"] = "#1B1B1B", ["SurfaceLight"] = "#282828",
                    ["MutedSurfaceBrush"] = "#1B1B1B", ["TextPrimary"] = "#F2F2F2", ["TextSecondary"] = "#B3B3B3",
                    ["BorderBrush"] = "#3D3D3D", ["Primary"] = "#4FBCC1", ["PrimaryHover"] = "#67CBD0",
                    ["PrimaryPressed"] = "#40A6AC", ["OnPrimaryBrush"] = "#062E30", ["SelectedBrush"] = "#303030", ["SelectedTextBrush"] = "#75D2D6",
                    ["SuccessBrush"] = "#4FBCC1", ["WarningBrush"] = "#F4CC79", ["DangerBrush"] = "#FF9696",
                    ["DangerSurfaceBrush"] = "#442326"
                }
                : new Dictionary<string, string>
                {
                    ["BackgroundDark"] = "#F5F5F5", ["SurfaceDark"] = "#FFFFFF", ["SurfaceLight"] = "#EEEEEE",
                    ["MutedSurfaceBrush"] = "#FFFFFF", ["TextPrimary"] = "#202020", ["TextSecondary"] = "#626262",
                    ["BorderBrush"] = "#DDDDDD", ["Primary"] = "#087F83", ["PrimaryHover"] = "#076D71",
                    ["PrimaryPressed"] = "#065A5E", ["OnPrimaryBrush"] = "#FFFFFF", ["SelectedBrush"] = "#EDEDED", ["SelectedTextBrush"] = "#07666A",
                    ["SuccessBrush"] = "#087F83", ["WarningBrush"] = "#805500", ["DangerBrush"] = "#B83434",
                    ["DangerSurfaceBrush"] = "#FCEBEC"
                };
            foreach (var token in palette)
                app.Resources[token.Key] = new SolidColorBrush((Color)ColorConverter.ConvertFromString(token.Value));

            if (SystemParameters.HighContrast)
            {
                app.Resources["BackgroundDark"] = SystemColors.WindowBrush;
                app.Resources["SurfaceDark"] = SystemColors.WindowBrush;
                app.Resources["SurfaceLight"] = SystemColors.ControlBrush;
                app.Resources["MutedSurfaceBrush"] = SystemColors.WindowBrush;
                app.Resources["TextPrimary"] = SystemColors.WindowTextBrush;
                app.Resources["TextSecondary"] = SystemColors.WindowTextBrush;
                app.Resources["BorderBrush"] = SystemColors.WindowTextBrush;
                app.Resources["Primary"] = SystemColors.HighlightBrush;
                app.Resources["PrimaryHover"] = SystemColors.HighlightBrush;
                app.Resources["PrimaryPressed"] = SystemColors.HighlightBrush;
                app.Resources["OnPrimaryBrush"] = SystemColors.HighlightTextBrush;
                app.Resources["SelectedBrush"] = SystemColors.ControlBrush;
                app.Resources["SelectedTextBrush"] = SystemColors.ControlTextBrush;
                app.Resources["DangerSurfaceBrush"] = SystemColors.ControlBrush;
                app.Resources["DangerBrush"] = SystemColors.WindowTextBrush;
                app.Resources["WarningBrush"] = SystemColors.WindowTextBrush;
                app.Resources["SuccessBrush"] = SystemColors.WindowTextBrush;
            }
            // Native WPF containers also use these system brush keys (for example the scrollbar corner).
            app.Resources[SystemColors.ControlBrushKey] = app.Resources["SurfaceLight"];
            app.Resources[SystemColors.WindowBrushKey] = app.Resources["SurfaceDark"];
            app.Resources[SystemColors.ControlTextBrushKey] = app.Resources["TextPrimary"];
            app.Resources[SystemColors.WindowTextBrushKey] = app.Resources["TextPrimary"];
            app.Resources[SystemColors.GrayTextBrushKey] = app.Resources["TextSecondary"];
            app.Resources[SystemColors.HighlightBrushKey] = app.Resources["SelectedBrush"];
            app.Resources[SystemColors.HighlightTextBrushKey] = app.Resources["SelectedTextBrush"];
            foreach (Window window in app.Windows) ApplyTitleBar(window);
            Changed?.Invoke(null, EventArgs.Empty);
        }

        private static bool ReadDarkPreference()
        {
            try
            {
                using (var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize"))
                    return key?.GetValue("AppsUseLightTheme") is int value && value == 0;
            }
            catch { return false; }
        }

        internal static void ApplyTitleBar(Window window)
        {
            try
            {
                var handle = new WindowInteropHelper(window).Handle;
                if (handle == IntPtr.Zero) return;
                int dark = IsDark && !SystemParameters.HighContrast ? 1 : 0;
                if (DwmSetWindowAttribute(handle, 20, ref dark, sizeof(int)) != 0)
                    DwmSetWindowAttribute(handle, 19, ref dark, sizeof(int));
            }
            catch (DllNotFoundException) { }
            catch (EntryPointNotFoundException) { }
        }

        [DllImport("dwmapi.dll")]
        private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);
    }
}
