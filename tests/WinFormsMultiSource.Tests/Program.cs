using System;
using System.Collections.Generic;
using System.Linq;
using VisionGuard.Models;
using VisionGuard.Services;

internal static class Program
{
    private static int Main()
    {
        var runtimes = new Dictionary<string, FakeRuntime>(StringComparer.Ordinal);
        var observedStarting = new HashSet<string>(StringComparer.Ordinal);
        int capacity = 1;
        using (var coordinator = new MultiSourceMonitorCoordinator(
            source =>
            {
                var runtime = new FakeRuntime(source);
                runtimes.Add(source.SourceId, runtime);
                return runtime;
            },
            () => capacity))
        {
            coordinator.StatusChanged += (sender, args) =>
            {
                if (args.Status.IsStarting) observedStarting.Add(args.Status.SourceId);
            };
            coordinator.UpdateSourceLimit(2);
            coordinator.Add(Source("source-1", "门口"));
            coordinator.Add(Source("source-2", "仓库"));
            AssertEqual(2, coordinator.Count, "two independent sources must be registered");

            bool limitRejected = false;
            try { coordinator.Add(Source("source-3", "车库")); }
            catch (InvalidOperationException) { limitRejected = true; }
            AssertTrue(limitRejected, "negotiated server source limit must reject only additional registration");

            coordinator.Start("source-1", "model-1");
            AssertTrue(observedStarting.Contains("source-1"), "source must report starting before model initialization completes");
            AssertTrue(runtimes["source-1"].IsStarted, "target source must start");
            AssertFalse(runtimes["source-2"].IsStarted, "non-target source must remain stopped");

            runtimes["source-2"].FailStart = true;
            IDictionary<string, string> failures = coordinator.StartAll(_ => "model");
            AssertTrue(runtimes["source-1"].IsStarted, "one source failure must not stop a healthy source");
            AssertTrue(failures.ContainsKey("source-2"), "start-all must report the failing source by stable id");

            runtimes["source-2"].FailStart = false;
            coordinator.Start("source-2", "model-2");
            AssertTrue(coordinator.Statuses.All(status => status.IsMonitoring),
                "both source runtimes must report independent running state");
            AssertTrue(coordinator.Statuses.All(status => status.PerformanceWarning.Contains("允许继续运行")),
                "capacity overage must warn without stopping or rejecting registered sources");
            capacity = 2;
            coordinator.NotifyCapacityChanged();
            AssertTrue(coordinator.Statuses.All(status => string.IsNullOrEmpty(status.PerformanceWarning)),
                "raising the user-configured CPU capacity must clear warnings without restarting sources");

            coordinator.UpdateModel("source-2", "yolov5su_320");
            AssertTrue(coordinator.Statuses.Single(status => status.SourceId == "source-2").ModelKey == "yolov5su_320",
                "model updates must remain scoped to the selected source");

            var updated = Source("ignored", "更新后").Config;
            updated.TargetFps = 7;
            coordinator.UpdateConfig("source-2", updated);
            AssertEqual(7, runtimes["source-2"].Source.Config.TargetFps,
                "config update must route to the selected source only");
            AssertEqual(3, runtimes["source-1"].Source.Config.TargetFps,
                "config update must not leak into another source");

            runtimes["source-2"].FailStop = true;
            IDictionary<string, string> stopFailures = coordinator.StopAll();
            AssertFalse(runtimes["source-1"].IsStarted, "a healthy source must still stop when another source stop fails");
            AssertTrue(stopFailures.ContainsKey("source-2"), "stop-all must report an isolated stop failure");
            runtimes["source-2"].FailStop = false;
            runtimes["source-2"].Stop();
            coordinator.Start("source-1", "model-1");

            coordinator.Remove("source-2");
            AssertTrue(runtimes["source-2"].Disposed, "removing a source must dispose its runtime");
            AssertTrue(runtimes["source-1"].IsStarted, "removing one source must leave another source running");
        }

        Console.WriteLine("WinForms multi-source tests passed.");
        return 0;
    }

    private static MonitorSource Source(string id, string name)
    {
        return new MonitorSource(id, name, "yolov5nu_320", new MonitorConfig
        {
            CaptureMode = CaptureMode.ScreenRegion,
            CaptureRegion = new System.Drawing.Rectangle(0, 0, 640, 480),
            TargetFps = 3,
        });
    }

    private static void AssertTrue(bool value, string message)
    {
        if (!value) throw new InvalidOperationException(message);
    }

    private static void AssertFalse(bool value, string message) { AssertTrue(!value, message); }

    private static void AssertEqual(int expected, int actual, string message)
    {
        if (expected != actual) throw new InvalidOperationException(message + ": expected " + expected + ", got " + actual);
    }

    private sealed class FakeRuntime : ISourceMonitorRuntime
    {
        public MonitorSource Source { get; private set; }
        public bool IsStarted { get; private set; }
        public bool IsReady { get { return true; } }
        public double ActualFps { get { return IsStarted ? 2.5d : 0d; } }
        public string Error { get; private set; }
        public bool FailStart { get; set; }
        public bool FailStop { get; set; }
        public bool Disposed { get; private set; }
        public event EventHandler<AlertEvent> AlertTriggered { add { } remove { } }
        public event EventHandler<FrameResultEventArgs> FrameProcessed { add { } remove { } }

        public FakeRuntime(MonitorSource source) { Source = source; Error = string.Empty; }
        public void Rename(string sourceName) { Source.SourceName = sourceName; }
        public void Start(string modelPath)
        {
            if (FailStart) { Error = "simulated failure"; throw new InvalidOperationException(Error); }
            Error = string.Empty;
            IsStarted = true;
        }
        public void Stop()
        {
            if (FailStop) throw new InvalidOperationException("simulated stop failure");
            IsStarted = false;
        }
        public void UpdateConfig(MonitorConfig config) { Source.Config = config; }
        public void Dispose() { Disposed = true; IsStarted = false; }
    }
}
