using System.Collections.Concurrent;
using System.Drawing;
using System.Text.Json;
using VisionGuard.Capture;
using VisionGuard.Inference;
using VisionGuard.Models;
using VisionGuard.Services;

if (args.Length is < 3 or > 4)
{
    Console.Error.WriteLine("Usage: VisionGuard.WpfSmoke <four-window-title-file> <model.onnx> <report.json> [confidence-threshold]");
    return 2;
}

var titles = File.ReadAllLines(Path.GetFullPath(args[0]))
    .Where(x => !string.IsNullOrWhiteSpace(x)).Select(x => x.Trim()).ToArray();
if (titles.Length != 4 || titles.Distinct(StringComparer.Ordinal).Count() != 4)
    throw new InvalidOperationException("真实窗口 smoke 必须提供恰好四个不同的窗口标题。");

var visibleWindows = WindowEnumerator.GetWindows(IntPtr.Zero);
var windows = titles.Select(title => visibleWindows.SingleOrDefault(w => string.Equals(w.Title, title, StringComparison.Ordinal))
    ?? throw new InvalidOperationException($"未找到唯一目标窗口：{title}")).ToArray();
if (windows.Select(w => w.Handle).Distinct().Count() != 4)
    throw new InvalidOperationException("四个目标必须是四个独立顶层窗口。");

var threshold = args.Length == 4 && float.TryParse(args[3], System.Globalization.NumberStyles.Float,
    System.Globalization.CultureInfo.InvariantCulture, out var parsed) ? parsed : 0.25f;
if (threshold is < 0 or > 1) throw new ArgumentOutOfRangeException(nameof(threshold));
var modelPath = Path.GetFullPath(args[1]);
if (!File.Exists(modelPath)) throw new FileNotFoundException("Model is not cached.", modelPath);

var sourceIds = new[] { "default", "signal-2", "signal-3", "signal-4" };
const int requiredFrames = 30;
var counts = new ConcurrentDictionary<string, int>();
var personHits = new ConcurrentDictionary<string, int>();
var maxConfidence = new ConcurrentDictionary<string, float>();
var samples = new ConcurrentDictionary<string, ConcurrentQueue<long>>();
var errors = new ConcurrentQueue<string>();
using var done = new CountdownEvent(4);
using var coordinator = new MultiSourceMonitorCoordinator();

coordinator.FrameProcessed += (_, e) =>
{
    try
    {
        if (e.Frame.HasError) { errors.Enqueue($"{e.SourceId}: {e.Frame.Error?.Message}"); return; }
        samples.GetOrAdd(e.SourceId, _ => new()).Enqueue(e.Frame.ProcessingMs);
        var people = e.Frame.Detections.Where(d => string.Equals(d.Label, "person", StringComparison.OrdinalIgnoreCase)).ToArray();
        if (people.Length > 0)
        {
            personHits.AddOrUpdate(e.SourceId, 1, (_, old) => old + 1);
            var frameMax = people.Max(d => d.Confidence);
            maxConfidence.AddOrUpdate(e.SourceId, frameMax, (_, old) => Math.Max(old, frameMax));
        }
        if (counts.AddOrUpdate(e.SourceId, 1, (_, old) => old + 1) == requiredFrames) done.Signal();
    }
    finally { e.Frame.Frame?.Dispose(); }
};

MonitorSource CreateSource(int index, string? name = null, int fps = 3, InferenceBackend backend = InferenceBackend.DirectML) =>
    new(sourceIds[index], name ?? windows[index].Title, "yolo26n_320", new MonitorConfig
    {
        CaptureMode = CaptureMode.WindowHandle, TargetWindowHandle = windows[index].Handle,
        TargetWindowTitle = windows[index].Title, WindowSubRegion = Rectangle.Empty,
        TargetFps = fps, SaveAlertSnapshot = false, ConfidenceThreshold = threshold,
        WatchedClasses = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { "person" },
    }, backend);

for (var i = 0; i < 4; i++) coordinator.Add(CreateSource(i));
var startedAt = DateTime.UtcNow;
foreach (var id in sourceIds) coordinator.Start(id, modelPath);
var completed = done.Wait(TimeSpan.FromSeconds(60));
var running = coordinator.Statuses.OrderBy(s => Array.IndexOf(sourceIds, s.SourceId)).ToArray();

coordinator.Stop("default");
var afterStop = counts.ToDictionary(x => x.Key, x => x.Value);
await Task.Delay(1500);
var stopIsolation = counts.GetValueOrDefault("default") == afterStop.GetValueOrDefault("default")
    && sourceIds.Skip(1).All(id => counts.GetValueOrDefault(id) > afterStop.GetValueOrDefault(id));

var beforeReconfigure = counts.ToDictionary(x => x.Key, x => x.Value);
coordinator.Remove("default");
coordinator.Add(CreateSource(0, "reconfigured-default"));
coordinator.Start("default", modelPath);
await Task.Delay(1500);
var configIsolation = sourceIds.All(id => counts.GetValueOrDefault(id) > beforeReconfigure.GetValueOrDefault(id))
    && coordinator.Statuses.Single(s => s.SourceId == "default").SourceName == "reconfigured-default";
foreach (var status in coordinator.Statuses.Where(s => s.IsMonitoring)) coordinator.Stop(status.SourceId);
var stopped = coordinator.Statuses;

var cpuMultiSourceRejected = false;
using (var cpu = new MultiSourceMonitorCoordinator())
{
    cpu.Add(CreateSource(0, backend: InferenceBackend.Cpu));
    cpu.Add(CreateSource(1, backend: InferenceBackend.DirectML));
    cpu.FrameProcessed += (_, e) => e.Frame.Frame?.Dispose();
    cpu.Start("default", modelPath);
    try { cpu.Start("signal-2", modelPath); } catch (InvalidOperationException) { cpuMultiSourceRejected = true; }
    cpu.Stop("default");
}

using var fallback = new OnnxInferenceEngine(modelPath, preferredBackend: InferenceBackend.DirectML, directMlDeviceId: int.MaxValue);
var fallbackToCpu = fallback.ActiveBackend == InferenceBackend.Cpu && !string.IsNullOrWhiteSpace(fallback.BackendFallbackReason);
var passed = completed && errors.IsEmpty && stopIsolation && configIsolation && cpuMultiSourceRejected && fallbackToCpu
    && sourceIds.All(id => personHits.GetValueOrDefault(id) > 0)
    && running.Length == 4 && running.All(s => s.IsMonitoring && s.IsReady && s.ActiveBackend == nameof(InferenceBackend.DirectML) && s.ActualFps >= 2.5)
    && stopped.All(s => !s.IsMonitoring);

var report = new
{
    passed, inputKind = "four independent WindowHandle captures", expectedLabel = "person", requiredFramesPerSource = requiredFrames,
    threshold, stopOneSourceIsolationPassed = stopIsolation, configChangeIsolationPassed = configIsolation,
    cpuMultiSourceRejected, directMlFailureFallsBackToCpu = fallbackToCpu, directMlFallbackReason = fallback.BackendFallbackReason,
    elapsedMs = Math.Round((DateTime.UtcNow - startedAt).TotalMilliseconds, 2),
    sources = running.Select((s, i) =>
    {
        var values = samples.GetValueOrDefault(s.SourceId)?.Order().ToArray() ?? Array.Empty<long>();
        long Percentile(double p) => values.Length == 0 ? 0 : values[Math.Min(values.Length - 1, (int)Math.Ceiling(values.Length * p) - 1)];
        return new { s.SourceId, s.SourceName, hwnd = windows[i].Handle.ToInt64(), frames = counts.GetValueOrDefault(s.SourceId),
            personHitFrames = personHits.GetValueOrDefault(s.SourceId), maxPersonConfidence = Math.Round(maxConfidence.GetValueOrDefault(s.SourceId), 4),
            s.IsReady, s.IsMonitoring, s.ActiveBackend, s.ActualFps, meanProcessingMs = values.Length == 0 ? 0 : Math.Round(values.Average(), 2),
            p95ProcessingMs = Percentile(0.95), p99ProcessingMs = Percentile(0.99), s.Error };
    }),
    stopped = stopped.Select(s => new { s.SourceId, s.IsMonitoring }), errors = errors.ToArray(),
};
var reportPath = Path.GetFullPath(args[2]);
Directory.CreateDirectory(Path.GetDirectoryName(reportPath)!);
await File.WriteAllTextAsync(reportPath, JsonSerializer.Serialize(report, new JsonSerializerOptions { WriteIndented = true }));
Console.WriteLine(JsonSerializer.Serialize(report, new JsonSerializerOptions { WriteIndented = true }));
return passed ? 0 : 1;
