using System;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Media;

namespace VisionGuard
{
    public partial class App : Application
    {
        private Utils.SingleInstanceGuard? _singleInstanceGuard;
        private Services.ResidentBridge? _residentBridge;

        protected override void OnStartup(StartupEventArgs e)
        {
            // 异常处理器必须在任何可能抛异常的逻辑之前注册：单实例判定、驻留桥、字体环境都可能失败，
            // 之前它们排在注册之前，异常会绕过这里并弹出 .NET Framework 的未处理异常对话框，
            // 用户只看到一个与真实原因无关的“Microsoft .NET Framework”窗口。
            this.DispatcherUnhandledException += OnDispatcherUnhandledException;
            AppDomain.CurrentDomain.UnhandledException += OnDomainUnhandledException;

            _singleInstanceGuard = new Utils.SingleInstanceGuard(Runtime.ResidentLauncher.ApplicationId);
            if (!_singleInstanceGuard.IsPrimaryInstance)
            {
                // 已有实例在运行时**不创建任何窗口**：主窗口在下面由本方法显式创建
                // （App.xaml 不再声明 StartupUri），所以这里不会有“关闭后才去显示窗口”的时序问题。
                // 旧写法依赖 App.xaml 的 StartupUri，那时 WPF 会在本方法返回后照样创建主窗口，
                // 而应用已进入关闭状态，Win7 实机上表现为 “Cannot set Visibility to Visible or call
                // Show, ShowDialog, Close, or WindowInteropHelper.EnsureHandle while a Window is closing.”
                _singleInstanceGuard.Dispose();
                _singleInstanceGuard = null;
                WriteCrashLog("启动退出：已有 VisionGuard 实例在运行（单实例 mutex 已被占用）", null);
                Shutdown();
                return;
            }

            EnsureWpfFontEnvironment();

            // 高 DPI 感知（PerMonitorV2）
            // .NET 9 WPF 下由 app.manifest 声明，此处无需额外调用 SetProcessDPIAware

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

            // 主窗口显式创建（App.xaml 不再声明 StartupUri）：这样“已有实例在运行”的分支
            // 能真正在创建窗口之前退出，也让启动顺序集中在这一处。
            var mainWindow = new Views.MainWindow();
            MainWindow = mainWindow;
            mainWindow.Show();

            _residentBridge = new Services.ResidentBridge(Runtime.ResidentLauncher.ApplicationId, () => Dispatcher.BeginInvoke(new Action(Shutdown)));
        }

        protected override void OnExit(ExitEventArgs e)
        {
            _residentBridge?.Dispose();
            _residentBridge = null;
            _singleInstanceGuard?.Dispose();
            _singleInstanceGuard = null;
            base.OnExit(e);
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
                // 记录完整异常（含堆栈）：只记 Message 时无从判断“窗口正在关闭”这类
                // 时序问题发生在哪条调用链上；同时落盘，Release 下才有可带回的证据。
                WriteCrashLog("UI 未处理异常", e.Exception);
                // 应用已在关闭过程中时绝不能再弹窗：MessageBox 自己也是窗口，
                // 在关闭期间 Show 会抛出同一个异常，把真实错误盖掉。
                if (Dispatcher == null || Dispatcher.HasShutdownStarted || Dispatcher.HasShutdownFinished)
                {
                    e.Handled = true;
                    return;
                }
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
                WriteCrashLog("域未处理异常", e.ExceptionObject as Exception);
            }
            catch { }
        }

        /// <summary>
        /// 把未处理异常追加到 <c>%LOCALAPPDATA%\VisionGuard\detector-crash.log</c>。
        ///
        /// <see cref="Utils.LogManager"/> 只写 <c>Debug.WriteLine</c>（Release 编译后整条语句被移除），
        /// 所以实机上的崩溃此前是完全静默的——Win7 上只能看到一个与真实原因无关的
        /// “Microsoft .NET Framework”对话框。这里落盘才能让 owner 带回可诊断的堆栈。
        /// </summary>
        private static void WriteCrashLog(string header, Exception? exception)
        {
            try
            {
                string directory = System.IO.Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VisionGuard");
                System.IO.Directory.CreateDirectory(directory);
                string text = $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] {header}{Environment.NewLine}{exception}{Environment.NewLine}{Environment.NewLine}";
                System.IO.File.AppendAllText(
                    System.IO.Path.Combine(directory, "detector-crash.log"), text, new System.Text.UTF8Encoding(false));
            }
            catch { }
        }
    }
}
