using System;
using System.Runtime.InteropServices;

namespace VisionGuard.Capture
{
    internal static class NativeMethods
    {
        // ── user32 ──────────────────────────────────────────────────
        [DllImport("user32.dll")]
        internal static extern IntPtr GetDesktopWindow();

        [DllImport("user32.dll")]
        internal static extern IntPtr GetDC(IntPtr hWnd);

        [DllImport("user32.dll")]
        internal static extern int ReleaseDC(IntPtr hWnd, IntPtr hDC);

        // ── gdi32 ───────────────────────────────────────────────────
        [DllImport("gdi32.dll")]
        internal static extern IntPtr CreateCompatibleDC(IntPtr hDC);

        [DllImport("gdi32.dll")]
        internal static extern IntPtr CreateCompatibleBitmap(IntPtr hDC, int nWidth, int nHeight);

        [DllImport("gdi32.dll")]
        internal static extern IntPtr SelectObject(IntPtr hDC, IntPtr hObject);

        [DllImport("gdi32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool BitBlt(
            IntPtr hdcDest, int nXDest, int nYDest, int nWidth, int nHeight,
            IntPtr hdcSrc,  int nXSrc,  int nYSrc,  uint   dwRop);

        [DllImport("gdi32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool DeleteDC(IntPtr hDC);

        [DllImport("gdi32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool DeleteObject(IntPtr hObject);

        // ── 用于 Debug 模式下监控 GDI 句柄数 ───────────────────────
        [DllImport("user32.dll")]
        internal static extern uint GetGuiResources(IntPtr hProcess, uint uiFlags);

        internal const uint SRCCOPY     = 0x00CC0020;
        internal const uint GR_GDIOBJECTS = 0;
    }
}
