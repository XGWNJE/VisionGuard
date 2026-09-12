// ┌─────────────────────────────────────────────────────────┐
// │ WindowInfo.cs                                           │
// │ 角色：顶层窗口信息 DTO (Handle, Title, ClassName, Bounds)│
// │ 用途：WindowEnumerator 返回值，Form1 持有当前目标窗口   │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Drawing;

namespace VisionGuard.Capture
{
    /// <summary>
    /// 描述一个顶层窗口的基本信息。
    /// </summary>
    public class WindowInfo
    {
        public IntPtr   Handle    { get; }
        public string   Title     { get; }
        public string   ClassName { get; }
        public Rectangle Bounds   { get; }
        public int ProcessId { get; }
        public string ProcessName { get; }

        public WindowInfo(IntPtr handle, string title, string className, Rectangle bounds,
            int processId = 0, string processName = "")
        {
            Handle    = handle;
            Title     = title;
            ClassName = className;
            Bounds    = bounds;
            ProcessId = processId;
            ProcessName = processName ?? string.Empty;
        }

        public override string ToString()
            => string.IsNullOrWhiteSpace(ProcessName)
                ? $"{Title}  [{ClassName}]"
                : $"{Title}  [{ProcessName} · {ClassName}]";
    }
}
