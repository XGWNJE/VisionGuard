using System.Collections.Concurrent;
using System.Text.Json;
using VisionGuard.Inference;
using VisionGuard.Models;
using VisionGuard.Services;
using VisionGuard.Utils;

if (args.Length is < 3 or > 4)
{
    Console.Error.WriteLine("Usage: VisionGuard.WpfSmoke <three-image-directory> <model.onnx> <report.json> [confidence-threshold]");
    return 2;
}

var imageExtensions = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { ".jpg", ".jpeg", ".png", ".bmp" };
var imagePaths = Directory.Exists(args[0])
    ? Directory.EnumerateFiles(args[0])
        .Where(path => imageExtensions.Contains(Path.GetExtension(path)))
        .OrderBy(path => path, StringComparer.OrdinalIgnoreCase)
        .Select(Path.GetFullPath)
        .ToArray()
    : Array.Empty<string>();
if (imagePaths.Length != 3)
    throw new InvalidOperationException($"人员检测 smoke 必须提供恰好三张 jpg/jpeg/png/bmp 图片，实际找到 {imagePaths.Length} 张：{Path.GetFullPath(args[0])}");

var imageNames = imagePaths.Select(path => Path.GetFileName(path)!).ToArray();
var confidenceThreshold = args.Length == 4 && float.TryParse(args[3], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var parsedThreshold)
    ? parsedThreshold
    : 0.25f;
if (confidenceThreshold is < 0 or > 1)
    throw new ArgumentOutOfRangeException(nameof(confidenceThreshold), "置信度阈值必须在 0 到 1 之间。");

var modelKey = "yolo26n_320";
var modelPath = Path.GetFullPath(args[1]);
if (!File.Exists(modelPath)) throw new FileNotFoundException("Model is not cached.", modelPath);

const int requiredFrames = 30;
var counts = new ConcurrentDictionary<string, int>();
var personHitFrames = new ConcurrentDictionary<string, int>();
var maxPersonConfidence = new ConcurrentDictionary<string, float>();
var errors = new ConcurrentQueue<string>();
var processingSamples = new ConcurrentDictionary<string, ConcurrentQueue<long>>();
using var done = new CountdownEvent(3);
using var coordinator = new MultiSourceMonitorCoordinator();

coordinator.FrameProcessed += (_, e) =>
{
    try
    {
        if (e.Frame.HasError) errors.Enqueue($"{e.SourceId}: {e.Frame.Error?.Message}");
        if (!e.Frame.HasError)
        {
            processingSamples.GetOrAdd(e.SourceId, _ => new ConcurrentQueue<long>()).Enqueue(e.Frame.ProcessingMs);
            var personDetections = e.Frame.Detections
                .Where(d => string.Equals(d.Label, "person", StringComparison.OrdinalIgnoreCase))
                .ToArray();
            if (personDetections.Length > 0)
            {
                personHitFrames.AddOrUpdate(e.SourceId, 1, (_, old) => old + 1);
                var frameMax = personDetections.Max(d => d.Confidence);
                maxPersonConfidence.AddOrUpdate(e.SourceId, frameMax, (_, old) => Math.Max(old, frameMax));
            }
        }
        var count = counts.AddOrUpdate(e.SourceId, 1, (_, old) => old + 1);
        if (count == requiredFrames) done.Signal();
    }
    finally
    {
        e.Frame.Frame?.Dispose();
    }
};

for (var i = 0; i < imagePaths.Length; i++)
{
    var id = $"image-{i + 1}";
    coordinator.Add(new MonitorSource(id, imageNames[i], modelKey, new MonitorConfig
    {
        CaptureMode = CaptureMode.ImageFile,
        ImageFilePath = imagePaths[i],
        TargetFps = 30,
        SaveAlertSnapshot = false,
        ConfidenceThreshold = confidenceThreshold,
        WatchedClasses = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { "person" },
    }, InferenceBackend.DirectML));
}

var startedAt = DateTime.UtcNow;
foreach (var status in coordinator.Statuses) coordinator.Start(status.SourceId, modelPath);
var completed = done.Wait(TimeSpan.FromSeconds(30));
var running = coordinator.Statuses;
coordinator.Stop("image-1");
var countsAfterFirstStop = counts.ToDictionary(x => x.Key, x => x.Value);
await Task.Delay(1000);
var isolationPassed = counts.GetValueOrDefault("image-1") == countsAfterFirstStop.GetValueOrDefault("image-1")
    && counts.GetValueOrDefault("image-2") > countsAfterFirstStop.GetValueOrDefault("image-2")
    && counts.GetValueOrDefault("image-3") > countsAfterFirstStop.GetValueOrDefault("image-3");
var countsBeforeReconfigure = counts.ToDictionary(x => x.Key, x => x.Value);
coordinator.Remove("image-1");
coordinator.Add(new MonitorSource("image-1", "reconfigured-detector.png", modelKey, new MonitorConfig
{
    CaptureMode = CaptureMode.ImageFile,
    ImageFilePath = imagePaths[0],
    TargetFps = 5,
    ConfidenceThreshold = confidenceThreshold,
    AlertCooldownSeconds = 17,
    SaveAlertSnapshot = false,
    WatchedClasses = new HashSet<string> { "person" },
}, InferenceBackend.DirectML));
coordinator.Start("image-1", modelPath);
await Task.Delay(1000);
var configChangeIsolationPassed = counts.GetValueOrDefault("image-1") > countsBeforeReconfigure.GetValueOrDefault("image-1")
    && counts.GetValueOrDefault("image-2") > countsBeforeReconfigure.GetValueOrDefault("image-2")
    && counts.GetValueOrDefault("image-3") > countsBeforeReconfigure.GetValueOrDefault("image-3")
    && coordinator.Statuses.Single(x => x.SourceId == "image-1").SourceName == "reconfigured-detector.png"
    && personHitFrames.GetValueOrDefault("image-1") > 0;
foreach (var status in coordinator.Statuses.Where(s => s.IsMonitoring)) coordinator.Stop(status.SourceId);
var stopped = coordinator.Statuses;

var cpuMultiSourceRejected = false;
using (var cpuCoordinator = new MultiSourceMonitorCoordinator())
{
    for (var i = 0; i < 2; i++)
        cpuCoordinator.Add(new MonitorSource($"cpu-{i + 1}", $"CPU {i + 1}", modelKey, new MonitorConfig
        {
            CaptureMode = CaptureMode.ImageFile, ImageFilePath = imagePaths[i], TargetFps = 1,
            SaveAlertSnapshot = false, WatchedClasses = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { "person" },
        }, InferenceBackend.Cpu));
    cpuCoordinator.FrameProcessed += (_, e) => e.Frame.Frame?.Dispose();
    cpuCoordinator.Start("cpu-1", modelPath);
    try { cpuCoordinator.Start("cpu-2", modelPath); }
    catch (InvalidOperationException) { cpuMultiSourceRejected = true; }
    cpuCoordinator.Stop("cpu-1");
}

using var fallbackEngine = new OnnxInferenceEngine(modelPath, preferredBackend: InferenceBackend.DirectML, directMlDeviceId: int.MaxValue);
var directMlFailureFallsBackToCpu = fallbackEngine.ActiveBackend == InferenceBackend.Cpu
    && !string.IsNullOrWhiteSpace(fallbackEngine.BackendFallbackReason);

var passed = completed
    && errors.IsEmpty
    && isolationPassed
    && configChangeIsolationPassed
    && cpuMultiSourceRejected
    && directMlFailureFallsBackToCpu
    && imagePaths.Select((_, i) => $"image-{i + 1}").All(id => personHitFrames.GetValueOrDefault(id) > 0)
    && running.Count == 3
    && running.All(s => s.IsMonitoring && s.IsReady && s.ActiveBackend == nameof(InferenceBackend.DirectML) && s.ActualFps > 0)
    && stopped.All(s => !s.IsMonitoring);
var report = new
{
    passed,
    inputKind = "ImageFile product capture mode",
    modelKey,
    confidenceThreshold,
    expectedLabel = "person",
    requiredFramesPerSource = requiredFrames,
    stopOneSourceIsolationPassed = isolationPassed,
    configChangeIsolationPassed,
    cpuMultiSourceRejected,
    directMlFailureFallsBackToCpu,
    directMlFallbackReason = fallbackEngine.BackendFallbackReason,
    elapsedMs = Math.Round((DateTime.UtcNow - startedAt).TotalMilliseconds, 2),
    sources = running.Select(s =>
    {
        var samples = processingSamples.GetValueOrDefault(s.SourceId)?.Order().ToArray() ?? Array.Empty<long>();
        long percentile(double p) => samples.Length == 0 ? 0 : samples[Math.Min(samples.Length - 1, (int)Math.Ceiling(samples.Length * p) - 1)];
        return new
        {
            s.SourceId, s.SourceName, imageFile = Path.GetFileName(imagePaths[int.Parse(s.SourceId[^1..]) - 1]),
            frames = counts.GetValueOrDefault(s.SourceId),
            personHitFrames = personHitFrames.GetValueOrDefault(s.SourceId),
            maxPersonConfidence = Math.Round(maxPersonConfidence.GetValueOrDefault(s.SourceId), 4),
            s.IsReady, s.IsMonitoring,
            s.ActiveBackend, s.ActualFps, meanProcessingMs = samples.Length == 0 ? 0 : Math.Round(samples.Average(), 2),
            p95ProcessingMs = percentile(0.95), p99ProcessingMs = percentile(0.99), s.Error,
        };
    }),
    stopped = stopped.Select(s => new { s.SourceId, s.IsMonitoring }),
    errors = errors.ToArray(),
};
Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(args[2]))!);
await File.WriteAllTextAsync(args[2], JsonSerializer.Serialize(report, new JsonSerializerOptions { WriteIndented = true }));
Console.WriteLine(JsonSerializer.Serialize(report, new JsonSerializerOptions { WriteIndented = true }));
return passed ? 0 : 1;
