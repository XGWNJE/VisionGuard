using VisionGuard.Utils;

var applicationId = args.FirstOrDefault() ?? "Probe";
var holdMilliseconds = args.Length > 1 && int.TryParse(args[1], out var parsed) ? parsed : 0;
using var guard = new SingleInstanceGuard(applicationId);
Console.WriteLine(guard.IsPrimaryInstance ? "primary" : "secondary");
if (!guard.IsPrimaryInstance) return 2;
if (holdMilliseconds > 0) await Task.Delay(holdMilliseconds);
return 0;
