using System;

namespace VisionGuard.Detector.Windows.Utils
{
    public static class ApiKeyProvider
    {
        public const string EnvironmentVariableName = "VISIONGUARD_DETECTOR_API_KEY";
        public const string DefaultApiKey = "";

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
