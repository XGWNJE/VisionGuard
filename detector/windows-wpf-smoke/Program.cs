using VisionGuard.Runtime;
using System.Collections.Concurrent;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Text.Json;
using VisionGuard.Capture;
using VisionGuard.Inference;
using VisionGuard.Models;
using VisionGuard.Services;

if (args.Length is < 3 or > 4)
{
    Console.Error.WriteLine("Usage: VisionGuard.WpfSmoke <window-handle-file> <model.onnx> <report.json> [confidence-threshold]");
    return 2;
}

var handleValues = File.ReadAllLines(Path.GetFullPath(args[0]))
    .Where(x => !string.IsNullOrWhiteSpace(x))
    .Select(x => long.TryParse(x.Trim(), out var value) && value != 0
        ? value
        : throw new InvalidOperationException($"无效窗口句柄：{x}"))
    .ToArray();
if (handleValues.Length is < 2 or > 16 || handleValues.Distinct().Count() != handleValues.Length)
    throw new InvalidOperationException("真实窗口 smoke 必须提供 2–16 个不同的窗口句柄。");

var visibleWindows = WindowEnumerator.GetWindows(IntPtr.Zero);
var windows = handleValues.Select(value => visibleWindows.SingleOrDefault(w => w.Handle == new IntPtr(value))
    ?? throw new InvalidOperationException($"目标窗口句柄不可用：{value}")).ToArray();
if (windows.Select(w => w.Handle).Distinct().Count() != windows.Length)
    throw new InvalidOperationException("所有目标必须是独立顶层窗口。");

var threshold = args.Length == 4 && float.TryParse(args[3], System.Globalization.NumberStyles.Float,
    System.Globalization.CultureInfo.InvariantCulture, out var parsed) ? parsed : 0.25f;
if (threshold is < 0 or > 1) throw new ArgumentOutOfRangeException(nameof(threshold));
var modelPath = Path.GetFullPath(args[1]);
if (!File.Exists(modelPath)) throw new FileNotFoundException("Model is not cached.", modelPath);

var subRegionCapturePassed = false;
using (var fullFrame = WindowCapturer.CaptureWindow(windows[0].Handle, Rectangle.Empty))
{
    var subRegion = new Rectangle(fullFrame.Width / 4, fullFrame.Height / 4,
        fullFrame.Width / 2, fullFrame.Height / 2);
    using var croppedFrame = WindowCapturer.CaptureWindow(windows[0].Handle, subRegion);
    subRegionCapturePassed = croppedFrame.Width == subRegion.Width
        && croppedFrame.Height == subRegion.Height;
}

var sourceIds = Enumerable.Range(1, windows.Length).Select(i => i == 1 ? "default" : $"signal-{i}").ToArray();
const int requiredFrames = 30;
var counts = new ConcurrentDictionary<string, int>();
var personHits = new ConcurrentDictionary<string, int>();
var maxConfidence = new ConcurrentDictionary<string, float>();
var samples = new ConcurrentDictionary<string, ConcurrentQueue<long>>();
var errors = new ConcurrentQueue<string>();
using var done = new CountdownEvent(sourceIds.Length);
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

for (var i = 0; i < sourceIds.Length; i++) coordinator.Add(CreateSource(i));
var startedAt = DateTime.UtcNow;
foreach (var id in sourceIds) coordinator.Start(id, modelPath);
var completed = done.Wait(TimeSpan.FromSeconds(60));
var running = coordinator.Statuses.OrderBy(s => Array.IndexOf(sourceIds, s.SourceId)).ToArray();

var beforeWindowChange = counts.ToDictionary(x => x.Key, x => x.Value);
NativeWindowTest.MoveAndResize(windows[0].Handle, 120, 120, 640, 360);
await Task.Delay(1500);
var moveResizePassed = sourceIds.All(id => Net472Compat.DictOrDefault(counts, id) > Net472Compat.DictOrDefault(beforeWindowChange, id));

var beforeMinimize = counts.ToDictionary(x => x.Key, x => x.Value);
var errorsBeforeMinimize = errors.Count;
NativeWindowTest.Minimize(windows[0].Handle);
await Task.Delay(1500);
var minimizeFaultIsolated = errors.Count > errorsBeforeMinimize
    && sourceIds.Skip(1).All(id => Net472Compat.DictOrDefault(counts, id) > Net472Compat.DictOrDefault(beforeMinimize, id));
NativeWindowTest.Restore(windows[0].Handle);
var beforeRestore = Net472Compat.DictOrDefault(counts, "default");
await Task.Delay(1500);
var restoreRecoveryPassed = Net472Compat.DictOrDefault(counts, "default") > beforeRestore;

var beforeOcclusion = Net472Compat.DictOrDefault(counts, "default");
NativeWindowTest.MoveAndResize(windows[1].Handle, 120, 120, 640, 360);
await Task.Delay(1500);
var occlusionCapturePassed = Net472Compat.DictOrDefault(counts, "default") > beforeOcclusion;

coordinator.Stop("default");
var afterStop = counts.ToDictionary(x => x.Key, x => x.Value);
await Task.Delay(1500);
var stopIsolation = Net472Compat.DictOrDefault(counts, "default") == Net472Compat.DictOrDefault(afterStop, "default")
    && sourceIds.Skip(1).All(id => Net472Compat.DictOrDefault(counts, id) > Net472Compat.DictOrDefault(afterStop, id));

var beforeReconfigure = counts.ToDictionary(x => x.Key, x => x.Value);
coordinator.Remove("default");
coordinator.Add(CreateSource(0, "reconfigured-default"));
coordinator.Start("default", modelPath);
await Task.Delay(1500);
var configIsolation = sourceIds.All(id => Net472Compat.DictOrDefault(counts, id) > Net472Compat.DictOrDefault(beforeReconfigure, id))
    && coordinator.Statuses.Single(s => s.SourceId == "default").SourceName == "reconfigured-default";

var errorsBeforeClose = errors.Count;
var beforeClose = counts.ToDictionary(x => x.Key, x => x.Value);
// net472 的 Index/Range 不可用，使用显式下标。
var lastSourceId = sourceIds[sourceIds.Length - 1];
NativeWindowTest.Close(windows[windows.Length - 1].Handle);
var closeDeadline = DateTime.UtcNow.AddSeconds(5);
while (DateTime.UtcNow < closeDeadline && errors.Count == errorsBeforeClose) await Task.Delay(50);
await Task.Delay(500);
var closeIsolationPassed = errors.Count > errorsBeforeClose
    && sourceIds.Take(sourceIds.Length - 1).All(id => Net472Compat.DictOrDefault(counts, id) > Net472Compat.DictOrDefault(beforeClose, id));
coordinator.Stop(lastSourceId);
foreach (var status in coordinator.Statuses.Where(s => s.IsMonitoring)) coordinator.Stop(status.SourceId);
var expectedRuntimeErrors = errors.ToArray();
var unexpectedRuntimeErrors = expectedRuntimeErrors.Where(error =>
    !error.StartsWith("default:", StringComparison.Ordinal)
    && !(error.StartsWith(lastSourceId + ":", StringComparison.Ordinal) && error.IndexOf("已关闭", StringComparison.Ordinal) >= 0)).ToArray();
var stopped = coordinator.Statuses;

var cpuMultiSourceAllowedWithWarning = false;
using (var cpu = new MultiSourceMonitorCoordinator(capacityProvider: _ => 1))
{
    cpu.Add(CreateSource(0, backend: InferenceBackend.Cpu));
    cpu.Add(CreateSource(1, backend: InferenceBackend.Cpu));
    cpu.FrameProcessed += (_, e) => e.Frame.Frame?.Dispose();
    cpu.Start("default", modelPath);
    cpu.Start("signal-2", modelPath);
    cpuMultiSourceAllowedWithWarning = cpu.Statuses.All(status => status.IsMonitoring && status.PerformanceWarning.IndexOf("允许继续运行", StringComparison.Ordinal) >= 0);
    cpu.Stop("default"); cpu.Stop("signal-2");
}

using var fallback = new OnnxInferenceEngine(modelPath, preferredBackend: InferenceBackend.DirectML, directMlDeviceId: int.MaxValue);
var fallbackToCpu = fallback.ActiveBackend == InferenceBackend.Cpu && !string.IsNullOrWhiteSpace(fallback.BackendFallbackReason);

var directMlRuntimeFailureIsolated = false;
using (var failureGate = new ManualResetEventSlim(false))
using (var faulting = new MultiSourceMonitorCoordinator((_, _, _) => new FaultingDirectMlEngine(failureGate)))
{
    faulting.Add(CreateSource(0));
    faulting.Add(CreateSource(1));
    faulting.FrameProcessed += (_, e) => e.Frame.Frame?.Dispose();
    faulting.Start("default", modelPath);
    faulting.Start("signal-2", modelPath);
    failureGate.Set();
    var deadline = DateTime.UtcNow.AddSeconds(5);
    while (DateTime.UtcNow < deadline && faulting.Statuses.All(status => string.IsNullOrWhiteSpace(status.Error)))
        await Task.Delay(50);
    var faulted = faulting.Statuses;
    directMlRuntimeFailureIsolated = faulted.Count == 2
        && faulted.All(status => status.IsMonitoring)
        && faulted.All(status => status.Error.IndexOf("injected DirectML runtime failure", StringComparison.Ordinal) >= 0);
}

var passed = completed && unexpectedRuntimeErrors.Length == 0 && stopIsolation && configIsolation && moveResizePassed
    && minimizeFaultIsolated && restoreRecoveryPassed && occlusionCapturePassed && closeIsolationPassed
    && cpuMultiSourceAllowedWithWarning && fallbackToCpu
    && directMlRuntimeFailureIsolated
    && subRegionCapturePassed
    && sourceIds.All(id => Net472Compat.DictOrDefault(personHits, id) > 0)
    && running.Length == sourceIds.Length && running.All(s => s.IsMonitoring && s.IsReady && s.ActiveBackend == nameof(InferenceBackend.DirectML) && s.ActualFps >= 2.5)
    && stopped.All(s => !s.IsMonitoring);

var report = new
{
    passed, inputKind = $"{sourceIds.Length} independent WindowHandle captures", sourceCount = sourceIds.Length, expectedLabel = "person", requiredFramesPerSource = requiredFrames,
    threshold, stopOneSourceIsolationPassed = stopIsolation, configChangeIsolationPassed = configIsolation,
    moveResizePassed, minimizeFaultIsolated, restoreRecoveryPassed, occlusionCapturePassed, closeIsolationPassed,
    subRegionCapturePassed,
    cpuMultiSourceAllowedWithWarning, directMlFailureFallsBackToCpu = fallbackToCpu, directMlFallbackReason = fallback.BackendFallbackReason,
    directMlRuntimeFailureIsolated,
    elapsedMs = Math.Round((DateTime.UtcNow - startedAt).TotalMilliseconds, 2),
    sources = running.Select((s, i) =>
    {
        var values = Net472Compat.DictOrDefault(samples, s.SourceId)?.ToArray() ?? Array.Empty<long>();
        long Percentile(double p) => values.Length == 0 ? 0 : values[Math.Min(values.Length - 1, (int)Math.Ceiling(values.Length * p) - 1)];
        return new { s.SourceId, s.SourceName, hwnd = windows[i].Handle.ToInt64(), frames = Net472Compat.DictOrDefault(counts, s.SourceId),
            personHitFrames = Net472Compat.DictOrDefault(personHits, s.SourceId), maxPersonConfidence = Math.Round(Net472Compat.DictOrDefault(maxConfidence, s.SourceId), 4),
            s.IsReady, s.IsMonitoring, s.ActiveBackend, s.ActualFps, meanProcessingMs = values.Length == 0 ? 0 : Math.Round(values.Average(), 2),
            p95ProcessingMs = Percentile(0.95), p99ProcessingMs = Percentile(0.99), s.Error };
    }),
    stopped = stopped.Select(s => new { s.SourceId, s.IsMonitoring }), expectedRuntimeErrors, unexpectedRuntimeErrors,
};
var reportPath = Path.GetFullPath(args[2]);
Directory.CreateDirectory(Path.GetDirectoryName(reportPath)!);
await Net472Compat.WriteAllTextAsync(reportPath, JsonSerializer.Serialize(report, new JsonSerializerOptions { WriteIndented = true }));
Console.WriteLine(JsonSerializer.Serialize(report, new JsonSerializerOptions { WriteIndented = true }));
return passed ? 0 : 1;

sealed class FaultingDirectMlEngine : IInferenceEngine
{
    private readonly ManualResetEventSlim _failureGate;
    public FaultingDirectMlEngine(ManualResetEventSlim failureGate) => _failureGate = failureGate;
    public int ModelInputSize => 320;
    public InferenceBackend ActiveBackend => InferenceBackend.DirectML;
    public string BackendFallbackReason => string.Empty;
    public float[] Run(float[] inputData, int[] shape)
    {
        _failureGate.Wait(TimeSpan.FromSeconds(5));
        throw new InvalidOperationException("injected DirectML runtime failure");
    }
    public void Dispose() { }
}

static class NativeWindowTest
{
    private const uint SwpNoZOrder = 0x0004;
    private const int SwMinimize = 6;
    private const int SwRestore = 9;
    private const uint WmClose = 0x0010;

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetWindowPos(IntPtr hWnd, IntPtr insertAfter, int x, int y, int width, int height, uint flags);
    [DllImport("user32.dll")]
    private static extern bool ShowWindow(IntPtr hWnd, int command);
    [DllImport("user32.dll")]
    private static extern bool PostMessage(IntPtr hWnd, uint message, IntPtr wParam, IntPtr lParam);

    public static void MoveAndResize(IntPtr handle, int x, int y, int width, int height)
    {
        if (!SetWindowPos(handle, IntPtr.Zero, x, y, width, height, SwpNoZOrder))
            throw new InvalidOperationException($"SetWindowPos failed: {Marshal.GetLastWin32Error()}");
    }

    public static void Minimize(IntPtr handle) => ShowWindow(handle, SwMinimize);
    public static void Restore(IntPtr handle) => ShowWindow(handle, SwRestore);
    public static void Close(IntPtr handle) => PostMessage(handle, WmClose, IntPtr.Zero, IntPtr.Zero);
}
