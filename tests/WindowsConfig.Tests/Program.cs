using System;
using VisionGuard.Utils;

internal static class Program
{
    private static int Main()
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
}
