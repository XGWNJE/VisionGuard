using System;
using System.IO;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using VisionGuard.Detector.Windows.Runtime;

namespace VisionGuard.Detector.Windows.Utils
{
    public static class ModelManager
    {
        private static string ServerBase => AppConfig.ServerUrl;
        public const int ModelCount = 2;

        /// <summary>
        /// 本档位的模型清单：每档只留两项，用户不必在长列表里做选择。
        /// legacy 档（Windows 7）的 ONNX Runtime 原生库是 1.1.0，算子覆盖不足以支撑 YOLO26，
        /// 因此固定 YOLOv5 系列；低端机长期实测后只留 320 的 su / mu（640 不实用）。
        /// modern 档使用 YOLO26，固定 320 / 640 两档，走 FP32 + DirectML。
        /// 服务端 `/models` 是静态目录，这四个模型都必须已投放。
        /// </summary>
        public static readonly string[] ModelKeys = NativeLibrarySelector.IsLegacy
            ? new[] { "yolov5su_320", "yolov5mu_320" }
            : new[] { "yolo26m_320", "yolo26m_640" };

        public static readonly string[] ModelDisplayNames = NativeLibrarySelector.IsLegacy
            ? new[] { "YOLOv5su 320 (~35MB)", "YOLOv5mu 320 (~96MB)" }
            : new[] { "YOLO26m 320 (~78MB)", "YOLO26m 640 (~78MB)" };

        /// <summary>本档位的默认模型键（旧配置里的模型键不适用于本档位时使用）。</summary>
        public static string DefaultModelKey => ModelKeys[0];

        /// <summary>指定模型键是否属于本档位清单。</summary>
        public static bool IsSupported(string modelKey) => Array.IndexOf(ModelKeys, modelKey) >= 0;

        private static string ModelsDir => Environment.GetEnvironmentVariable("VISIONGUARD_MODELS_DIR") ?? Path.Combine(
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

        /// <summary>
        /// 最近一次下载失败的具体原因（HTTP 状态码或异常类型与消息）。
        /// 只写 Debug 输出的话，用户机器上没有落点，界面只能报“下载失败”，无法判断是网络、证书还是服务端缺文件。
        /// </summary>
        public static string LastFailureReason { get; private set; } = string.Empty;

        public static async Task<bool> DownloadModel(string modelKey, IProgress<int> progress = null, CancellationToken ct = default)
        {
            var url = $"{ServerBase}/models/{modelKey}.onnx";
            var destPath = GetModelPath(modelKey);
            var tmpPath = destPath + ".tmp";

            try
            {
                Directory.CreateDirectory(ModelsDir);
                if (File.Exists(tmpPath)) File.Delete(tmpPath);

                long totalBytes;
                long totalRead = 0;
                using (var client = new HttpClient { Timeout = TimeSpan.FromMinutes(10) })
                using (var response = await client.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct))
                {
                    if (!response.IsSuccessStatusCode)
                    {
                        LastFailureReason = string.Format("HTTP {0}（{1}）", (int)response.StatusCode, url);
                        LogManager.StaticWarn("[ModelManager] Download rejected: " + LastFailureReason);
                        return false;
                    }
                    totalBytes = response.Content.Headers.ContentLength ?? -1L;
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

                // 半截文件不能当成下载成功：服务端断流时必须重下，否则会拿一个损坏模型去建会话。
                if (totalBytes > 0 && totalRead != totalBytes)
                {
                    LastFailureReason = string.Format("下载不完整（{0}/{1} 字节）", totalRead, totalBytes);
                    LogManager.StaticWarn("[ModelManager] " + LastFailureReason);
                    try { if (File.Exists(tmpPath)) File.Delete(tmpPath); } catch { }
                    return false;
                }

                if (File.Exists(destPath)) File.Delete(destPath);
                File.Move(tmpPath, destPath);

                LastFailureReason = string.Empty;
                LogManager.StaticInfo($"[ModelManager] Downloaded {modelKey} ({totalRead / 1048576} MB)");
                return true;
            }
            catch (Exception ex)
            {
                LastFailureReason = string.Format("{0}: {1}", ex.GetType().Name, ex.Message);
                if (ex.InnerException != null)
                    LastFailureReason += string.Format(" / {0}: {1}", ex.InnerException.GetType().Name, ex.InnerException.Message);
                LogManager.StaticWarn($"[ModelManager] Download failed for {modelKey}: {LastFailureReason}");
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
