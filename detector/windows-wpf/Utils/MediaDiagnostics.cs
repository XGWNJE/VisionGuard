using System;
using System.IO;
using System.Text;

namespace VisionGuard.Detector.Windows.Utils
{
    internal static class MediaDiagnostics
    {
        internal static readonly bool Enabled = Environment.GetEnvironmentVariable("VISIONGUARD_MEDIA_DIAGNOSTICS") == "1";
        private static readonly object Gate = new object();

        // Explicit opt-in, bounded local timing log; never include frames or credentials.
        internal static void Write(string message)
        {
            if (!Enabled) return;
            try
            {
                lock (Gate)
                {
                    Directory.CreateDirectory(AccountSession.LogRoot);
                    string path = Path.Combine(AccountSession.LogRoot, "media-perf.log");
                    if (File.Exists(path) && new FileInfo(path).Length >= 10 * 1024 * 1024) return;
                    File.AppendAllText(path, DateTime.UtcNow.ToString("O") + " " + message + Environment.NewLine, new UTF8Encoding(false));
                }
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }
}
