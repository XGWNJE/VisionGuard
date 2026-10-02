using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Threading;
using VisionGuard.Detector.Windows.Models;
using VisionGuard.Detector.Windows.Services;

if (args.Length == 2 && args[0] == "--outbox-contract")
{
    var path = Path.GetFullPath(args[1]);
    if (File.Exists(path)) throw new InvalidOperationException("Use a fresh isolated outbox path");
    var outbox = new AlertOutbox(path);
    var now = DateTime.UtcNow;
    outbox.Enqueue("expired", System.Text.Json.JsonSerializer.Serialize(new { expiresAt = now.AddSeconds(-1) }));
    outbox.Enqueue("live", System.Text.Json.JsonSerializer.Serialize(new { expiresAt = now.AddSeconds(30) }));
    outbox.Enqueue("missing-deadline", "{}");
    if (outbox.Snapshot().Count != 1 || outbox.Snapshot()[0].AlertId != "live")
        throw new InvalidOperationException("Expired events must leave the retry queue");
    var restored = new AlertOutbox(path);
    if (restored.Snapshot().Count != 1 || !restored.Acknowledge("live") || new AlertOutbox(path).Snapshot().Count != 0)
        throw new InvalidOperationException("Retry pruning and acknowledgement must persist across restart");
    Console.WriteLine("PASS: live-only persistent outbox and acknowledgement");
    return;
}

if (args.Length != 1)
    throw new ArgumentException("Usage: WpfAlertChain.Probe <server-url>; set VISIONGUARD_DETECTOR_API_KEY");

var alertId = Guid.NewGuid().ToString();
var alertsDirectory = Path.Combine(AppContext.BaseDirectory, "alerts");
Directory.CreateDirectory(alertsDirectory);
var screenshotPath = Path.Combine(alertsDirectory, alertId + ".png");
using (var screenshot = new Bitmap(320, 240))
{
    using var graphics = Graphics.FromImage(screenshot);
    graphics.Clear(Color.FromArgb(24, 24, 27));
    graphics.FillRectangle(Brushes.White, 120, 35, 80, 170);
    graphics.DrawString("VisionGuard E2E", SystemFonts.DefaultFont, Brushes.Red, 10, 10);
    screenshot.Save(screenshotPath, ImageFormat.Png);
}

using var service = new ServerPushService();
service.Configure(args[0], Environment.GetEnvironmentVariable("VISIONGUARD_DETECTOR_API_KEY") ?? "", "wpf-alert-chain-probe", "WPF Alert Chain Probe");
var deadline = DateTime.UtcNow.AddSeconds(15);
while (!service.IsConnected && DateTime.UtcNow < deadline) Thread.Sleep(100);
if (!service.IsConnected) throw new TimeoutException("WPF ServerPushService did not authenticate in 15 seconds");

service.UpdateHeartbeatParams(true, true, 5, 0.45f, "person", sources: new object[] {
    new Dictionary<string, object> { ["sourceId"] = "probe-source", ["sourceName"] = "隔离链路探针", ["isMonitoring"] = true, ["isReady"] = true, ["modelKey"] = "", ["monitoringExpected"] = true, ["lastProgressAt"] = DateTime.UtcNow.ToString("o") } });
service.SendHeartbeatNow();
using var snapshot = new Bitmap(screenshotPath);
service.PushAlert(new AlertEvent(
    alertId,
    new[] { new Detection { ClassId = 0, Label = "person", Confidence = 0.99f, BoundingBox = new RectangleF(120, 35, 80, 170) } },
    snapshot,
    new Dictionary<string, long> { ["inferenceMs"] = 3, ["totalProcessMs"] = 8 },
    "probe-source",
    "隔离链路探针"));

Thread.Sleep(5000);
Console.WriteLine(alertId);
