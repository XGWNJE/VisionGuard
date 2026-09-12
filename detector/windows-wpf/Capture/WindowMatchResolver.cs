#nullable enable
using System;
using System.Collections.Generic;
using System.Linq;

namespace VisionGuard.Capture
{
    public enum WindowMatchStatus
    {
        Found,
        NotFound,
        Ambiguous,
    }

    public sealed class WindowMatchResult
    {
        public WindowMatchStatus Status { get; }
        public WindowInfo? Window { get; }

        private WindowMatchResult(WindowMatchStatus status, WindowInfo? window)
            => (Status, Window) = (status, window);

        public static WindowMatchResult Found(WindowInfo window) => new(WindowMatchStatus.Found, window);
        public static WindowMatchResult NotFound() => new(WindowMatchStatus.NotFound, null);
        public static WindowMatchResult Ambiguous() => new(WindowMatchStatus.Ambiguous, null);
    }

    /// <summary>使用持久化的标题、窗口类和进程名安全地恢复窗口绑定。</summary>
    public static class WindowMatchResolver
    {
        public static WindowMatchResult Resolve(
            IReadOnlyCollection<WindowInfo> windows,
            string title,
            string className,
            string processName)
        {
            if (windows == null || string.IsNullOrWhiteSpace(title)) return WindowMatchResult.NotFound();

            var exactTitle = windows
                .Where(window => string.Equals(window.Title, title, StringComparison.OrdinalIgnoreCase))
                .ToArray();

            var exactIdentity = FilterIdentity(exactTitle, className, processName);
            if (exactIdentity.Length == 1) return WindowMatchResult.Found(exactIdentity[0]);
            if (exactIdentity.Length > 1) return WindowMatchResult.Ambiguous();

            // 兼容旧配置：没有持久化稳定身份时，仅允许唯一标题命中。
            bool hasStableIdentity = !string.IsNullOrWhiteSpace(className) || !string.IsNullOrWhiteSpace(processName);
            if (!hasStableIdentity)
            {
                if (exactTitle.Length == 1) return WindowMatchResult.Found(exactTitle[0]);
                return exactTitle.Length > 1 ? WindowMatchResult.Ambiguous() : WindowMatchResult.NotFound();
            }

            // 标题可能随文档或页面变化；只有进程名和窗口类能够唯一确定时才自动重绑。
            var stableIdentity = FilterIdentity(windows, className, processName);
            if (stableIdentity.Length == 1) return WindowMatchResult.Found(stableIdentity[0]);
            return stableIdentity.Length > 1 ? WindowMatchResult.Ambiguous() : WindowMatchResult.NotFound();
        }

        private static WindowInfo[] FilterIdentity(
            IEnumerable<WindowInfo> windows,
            string className,
            string processName)
            => windows.Where(window =>
                    (string.IsNullOrWhiteSpace(className)
                        || string.Equals(window.ClassName, className, StringComparison.OrdinalIgnoreCase))
                    && (string.IsNullOrWhiteSpace(processName)
                        || string.Equals(window.ProcessName, processName, StringComparison.OrdinalIgnoreCase)))
                .ToArray();
    }
}
