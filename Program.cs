using System;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace VisionGuard
{
    internal static class Program
    {
        [DllImport("user32.dll")]
        private static extern bool SetProcessDPIAware();

        [STAThread]
        static void Main()
        {
            // 必须在任何窗口句柄创建前调用，确保 BitBlt 坐标与鼠标坐标一致
            SetProcessDPIAware();

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new Form1());
        }
    }
}
