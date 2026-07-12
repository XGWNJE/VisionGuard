using System;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Media;

namespace VisionGuard
{
    public partial class App : Application
    {
        protected override void OnStartup(StartupEventArgs e)
        {
            EnsureWpfFontEnvironment();

            // 高 DPI 感知（PerMonitorV2）
            // .NET 9 WPF 下由 app.manifest 声明，此处无需额外调用 SetProcessDPIAware

            // 全局未处理异常
            this.DispatcherUnhandledException += OnDispatcherUnhandledException;
            AppDomain.CurrentDomain.UnhandledException += OnDomainUnhandledException;

            // 后台同步 NTP 时钟（fire-and-forget，不阻塞 UI 启动）
            _ = Utils.NtpSync.SyncAsync();

            // 后台检查更新（fire-and-forget）
            _ = Utils.AutoUpdater.CheckUpdateAsync();

            // 迁移旧 Assets 目录下的模型到 AppData
            _ = Task.Run(() =>
            {
                var oldDir = System.IO.Path.Combine(AppContext.BaseDirectory, "Assets");
                Utils.ModelManager.MigrateOldModels(oldDir);
            });

            base.OnStartup(e);
        }

        private static void EnsureWpfFontEnvironment()
        {
            if (!string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("windir")))
                return;

            var windowsDirectory = Environment.GetFolderPath(Environment.SpecialFolder.Windows);
            if (!string.IsNullOrWhiteSpace(windowsDirectory))
            {
                Environment.SetEnvironmentVariable("windir", windowsDirectory, EnvironmentVariableTarget.Process);
            }
        }

        private void OnDispatcherUnhandledException(object sender, System.Windows.Threading.DispatcherUnhandledExceptionEventArgs e)
        {
            try
            {
                Utils.LogManager.StaticWarn($"[App] UI 未处理异常: {e.Exception.Message}");
                MessageBox.Show($"发生未处理异常:\n{e.Exception.Message}", "VisionGuard 错误",
                    MessageBoxButton.OK, MessageBoxImage.Error);
            }
            catch { }
            e.Handled = true;
        }

        private void OnDomainUnhandledException(object sender, UnhandledExceptionEventArgs e)
        {
            try
            {
                var ex = e.ExceptionObject as Exception;
                Utils.LogManager.StaticWarn($"[App] 域未处理异常: {ex?.Message}");
            }
            catch { }
        }
    }
}
