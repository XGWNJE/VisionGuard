using System;

namespace VisionGuard.Utils
{
    public static class ApiKeyProvider
    {
        public const string EnvironmentVariableName = "VISIONGUARD_API_KEY";
        public const string DefaultApiKey = "XG-VisionGuard-2024";

        public static string ResolveFromEnvironment()
        {
            var value = Environment.GetEnvironmentVariable(EnvironmentVariableName) ?? "";
            return Resolve(value, DefaultApiKey);
        }

        public static string Resolve(string environmentValue, string fallback)
        {
            if (!string.IsNullOrWhiteSpace(environmentValue))
                return environmentValue.Trim();

            return (fallback ?? "").Trim();
        }
    }
}
