using System;
using System.Diagnostics;
using System.IO;
using System.Threading.Tasks;
using System.Windows;
using VisionGuard.Detector.Windows.Runtime;

namespace VisionGuard.Detector.Windows.Utils
{
    /// <summary>把更新检查交给安装根目录中的 Win7 兼容启动器。</summary>
    public static class AutoUpdater
    {
        public static Task CheckUpdateAsync(bool userInitiated = false)
        {
            try
            {
                if (!InstallLayout.IsUnifiedPackage || !File.Exists(InstallLayout.LauncherPath))
                {
                    const string message = "当前是开发构建或旧式目录，无法执行整包更新。请使用统一 Windows 安装包。";
                    LogManager.StaticWarn("[AutoUpdater] " + message);
                    if (userInitiated)
                        VisionGuard.Detector.Windows.Views.ThemedMessageBox.Show(message, "VisionGuard 视觉节点更新", MessageBoxButton.OK, MessageBoxImage.Information);
                    return Task.CompletedTask;
                }

                string arguments = "--check-update --owner-pid " + Process.GetCurrentProcess().Id;
                if (userInitiated) arguments += " --interactive";
                Process.Start(new ProcessStartInfo
                {
                    FileName = InstallLayout.LauncherPath,
                    Arguments = arguments,
                    WorkingDirectory = InstallLayout.InstallRoot,
                    UseShellExecute = true,
                });
                LogManager.StaticInfo("[AutoUpdater] 更新检查已交给启动器");
            }
            catch (Exception ex)
            {
                LogManager.StaticWarn("[AutoUpdater] 无法启动更新检查: " + ex.Message);
                if (userInitiated)
                    VisionGuard.Detector.Windows.Views.ThemedMessageBox.Show("无法启动更新检查：" + ex.Message, "VisionGuard 视觉节点更新", MessageBoxButton.OK, MessageBoxImage.Warning);
            }
            return Task.CompletedTask;
        }
    }
}
