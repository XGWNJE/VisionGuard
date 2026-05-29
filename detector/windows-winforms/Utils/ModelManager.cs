using System;
using System.IO;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;

namespace VisionGuard.Utils
{
    public static class ModelManager
    {
        private const string ServerBase = "https://visionguard.xgwnje.cn";
        public const int ModelCount = 6;

        public static readonly string[] ModelKeys = {
            "yolov5nu_320", "yolov5nu_640",
            "yolov5su_320", "yolov5su_640",
            "yolov5mu_320", "yolov5mu_640"
        };

        public static readonly string[] ModelDisplayNames = {
            "YOLOv5nu 320 (~10MB)", "YOLOv5nu 640 (~10MB)",
            "YOLOv5su 320 (~35MB)", "YOLOv5su 640 (~35MB)",
            "YOLOv5mu 320 (~96MB)", "YOLOv5mu 640 (~96MB)"
        };

        private static string ModelsDir => Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "VisionGuard", "models");

        public static string GetModelPath(string modelKey)
        {
            return Path.Combine(ModelsDir, $"{modelKey}.onnx");
        }

        public static bool IsDownloaded(string modelKey)
        {
            return File.Exists(GetModelPath(modelKey));
        }

        public static async Task<bool> DownloadModel(string modelKey, IProgress<int> progress = null, CancellationToken ct = default)
        {
            var url = $"{ServerBase}/models/{modelKey}.onnx";
            var destPath = GetModelPath(modelKey);
            var tmpPath = destPath + ".tmp";

            try
            {
                Directory.CreateDirectory(ModelsDir);
                if (File.Exists(tmpPath)) File.Delete(tmpPath);

                long totalRead = 0;

                using (var client = new HttpClient { Timeout = TimeSpan.FromMinutes(10) })
                using (var response = await client.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct))
                {
                    response.EnsureSuccessStatusCode();

                    var totalBytes = response.Content.Headers.ContentLength ?? -1L;
                    using (var contentStream = await response.Content.ReadAsStreamAsync())
                    using (var fileStream = new FileStream(tmpPath, FileMode.Create, FileAccess.Write, FileShare.None, 8192, true))
                    {
                        var buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = await contentStream.ReadAsync(buffer, 0, buffer.Length, ct)) > 0)
                        {
                            await fileStream.WriteAsync(buffer, 0, bytesRead, ct);
                            totalRead += bytesRead;
                            if (totalBytes > 0)
                                progress?.Report((int)(totalRead * 100 / totalBytes));
                        }
                    }
                }

                if (File.Exists(destPath)) File.Delete(destPath);
                File.Move(tmpPath, destPath);

                LogManager.StaticInfo($"[ModelManager] Downloaded {modelKey} ({totalRead / 1048576} MB)");
                return true;
            }
            catch (Exception ex)
            {
                LogManager.StaticWarn($"[ModelManager] Download failed for {modelKey}: {ex.GetType().Name} - {ex.Message}");
                if (ex.InnerException != null)
                    LogManager.StaticWarn($"[ModelManager]   Inner: {ex.InnerException.GetType().Name} - {ex.InnerException.Message}");
                try { if (File.Exists(tmpPath)) File.Delete(tmpPath); } catch { }
                return false;
            }
        }

        public static void MigrateOldModels(string oldAssetsDir)
        {
            try
            {
                if (!Directory.Exists(oldAssetsDir)) return;
                Directory.CreateDirectory(ModelsDir);
                foreach (var key in ModelKeys)
                {
                    var oldPath = Path.Combine(oldAssetsDir, $"{key}.onnx");
                    var newPath = GetModelPath(key);
                    if (File.Exists(oldPath) && !File.Exists(newPath))
                    {
                        File.Copy(oldPath, newPath);
                        LogManager.StaticInfo($"[ModelManager] Migrated {key} from old location");
                    }
                }
            }
            catch (Exception ex)
            {
                LogManager.StaticWarn($"[ModelManager] Migration failed: {ex.Message}");
            }
        }
    }
}
