using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Threading;
using VisionGuard.Models;
using VisionGuard.Services;

if (args.Length != 2)
    throw new ArgumentException("Usage: WpfAlertChain.Probe <server-url> <api-key>");

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
service.Configure(args[0], args[1], "wpf-alert-chain-probe", "WPF Alert Chain Probe");
var deadline = DateTime.UtcNow.AddSeconds(15);
while (!service.IsConnected && DateTime.UtcNow < deadline) Thread.Sleep(100);
if (!service.IsConnected) throw new TimeoutException("WPF ServerPushService did not authenticate in 15 seconds");

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
