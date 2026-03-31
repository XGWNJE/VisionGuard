using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Forms;

/// <summary>
/// 独立诊断工具：检测 Win7 上 onnxruntime.dll 加载失败的根本原因。
/// 编译为独立 exe 拷到目标机运行，不需要任何其他依赖。
/// </summary>
static class DiagTool
{
    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern IntPtr LoadLibraryExW(string lpFileName, IntPtr hFile, uint dwFlags);

    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool FreeLibrary(IntPtr hModule);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    static extern uint FormatMessageW(uint dwFlags, IntPtr lpSource, uint dwMessageId,
        uint dwLanguageId, StringBuilder lpBuffer, uint nSize, IntPtr Arguments);

    [DllImport("kernel32.dll")]
    static extern uint GetLastError();

    const uint LOAD_WITH_ALTERED_SEARCH_PATH = 0x00000008;
    const uint FORMAT_MESSAGE_FROM_SYSTEM    = 0x00001000;
    const uint FORMAT_MESSAGE_IGNORE_INSERTS = 0x00000200;

    [STAThread]
    static void Main()
    {
        string exeDir  = AppDomain.CurrentDomain.BaseDirectory;
        string onnxDll = Path.Combine(exeDir, "onnxruntime.dll");

        var sb = new StringBuilder();
        sb.AppendLine("=== VisionGuard DLL 诊断工具 ===");
        sb.AppendLine("目录: " + exeDir);
        sb.AppendLine();

        // 列出目录里的 DLL
        sb.AppendLine("── 目录中的 DLL ──");
        foreach (var f in Directory.GetFiles(exeDir, "*.dll"))
            sb.AppendLine("  " + Path.GetFileName(f));
        sb.AppendLine();

        // 尝试加载
        sb.AppendLine("── 加载测试 ──");
        string[] targets = {
            "onnxruntime.dll",
            "onnxruntime_providers_shared.dll",
            "ucrtbase.dll",
            "vcruntime140.dll",
            "vcruntime140_1.dll",
            "msvcp140.dll",
        };

        foreach (var name in targets)
        {
            string path = Path.Combine(exeDir, name);
            if (!File.Exists(path))
            {
                sb.AppendLine("[缺失] " + name);
                continue;
            }

            IntPtr h = LoadLibraryExW(path, IntPtr.Zero, LOAD_WITH_ALTERED_SEARCH_PATH);
            if (h != IntPtr.Zero)
            {
                sb.AppendLine("[OK  ] " + name);
                FreeLibrary(h);
            }
            else
            {
                uint err = GetLastError();
                var msg = new StringBuilder(512);
                FormatMessageW(FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
                    IntPtr.Zero, err, 0, msg, 512, IntPtr.Zero);
                sb.AppendLine("[FAIL] " + name + "  错误=" + err + " (0x" + err.ToString("X8") + ")");
                sb.AppendLine("       " + msg.ToString().Trim());
            }
        }

        sb.AppendLine();
        sb.AppendLine("── 系统信息 ──");
        sb.AppendLine("OS: " + Environment.OSVersion);
        sb.AppendLine("CLR: " + Environment.Version);
        sb.AppendLine("64bit OS: " + Environment.Is64BitOperatingSystem);
        sb.AppendLine("64bit Process: " + Environment.Is64BitProcess);

        string result = sb.ToString();
        MessageBox.Show(result, "VisionGuard 诊断", MessageBoxButtons.OK, MessageBoxIcon.Information);

        // 同时写到文件
        File.WriteAllText(Path.Combine(exeDir, "diag_result.txt"), result);
    }
}
