using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;
using System.Web.Script.Serialization;
using VisionGuard.Models;
using VisionGuard.Services;
using VisionGuard.Capture;

namespace VisionGuard.WinFormsInferenceSmoke
{
    internal static class Program
    {
        private const int RequiredFrames = 12;
        /// <summary>持续运行模式判定“该路已停滞”的帧龄阈值。</summary>
        private const double StalledAfterMs = 30000d;
        private static readonly TimeSpan FrameModeTimeout = TimeSpan.FromMinutes(3);

        // 捕获坐标系必须与被测端一致：启动时确定单一 DPI，不做多 DPI 适配。
        [DllImport("user32.dll")]
        private static extern bool SetProcessDPIAware();

        private static int Main(string[] args)
        {
            try { SetProcessDPIAware(); } catch { }
            if (args.Length < 3 || args.Length > 5)
            {
                Console.Error.WriteLine("Usage: VisionGuard.WinFormsInferenceSmoke <handles.txt> <model.onnx> <report.json> [threshold] [durationSeconds]");
                return 2;
            }
            string[] handleLines = File.ReadAllLines(Path.GetFullPath(args[0]));
            long[] handles = handleLines.Where(value => !string.IsNullOrWhiteSpace(value))
                .Select(value => long.Parse(value.Trim(), CultureInfo.InvariantCulture)).ToArray();
            if (handles.Length < 2 || handles.Length > 16 || handles.Distinct().Count() != handles.Length)
                throw new InvalidOperationException("必须提供 2–16 个不同的窗口句柄。");
            string modelPath = Path.GetFullPath(args[1]);
            if (!File.Exists(modelPath)) throw new FileNotFoundException("模型文件不存在。", modelPath);
            float threshold = args.Length >= 4 ? float.Parse(args[3], CultureInfo.InvariantCulture) : 0.25f;
            int durationSeconds = args.Length >= 5 ? int.Parse(args[4], CultureInfo.InvariantCulture) : 0;
            if (durationSeconds < 0)
                throw new ArgumentOutOfRangeException("durationSeconds", "持续运行秒数不能为负。");
            bool soak = durationSeconds > 0;

            bool subRegionCapturePassed;
            using (Bitmap fullFrame = WindowCapturer.CaptureWindow(new IntPtr(handles[0]), Rectangle.Empty))
            {
                var subRegion = new Rectangle(fullFrame.Width / 4, fullFrame.Height / 4,
                    fullFrame.Width / 2, fullFrame.Height / 2);
                using (Bitmap croppedFrame = WindowCapturer.CaptureWindow(new IntPtr(handles[0]), subRegion))
                    subRegionCapturePassed = croppedFrame.Width == subRegion.Width
                        && croppedFrame.Height == subRegion.Height;
            }

            var counts = new ConcurrentDictionary<string, int>();
            var personHits = new ConcurrentDictionary<string, int>();
            var maxConfidence = new ConcurrentDictionary<string, float>();
            var frameIntervals = new ConcurrentDictionary<string, List<double>>();
            var lastFrameTicks = new ConcurrentDictionary<string, long>();
            var errors = new ConcurrentQueue<string>();
            var done = new CountdownEvent(handles.Length);
            var stopwatch = Stopwatch.StartNew();
            IList<MonitorSourceStatus> running;
            bool stopIsolation;

            using (var coordinator = new MultiSourceMonitorCoordinator(capacityProvider: () => 1))
            {
                coordinator.UpdateSourceLimit(handles.Length);
                coordinator.FrameProcessed += (sender, sourceFrame) =>
                {
                    FrameResultEventArgs frame = sourceFrame.Frame;
                    try
                    {
                        if (frame.HasError)
                        {
                            errors.Enqueue(sourceFrame.SourceId + ": " + frame.Error.Message);
                            return;
                        }
                        RecordFrameInterval(frameIntervals, lastFrameTicks, sourceFrame.SourceId);
                        Detection[] people = frame.Detections.Where(detection =>
                            string.Equals(detection.Label, "person", StringComparison.OrdinalIgnoreCase)).ToArray();
                        if (people.Length > 0)
                        {
                            personHits.AddOrUpdate(sourceFrame.SourceId, 1, (id, old) => old + 1);
                            float highest = people.Max(detection => detection.Confidence);
                            maxConfidence.AddOrUpdate(sourceFrame.SourceId, highest, (id, old) => Math.Max(old, highest));
                        }
                        int count = counts.AddOrUpdate(sourceFrame.SourceId, 1, (id, old) => old + 1);
                        if (count == RequiredFrames) done.Signal();
                    }
                    finally { if (frame.Frame != null) frame.Frame.Dispose(); }
                };

                for (int i = 0; i < handles.Length; i++)
                {
                    string sourceId = SourceIdFor(i);
                    var config = new MonitorConfig
                    {
                        CaptureMode = CaptureMode.WindowHandle,
                        TargetWindowHandle = new IntPtr(handles[i]),
                        TargetWindowTitle = "WinForms smoke " + (i + 1),
                        TargetFps = 3,
                        SaveAlertSnapshot = false,
                        ConfidenceThreshold = threshold,
                    };
                    config.WatchedClasses.Add("person");
                    coordinator.Add(new MonitorSource(sourceId, "来源 " + (i + 1), "yolov5nu_320", config));
                }
                IDictionary<string, string> startFailures = coordinator.StartAll(source => modelPath);
                bool completed;
                if (soak)
                {
                    DateTime deadline = DateTime.UtcNow.AddSeconds(durationSeconds);
                    while (DateTime.UtcNow < deadline) Thread.Sleep(250);
                    completed = true;
                }
                else
                {
                    completed = done.Wait(FrameModeTimeout);
                }
                double runSeconds = stopwatch.Elapsed.TotalSeconds;
                long runEndTicks = DateTime.UtcNow.Ticks;
                running = coordinator.Statuses;
                // 必须在停止隔离之前取帧龄，否则被停的那一路会被误判为停滞。
                IDictionary<string, double> frameAgeMs = running.ToDictionary(
                    status => status.SourceId,
                    status => FrameAgeMs(lastFrameTicks, status.SourceId, runEndTicks));

                string first = SourceIdFor(0);
                coordinator.Stop(first);
                var before = counts.ToDictionary(pair => pair.Key, pair => pair.Value);
                Thread.Sleep(1500);
                stopIsolation = counts.Where(pair => pair.Key != first).All(pair => pair.Value > ValueOrZero(before, pair.Key))
                    && ValueOrZero(counts, first) == ValueOrZero(before, first);
                coordinator.StopAll();

                bool stalled = soak && frameAgeMs.Values.Any(age => age > StalledAfterMs);
                // 持续运行模式不设帧率硬门槛：超容量只提示性能不足，把性能不足写成失败会歪曲容量模型语义。
                bool passed = completed && startFailures.Count == 0 && errors.Count == 0 && stopIsolation && !stalled &&
                    subRegionCapturePassed &&
                    running.Count == handles.Length && running.All(status => status.IsMonitoring && status.IsReady) &&
                    running.All(status => status.ActualFps > 0d) &&
                    running.All(status => personHits.ContainsKey(status.SourceId) && personHits[status.SourceId] > 0);

                var sources = new List<object>();
                for (int i = 0; i < handles.Length; i++)
                {
                    string sourceId = SourceIdFor(i);
                    MonitorSourceStatus status = running.FirstOrDefault(item => item.SourceId == sourceId);
                    double[] intervals = SnapshotIntervals(frameIntervals, sourceId);
                    int frames = ValueOrZero(counts, sourceId);
                    sources.Add(new
                    {
                        sourceId,
                        sourceName = status == null ? string.Empty : status.SourceName,
                        hwnd = handles[i],
                        frames,
                        measuredFps = runSeconds > 0d ? frames / runSeconds : 0d,
                        actualFps = status == null ? 0d : status.ActualFps,
                        personHitFrames = ValueOrZero(personHits, sourceId),
                        maxPersonConfidence = maxConfidence.ContainsKey(sourceId) ? maxConfidence[sourceId] : 0f,
                        meanFrameIntervalMs = intervals.Length == 0 ? 0d : intervals.Average(),
                        p95FrameIntervalMs = Percentile(intervals, 0.95d),
                        lastFrameAgeMs = frameAgeMs.ContainsKey(sourceId) ? frameAgeMs[sourceId] : 0d,
                        error = status == null ? "source status missing" : status.Error,
                        capacityWarning = status == null ? string.Empty : status.PerformanceWarning,
                    });
                }

                var report = new
                {
                    schemaVersion = 2,
                    checkedAtUtc = DateTime.UtcNow.ToString("o", CultureInfo.InvariantCulture),
                    passed,
                    mode = soak ? "duration" : "frames",
                    sourceCount = handles.Length,
                    durationSeconds,
                    requiredFramesPerSource = soak ? -1 : RequiredFrames,
                    stalledSourceDetected = stalled,
                    threshold,
                    elapsedMs = stopwatch.ElapsedMilliseconds,
                    runSeconds,
                    stopOneSourceIsolationPassed = stopIsolation,
                    subRegionCapturePassed,
                    capacityWarningPresent = running.All(status => !string.IsNullOrWhiteSpace(status.PerformanceWarning)),
                    startFailures,
                    errors = errors.ToArray(),
                    sources = sources.ToArray(),
                };
                string reportPath = Path.GetFullPath(args[2]);
                Directory.CreateDirectory(Path.GetDirectoryName(reportPath));
                string json = new JavaScriptSerializer().Serialize(report);
                File.WriteAllText(reportPath, json, new System.Text.UTF8Encoding(false));
                Console.WriteLine(json);
                return passed ? 0 : 1;
            }
        }

        private static string SourceIdFor(int index)
        {
            return "winforms-smoke-" + (index + 1);
        }

        private static void RecordFrameInterval(
            ConcurrentDictionary<string, List<double>> frameIntervals,
            ConcurrentDictionary<string, long> lastFrameTicks,
            string sourceId)
        {
            long nowTicks = DateTime.UtcNow.Ticks;
            long previousTicks;
            if (lastFrameTicks.TryGetValue(sourceId, out previousTicks))
            {
                List<double> samples = frameIntervals.GetOrAdd(sourceId, _ => new List<double>());
                double intervalMs = (nowTicks - previousTicks) / (double)TimeSpan.TicksPerMillisecond;
                lock (samples) { samples.Add(intervalMs); }
            }
            lastFrameTicks[sourceId] = nowTicks;
        }

        private static double[] SnapshotIntervals(ConcurrentDictionary<string, List<double>> frameIntervals, string sourceId)
        {
            List<double> samples;
            if (!frameIntervals.TryGetValue(sourceId, out samples)) return new double[0];
            lock (samples) { return samples.ToArray(); }
        }

        private static double FrameAgeMs(ConcurrentDictionary<string, long> lastFrameTicks, string sourceId, long nowTicks)
        {
            long ticks;
            if (!lastFrameTicks.TryGetValue(sourceId, out ticks)) return double.MaxValue;
            return (nowTicks - ticks) / (double)TimeSpan.TicksPerMillisecond;
        }

        private static double Percentile(double[] samples, double percentile)
        {
            if (samples.Length == 0) return 0d;
            double[] ordered = samples.OrderBy(value => value).ToArray();
            int index = (int)Math.Ceiling(percentile * ordered.Length) - 1;
            return ordered[Math.Max(0, Math.Min(ordered.Length - 1, index))];
        }

        private static int ValueOrZero(ConcurrentDictionary<string, int> values, string key)
        {
            int value;
            return values.TryGetValue(key, out value) ? value : 0;
        }

        private static int ValueOrZero(IDictionary<string, int> values, string key)
        {
            int value;
            return values.TryGetValue(key, out value) ? value : 0;
        }
    }
}
