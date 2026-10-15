using System.Diagnostics;
using System.Drawing;
using System.Text;
using System.Text.Json;
using VisionGuard.Detector.Windows.Inference;
using VisionGuard.Detector.Windows.Models;
using VisionGuard.Detector.Windows.Services;

internal static class StreamCompressionProbe
{
    private static double Iou(RectangleF first, int width1, int height1, RectangleF second, int width2, int height2) {
        var a = new RectangleF(first.X/width1, first.Y/height1, first.Width/width1, first.Height/height1);
        var b = new RectangleF(second.X/width2, second.Y/height2, second.Width/width2, second.Height/height2);
        var intersection = RectangleF.Intersect(a,b);
        double overlap = Math.Max(0, intersection.Width)*Math.Max(0, intersection.Height);
        return overlap/(a.Width*a.Height+b.Width*b.Height-overlap);
    }

    public static int Run(string model, string images, string report)
    {
        using var engine = new OnnxInferenceEngine(model, preferredBackend: InferenceBackend.Cpu);
        var rows = new List<object>();
        var passed = true;
        foreach (var path in Directory.GetFiles(images, "*.jpg").Take(6))
        {
            using var input = new Bitmap(path);
            List<Detection>? reference = null;
            int referenceWidth = 1, referenceHeight = 1;
            foreach (var longEdge in new[] { 1280, 960 })
            foreach (var quality in new[] { 55L, 40L })
            {
                var watch = Stopwatch.StartNew();
                byte[] packet = Array.Empty<byte>();
                var timings = new List<double>();
                for (int iteration = 0; iteration < 6; iteration++) {
                    watch.Restart();
                    packet = MediaPublishService.BuildPacket(new PublishedFrame(input), "compression", "probe", 1, 1, quality, longEdge)!;
                    if (iteration > 0) timings.Add(watch.Elapsed.TotalMilliseconds);
                }
                double encodeMs = timings.OrderBy(value => value).ElementAt(2);
                int header = (packet[0] << 24) | (packet[1] << 16) | (packet[2] << 8) | packet[3];
                using var stream = new MemoryStream(packet, header + 4, packet.Length - header - 4);
                using var decoded = new Bitmap(stream);
                var prepared = ImagePreprocessor.Prepare(decoded, engine.ModelInputSize);
                watch.Restart();
                var output = engine.Run(prepared.Tensor, ImagePreprocessor.InputShape(engine.ModelInputSize));
                var detections = YoloOutputParser.Parse(output, prepared.Transform, .45f, new HashSet<string> { "person" });
                double inferMs = watch.Elapsed.TotalMilliseconds;
                if (reference == null) { reference = detections; referenceWidth = decoded.Width; referenceHeight = decoded.Height; }
                double minIou = reference.Count == 0 ? 1 : reference.Min(expected => detections.Count == 0 ? 0 : detections.Max(actual => Iou(expected.BoundingBox, referenceWidth, referenceHeight, actual.BoundingBox, decoded.Width, decoded.Height)));
                passed &= detections.Count == reference.Count && minIou >= .8;
                double mse = 0;
                using (var expected = new Bitmap(input, decoded.Size)) {
                    for (int y = 0; y < decoded.Height; y += 8) for (int x = 0; x < decoded.Width; x += 8) {
                        var a = expected.GetPixel(x, y); var b = decoded.GetPixel(x, y);
                        mse += (a.R-b.R)*(a.R-b.R)+(a.G-b.G)*(a.G-b.G)+(a.B-b.B)*(a.B-b.B);
                    }
                }
                mse /= Math.Ceiling(decoded.Width/8.0)*Math.Ceiling(decoded.Height/8.0)*3;
                double psnr = 10*Math.Log10(255*255/Math.Max(mse, .001));
                rows.Add(new { image = Path.GetFileName(path), longEdge, quality, bytes = packet.Length, encodeMs, inferMs,
                    width = decoded.Width, height = decoded.Height, people = detections.Count, minIou, psnr,
                    confidence = detections.Count == 0 ? 0 : detections.Max(item => item.Confidence) });
            }
        }
        File.WriteAllText(report, JsonSerializer.Serialize(new { passed, rows }, new JsonSerializerOptions { WriteIndented = true }), new UTF8Encoding(false));
        Console.WriteLine($"Compression comparison: {rows.Count} cases; same detections and IoU >= 0.8 in all = {passed}; {report}");
        return passed ? 0 : 1;
    }
}
