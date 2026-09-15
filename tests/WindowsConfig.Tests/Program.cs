using System;
using System.IO;
using System.Linq;
using VisionGuard.Capture;
using VisionGuard.Services;
using VisionGuard.Utils;
using VisionGuard.Models;

internal static class Program
{
    private static int Main(string[] args)
    {
        AssertFalse(string.IsNullOrWhiteSpace(ApiKeyProvider.DefaultApiKey),
            "DefaultApiKey must not be blank for packaged Windows clients.");
        AssertEqual("fallback-key", ApiKeyProvider.Resolve(null, "fallback-key"),
            "missing environment value should use fallback");
        AssertEqual("fallback-key", ApiKeyProvider.Resolve("", "fallback-key"),
            "empty environment value should use fallback");
        AssertEqual("fallback-key", ApiKeyProvider.Resolve("   ", "fallback-key"),
            "whitespace environment value should use fallback");
        AssertEqual("custom-key", ApiKeyProvider.Resolve("  custom-key  ", "fallback-key"),
            "non-empty environment value should be trimmed and used");

        var settingsDirectory = Path.Combine(Path.GetTempPath(), "visionguard-settings-test-" + Guid.NewGuid().ToString("N"));
        var settingsPath = Path.Combine(settingsDirectory, "settings.ini");
        var wpfSettings = new SharedSettingsFile(settingsPath);
        var winFormsSettings = new SharedSettingsFile(settingsPath);
        wpfSettings.Load();
        winFormsSettings.Load();
        wpfSettings.Set("Wpf.Signal.1.Name", "门口");
        winFormsSettings.Set("WinForms.Signal.1.Name", "仓库");
        wpfSettings.Save();
        winFormsSettings.Save();
        var mergedSettings = new SharedSettingsFile(settingsPath);
        mergedSettings.Load();
        AssertEqual("门口", mergedSettings.GetString("Wpf.Signal.1.Name", ""),
            "a later WinForms save must preserve WPF keys written after its load");
        AssertEqual("仓库", mergedSettings.GetString("WinForms.Signal.1.Name", ""),
            "a WinForms save must persist its own dirty keys");
        Directory.Delete(settingsDirectory, true);

        // “信号”→“来源”键名迁移：补写新键、保留旧键、可重复执行，数量键不覆盖用户当前值。
        var migrationDirectory = Path.Combine(Path.GetTempPath(), "visionguard-migration-test-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(migrationDirectory);
        var migrationPath = Path.Combine(migrationDirectory, "settings.ini");
        File.WriteAllText(migrationPath,
            "Signal.1.Name=信号 1\nSignal.2.Name=信号 2\nSignal.2.Cooldown=45\nWinForms.Signal.Count=2\n",
            new System.Text.UTF8Encoding(false));
        var migrationStore = new SharedSettingsFile(migrationPath);
        migrationStore.Load();
        var suffixes = new[] { "Name", "Cooldown" };
        AssertEqual(2, SourceSettingsMigration.Migrate(migrationStore, "Signal.", "Source.", suffixes, 16),
            "migration must report the highest source index that carried data");
        AssertEqual("信号 2", migrationStore.GetString("Source.2.Name", ""),
            "legacy per-source values must be carried over to the unified prefix");
        AssertEqual("信号 2", migrationStore.GetString("Signal.2.Name", ""),
            "legacy keys must stay as a backup after migration");
        AssertEqual(2, SourceSettingsMigration.MigrateCount(migrationStore, "WinForms.Signal.Count", "WinForms.Source.Count", 1),
            "the source count must migrate from its legacy key");
        migrationStore.Save();
        var migratedAgain = new SharedSettingsFile(migrationPath);
        migratedAgain.Load();
        AssertEqual(null, migratedAgain.GetString("Signal.1.Cooldown", null),
            "migration must not invent keys the legacy file never had");
        AssertEqual(2, SourceSettingsMigration.Migrate(migratedAgain, "Signal.", "Source.", suffixes, 16),
            "migration must be idempotent");
        migratedAgain.Set("WinForms.Source.Count", "4");
        migratedAgain.Save();
        var countEdited = new SharedSettingsFile(migrationPath);
        countEdited.Load();
        AssertEqual(4, SourceSettingsMigration.MigrateCount(countEdited, "WinForms.Signal.Count", "WinForms.Source.Count", 1),
            "an existing unified count must win over the legacy value");
        Directory.Delete(migrationDirectory, true);

        var sourceConfig = new MonitorConfig
        {
            AlertCooldownSeconds = 9,
            ConfidenceThreshold = 0.55f,
            TargetFps = 3,
        };
        sourceConfig.WatchedClasses.Add("person");
        var source = new MonitorSource("winforms-source-1", "门口", "yolov5nu_320", sourceConfig);
        var sourcePayload = new MonitorSourceStatus
        {
            SourceId = source.SourceId,
            SourceName = source.SourceName,
            ModelKey = source.ModelKey,
            IsMonitoring = true,
            IsReady = true,
            ActualFps = 2.75,
        }.ToHeartbeatPayload(sourceConfig);
        AssertEqual("winforms-source-1", (string)sourcePayload["sourceId"],
            "WinForms source heartbeat must preserve stable source identity");
        AssertEqual("门口", (string)sourcePayload["sourceName"],
            "WinForms source heartbeat must preserve the editable source name");
        AssertTrue((bool)sourcePayload["isMonitoring"] && (bool)sourcePayload["isReady"],
            "WinForms source heartbeat must expose per-source runtime state");
        AssertTrue(Math.Abs((double)sourcePayload["actualFps"] - 2.75) < 0.001,
            "WinForms source heartbeat must expose measured FPS without silently replacing it");

        AssertFalse(CaptureSizeConstraints.IsValid(100, 101),
            "width equal to 100 must be rejected");
        AssertFalse(CaptureSizeConstraints.IsValid(101, 100),
            "height equal to 100 must be rejected");
        AssertFalse(CaptureSizeConstraints.IsValid(99, 500),
            "width below 100 must be rejected");
        AssertTrue(CaptureSizeConstraints.IsValid(101, 101),
            "both dimensions above 100 must be accepted");

        var highDpiBoundary = CaptureSizeConstraints.MapToCapturePixels(0, 0, 50, 50, 2, 2);
        AssertEqual(100, highDpiBoundary.Width,
            "50 DIP at 200% DPI must be reported as 100 capture pixels");
        AssertFalse(CaptureSizeConstraints.IsValid(highDpiBoundary),
            "a 100x100 capture-pixel selection must be rejected at 200% DPI");

        var highDpiValid = CaptureSizeConstraints.MapToCapturePixels(0, 0, 50.5, 50.5, 2, 2);
        AssertEqual(101, highDpiValid.Width,
            "50.5 DIP at 200% DPI must be reported as 101 capture pixels");
        AssertTrue(CaptureSizeConstraints.IsValid(highDpiValid),
            "a 101x101 capture-pixel selection must be accepted at 200% DPI");

        foreach (var scale in new[] { 1.0, 1.25, 1.5, 1.75, 2.0 })
        {
            var mapped = CaptureSizeConstraints.MapToCapturePixels(8, 12, 320, 180, scale, scale);
            AssertEqual((int)(320 * scale), mapped.Width,
                $"320 DIP width must map consistently at {scale * 100:0}% DPI");
            AssertEqual((int)(180 * scale), mapped.Height,
                $"180 DIP height must map consistently at {scale * 100:0}% DPI");
        }

        var selectedWindow = new WindowInfo(new IntPtr(1), "监控画面", "Chrome_WidgetWin_1",
            new System.Drawing.Rectangle(0, 0, 800, 600), 10, "chrome");
        var sameIdentityNewTitle = new WindowInfo(new IntPtr(2), "监控画面 - 新标题", "Chrome_WidgetWin_1",
            new System.Drawing.Rectangle(0, 0, 800, 600), 10, "chrome");
        var exactMatch = WindowMatchResolver.Resolve(
            new[] { selectedWindow, sameIdentityNewTitle }, "监控画面", "Chrome_WidgetWin_1", "chrome");
        AssertTrue(exactMatch.Status == WindowMatchStatus.Found && exactMatch.Window?.Handle == new IntPtr(1),
            "exact title and stable identity must restore the selected window");

        var changedTitleMatch = WindowMatchResolver.Resolve(
            new[] { sameIdentityNewTitle }, "监控画面", "Chrome_WidgetWin_1", "chrome");
        AssertTrue(changedTitleMatch.Status == WindowMatchStatus.Found && changedTitleMatch.Window?.Handle == new IntPtr(2),
            "a unique process and class identity may restore a window after its title changes");

        var ambiguousMatch = WindowMatchResolver.Resolve(
            new[] { selectedWindow, sameIdentityNewTitle }, "已关闭标题", "Chrome_WidgetWin_1", "chrome");
        AssertTrue(ambiguousMatch.Status == WindowMatchStatus.Ambiguous && ambiguousMatch.Window == null,
            "multiple stable-identity candidates must not be rebound silently");

        var duplicateTitleMatch = WindowMatchResolver.Resolve(
            new[]
            {
                selectedWindow,
                new WindowInfo(new IntPtr(3), "监控画面", "OtherClass", new System.Drawing.Rectangle(0, 0, 800, 600)),
            },
            "监控画面", "", "");
        AssertTrue(duplicateTitleMatch.Status == WindowMatchStatus.Ambiguous,
            "legacy title-only configuration must reject duplicate window titles");

        // 故障分类仍逐来源上报；早已没有“某种故障要全局停机”的策略位（见 30-windows-detector.md）。
        AssertTrue(Enum.IsDefined(typeof(MonitorFailureKind), MonitorFailureKind.BlackFrame),
            "per-source failure kinds must stay available for reporting");
        AssertEqual((int)MonitorFailureKind.None, 0,
            "MonitorFailureKind.None must keep its serialized value");
        AssertEqual("", CapacityPolicy.GetWarning("Cpu", 1, 1),
            "running at the configured capacity must not produce a warning");
        AssertTrue(CapacityPolicy.GetWarning("Cpu", 2, 1).Contains("允许继续运行"),
            "exceeding capacity must warn without representing an admission failure");

        var outboxDirectory = Path.Combine(Path.GetTempPath(), "visionguard-outbox-test-" + Guid.NewGuid().ToString("N"));
        var outboxPath = Path.Combine(outboxDirectory, "outbox.json");
        var outbox = new AlertOutbox(outboxPath);
        outbox.Enqueue("alert-1", "{\"type\":\"alert\"}");
        AssertTrue(new AlertOutbox(outboxPath).Snapshot().Single().AlertId == "alert-1",
            "alert outbox must survive process recreation");
        outbox.Enqueue("alert-1", "{\"type\":\"alert\",\"retry\":true}");
        AssertTrue(outbox.Snapshot().Count == 1,
            "same alert id must replace rather than duplicate an outbox entry");
        AssertTrue(outbox.Acknowledge("alert-1") && new AlertOutbox(outboxPath).Snapshot().Count == 0,
            "acknowledged alert must be removed durably");
        File.WriteAllText(outboxPath, "not-json");
        var recoveredOutbox = new AlertOutbox(outboxPath);
        AssertTrue(recoveredOutbox.Snapshot().Count == 0 && !string.IsNullOrWhiteSpace(recoveredOutbox.RecoveryWarning),
            "a corrupt alert outbox must be isolated and reported explicitly");
        AssertTrue(Directory.GetFiles(outboxDirectory, "outbox.json.corrupt-*").Length == 1,
            "a corrupt alert outbox must be preserved for diagnosis");
        Directory.Delete(outboxDirectory, true);

        using (var blackFrame = new System.Drawing.Bitmap(200, 200))
        {
            AssertTrue(WindowCapturer.IsLikelyBlack(blackFrame),
                "an all-black captured frame must be classified as a black-screen fault");
            blackFrame.SetPixel(40, 100, System.Drawing.Color.White);
            AssertFalse(WindowCapturer.IsLikelyBlack(blackFrame),
                "a captured frame with visible sampled content must not be classified as black");
        }

        var visibleWindows = WindowEnumerator.GetWindows(IntPtr.Zero);
        AssertTrue(visibleWindows.All(window => CaptureSizeConstraints.IsValid(window.Bounds)),
            "window enumeration must return only windows whose width and height are above 100");
        if (args.Length >= 2 && string.Equals(args[0], "--expect-black", StringComparison.Ordinal))
        {
            var blackWindow = visibleWindows.SingleOrDefault(window => string.Equals(window.Title, args[1], StringComparison.Ordinal));
            AssertTrue(blackWindow != null, "the named black-screen test window must be enumerable");
            bool blackScreenRejected = false;
            try
            {
                using var ignored = WindowCapturer.CaptureWindow(blackWindow.Handle, System.Drawing.Rectangle.Empty);
            }
            catch (CaptureBlackFrameException)
            {
                blackScreenRejected = true;
            }
            AssertTrue(blackScreenRejected,
                "an actual all-black PrintWindow result must raise an explicit black-screen fault");
        }
        else if (args.Length > 0)
        {
            AssertFalse(visibleWindows.Any(window => string.Equals(window.Title, args[0], StringComparison.Ordinal)),
                "the named undersized test window must be filtered from enumeration");
        }

        Console.WriteLine("Windows config tests passed.");
        return 0;
    }

    private static void AssertEqual(string expected, string actual, string message)
    {
        if (!string.Equals(expected, actual, StringComparison.Ordinal))
            throw new InvalidOperationException($"{message}: expected '{expected}', got '{actual}'");
    }

    private static void AssertFalse(bool condition, string message)
    {
        if (condition)
            throw new InvalidOperationException(message);
    }

    private static void AssertEqual(int expected, int actual, string message)
    {
        if (expected != actual)
            throw new InvalidOperationException($"{message}: expected '{expected}', got '{actual}'");
    }

    private static void AssertTrue(bool condition, string message)
    {
        if (!condition)
            throw new InvalidOperationException(message);
    }
}
