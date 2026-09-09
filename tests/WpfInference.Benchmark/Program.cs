using System.Diagnostics;
using System.Drawing;
using System.Text.Json;
using VisionGuard.Inference;

if (args.Length < 1)
{
    Console.Error.WriteLine("Usage: WpfInference.Benchmark <model.onnx> [cpu|directml] [iterations] [streams|image1 image2 image3]");
    return 2;
}

var modelPath = Path.GetFullPath(args[0]);
var requestedBackend = args.Length >= 2 && args[1].Equals("cpu", StringComparison.OrdinalIgnoreCase)
    ? InferenceBackend.Cpu
    : InferenceBackend.DirectML;
var iterations = args.Length >= 3 && int.TryParse(args[2], out var parsedIterations)
    ? Math.Clamp(parsedIterations, 10, 1000)
    : 100;
var hasImageInputs = args.Length >= 4 && !int.TryParse(args[3], out _);
var imagePaths = hasImageInputs ? args.Skip(3).Select(Path.GetFullPath).Take(3).ToArray() : Array.Empty<string>();
var streamCount = hasImageInputs
    ? imagePaths.Length
    : args.Length >= 4 && int.TryParse(args[3], out var parsedStreams) ? Math.Clamp(parsedStreams, 1, 3) : 1;

if (hasImageInputs && imagePaths.Any(path => !File.Exists(path)))
{
    Console.Error.WriteLine("One or more image inputs do not exist.");
    return 2;
}

var engines = Enumerable.Range(0, streamCount)
    .Select(_ => new OnnxInferenceEngine(modelPath, preferredBackend: requestedBackend))
    .ToArray();
var inputSize = engines[0].ModelInputSize;
var inputs = hasImageInputs
    ? imagePaths.Select(path =>
    {
        using var bitmap = new Bitmap(path);
        return ImagePreprocessor.ToTensor(bitmap, inputSize);
    }).ToArray()
    : Enumerable.Range(0, streamCount).Select(_ =>
    {
        var synthetic = new float[3 * inputSize * inputSize];
        for (var i = 0; i < synthetic.Length; i++)
            synthetic[i] = (i % 255) / 255f;
        return synthetic;
    }).ToArray();

var shape = new[] { 1, 3, inputSize, inputSize };
for (var streamIndex = 0; streamIndex < engines.Length; streamIndex++)
{
    for (var i = 0; i < 5; i++)
        _ = engines[streamIndex].Run(inputs[streamIndex], shape);
}

object? backendComparison = null;
var backendComparisonPassed = true;
if (requestedBackend == InferenceBackend.DirectML && engines[0].ActiveBackend == InferenceBackend.DirectML)
{
    using var cpuReference = new OnnxInferenceEngine(modelPath, preferredBackend: InferenceBackend.Cpu);
    var directMlOutput = engines[0].Run(inputs[0], shape);
    var cpuOutput = cpuReference.Run(inputs[0], shape);
    if (directMlOutput.Length != cpuOutput.Length)
        throw new InvalidOperationException("CPU 与 DirectML 输出长度不一致。");
    var differences = directMlOutput.Zip(cpuOutput, (gpu, cpu) => Math.Abs((double)gpu - cpu)).ToArray();
    var captureRegion = new Rectangle(0, 0, inputSize, inputSize);
    var directMlDetections = YoloOutputParser.Parse(directMlOutput, captureRegion, 0.1f, new HashSet<string>(), inputSize);
    var cpuDetections = YoloOutputParser.Parse(cpuOutput, captureRegion, 0.1f, new HashSet<string>(), inputSize);
    var unmatchedCpu = cpuDetections.ToList();
    var matches = directMlDetections.Select(detection =>
    {
        var candidate = unmatchedCpu.Where(x => x.ClassId == detection.ClassId)
            .Select(x => new { Detection = x, Iou = IntersectionOverUnion(detection.BoundingBox, x.BoundingBox) })
            .OrderByDescending(x => x.Iou).FirstOrDefault();
        if (candidate != null) unmatchedCpu.Remove(candidate.Detection);
        return new
        {
            detection.Label,
            confidenceDifference = candidate == null ? double.PositiveInfinity : Math.Abs(detection.Confidence - candidate.Detection.Confidence),
            iou = candidate?.Iou ?? 0,
        };
    }).ToArray();
    backendComparisonPassed = directMlDetections.Count == cpuDetections.Count
        && unmatchedCpu.Count == 0
        && matches.All(x => x.confidenceDifference <= 0.01 && x.iou >= 0.99);
    backendComparison = new
    {
        referenceBackend = cpuReference.ActiveBackend.ToString(),
        comparedElements = differences.Length,
        maxAbsoluteError = differences.Max(),
        meanAbsoluteError = differences.Average(),
        comparisonKind = "parsed detections (class, confidence, IoU); raw output ordering may differ",
        directMlDetectionCount = directMlDetections.Count,
        cpuDetectionCount = cpuDetections.Count,
        matches,
        passed = backendComparisonPassed,
    };
}

var wallClock = Stopwatch.StartNew();
var streamTasks = engines.Select((engine, streamIndex) => Task.Run(() =>
{
    var samples = new double[iterations];
    for (var i = 0; i < iterations; i++)
    {
        var stopwatch = Stopwatch.StartNew();
        float[] input;
        if (hasImageInputs)
        {
            using var bitmap = new Bitmap(imagePaths[streamIndex]);
            input = ImagePreprocessor.ToTensor(bitmap, inputSize);
        }
        else
        {
            input = inputs[streamIndex];
        }
        _ = engine.Run(input, shape);
        stopwatch.Stop();
        samples[i] = stopwatch.Elapsed.TotalMilliseconds;
    }
    Array.Sort(samples);
    double Percentile(double percentile) => samples[Math.Clamp((int)Math.Ceiling(percentile * samples.Length) - 1, 0, samples.Length - 1)];
    return new
    {
        stream = streamIndex + 1,
        meanMs = samples.Average(),
        p50Ms = Percentile(0.50),
        p95Ms = Percentile(0.95),
        p99Ms = Percentile(0.99),
        minMs = samples[0],
        maxMs = samples[^1]
    };
})).ToArray();
var streams = await Task.WhenAll(streamTasks);
wallClock.Stop();

var reportJson = JsonSerializer.Serialize(new
{
    model = Path.GetFileName(modelPath),
    requestedBackend = requestedBackend.ToString(),
    activeBackends = engines.Select(engine => engine.ActiveBackend.ToString()).ToArray(),
    fallbackReasons = engines.Select(engine => engine.BackendFallbackReason).ToArray(),
    inputSize,
    iterations,
    streamCount,
    inputKind = hasImageInputs ? "image" : "synthetic-tensor",
    imageFiles = imagePaths.Select(Path.GetFileName).ToArray(),
    backendComparison,
    wallClockMs = wallClock.Elapsed.TotalMilliseconds,
    perStreamFps = iterations / wallClock.Elapsed.TotalSeconds,
    streams
}, new JsonSerializerOptions { WriteIndented = true });
Console.WriteLine(reportJson);

var reportPath = Environment.GetEnvironmentVariable("VISIONGUARD_BENCHMARK_REPORT");
if (!string.IsNullOrWhiteSpace(reportPath))
{
    var fullReportPath = Path.GetFullPath(reportPath);
    Directory.CreateDirectory(Path.GetDirectoryName(fullReportPath)!);
    File.WriteAllText(fullReportPath, reportJson);
}

var exitCode = engines.All(engine => engine.ActiveBackend == requestedBackend) && backendComparisonPassed ? 0 : 3;
foreach (var engine in engines)
    engine.Dispose();
return exitCode;

static double IntersectionOverUnion(RectangleF left, RectangleF right)
{
    var intersectionLeft = Math.Max(left.Left, right.Left);
    var intersectionTop = Math.Max(left.Top, right.Top);
    var intersectionRight = Math.Min(left.Right, right.Right);
    var intersectionBottom = Math.Min(left.Bottom, right.Bottom);
    var width = Math.Max(0, intersectionRight - intersectionLeft);
    var height = Math.Max(0, intersectionBottom - intersectionTop);
    var intersection = width * height;
    var union = left.Width * left.Height + right.Width * right.Height - intersection;
    return union <= 0 ? 0 : intersection / union;
}
