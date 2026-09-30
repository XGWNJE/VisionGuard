using System;
using System.IO;

namespace VisionGuard.Detector.Windows.Runtime
{
    /// <summary>统一安装包与开发构建目录的路径事实。</summary>
    internal static class InstallLayout
    {
        public static readonly string RuntimeDirectory =
            AppDomain.CurrentDomain.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);

        public static readonly bool IsUnifiedPackage = DetectUnifiedPackage();

        public static readonly string InstallRoot = IsUnifiedPackage
            ? Directory.GetParent(Directory.GetParent(RuntimeDirectory).FullName).FullName
            : RuntimeDirectory;

        public static string LauncherPath => Path.Combine(InstallRoot, "VisionGuard.Detector.Windows.exe");
        public static string ResidentPath => Path.Combine(InstallRoot, "VisionGuard.Resident.Windows.exe");

        private static bool DetectUnifiedPackage()
        {
            var runtime = new DirectoryInfo(RuntimeDirectory);
            if (!string.Equals(runtime.Name, "modern", StringComparison.OrdinalIgnoreCase)
                && !string.Equals(runtime.Name, "legacy", StringComparison.OrdinalIgnoreCase)) return false;
            return runtime.Parent != null
                && string.Equals(runtime.Parent.Name, "runtimes", StringComparison.OrdinalIgnoreCase)
                && runtime.Parent.Parent != null;
        }
    }
}
