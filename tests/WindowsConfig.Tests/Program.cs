using System;
using System.Linq;
using VisionGuard.Capture;
using VisionGuard.Utils;

internal static class Program
{
    private static int Main(string[] args)
    {
        AssertFalse(string.IsNullOrWhiteSpace(ApiKeyProvider.DefaultApiKey),
            "DefaultApiKey must not be blank for packaged Windows clients.");
        AssertEqual("fallback-key", ApiKeyProvider.Resolve(null, "fallback-key"),
            "missing environment value should use fallback");
        AssertEqual("fallback-key", ApiKeyProvider.Resolve("", "fallback-key"),
            "empty environment value should use fallback");
        AssertEqual("fallback-key", ApiKeyProvider.Resolve("   ", "fallback-key"),
            "whitespace environment value should use fallback");
        AssertEqual("custom-key", ApiKeyProvider.Resolve("  custom-key  ", "fallback-key"),
            "non-empty environment value should be trimmed and used");

        AssertFalse(CaptureSizeConstraints.IsValid(100, 101),
            "width equal to 100 must be rejected");
        AssertFalse(CaptureSizeConstraints.IsValid(101, 100),
            "height equal to 100 must be rejected");
        AssertFalse(CaptureSizeConstraints.IsValid(99, 500),
            "width below 100 must be rejected");
        AssertTrue(CaptureSizeConstraints.IsValid(101, 101),
            "both dimensions above 100 must be accepted");

        var highDpiBoundary = CaptureSizeConstraints.MapToCapturePixels(0, 0, 50, 50, 2, 2);
        AssertEqual(100, highDpiBoundary.Width,
            "50 DIP at 200% DPI must be reported as 100 capture pixels");
        AssertFalse(CaptureSizeConstraints.IsValid(highDpiBoundary),
            "a 100x100 capture-pixel selection must be rejected at 200% DPI");

        var highDpiValid = CaptureSizeConstraints.MapToCapturePixels(0, 0, 50.5, 50.5, 2, 2);
        AssertEqual(101, highDpiValid.Width,
            "50.5 DIP at 200% DPI must be reported as 101 capture pixels");
        AssertTrue(CaptureSizeConstraints.IsValid(highDpiValid),
            "a 101x101 capture-pixel selection must be accepted at 200% DPI");

        var visibleWindows = WindowEnumerator.GetWindows(IntPtr.Zero);
        AssertTrue(visibleWindows.All(window => CaptureSizeConstraints.IsValid(window.Bounds)),
            "window enumeration must return only windows whose width and height are above 100");
        if (args.Length > 0)
        {
            AssertFalse(visibleWindows.Any(window => string.Equals(window.Title, args[0], StringComparison.Ordinal)),
                "the named undersized test window must be filtered from enumeration");
        }

        Console.WriteLine("Windows config tests passed.");
        return 0;
    }

    private static void AssertEqual(string expected, string actual, string message)
    {
        if (!string.Equals(expected, actual, StringComparison.Ordinal))
            throw new InvalidOperationException($"{message}: expected '{expected}', got '{actual}'");
    }

    private static void AssertFalse(bool condition, string message)
    {
        if (condition)
            throw new InvalidOperationException(message);
    }

    private static void AssertEqual(int expected, int actual, string message)
    {
        if (expected != actual)
            throw new InvalidOperationException($"{message}: expected '{expected}', got '{actual}'");
    }

    private static void AssertTrue(bool condition, string message)
    {
        if (!condition)
            throw new InvalidOperationException(message);
    }
}
