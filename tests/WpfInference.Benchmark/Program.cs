using System.Diagnostics;
using System.Drawing;
using System.Text.Json;
using System.Threading;
using System.Windows.Controls;
using System.Windows.Threading;
using VisionGuard.Detector.Windows.Inference;
using VisionGuard.Detector.Windows.Runtime;
using VisionGuard.Detector.Windows.Services;
using VisionGuard.Detector.Windows.ViewModels;
using VisionGuard.Detector.Windows.Views;
using WpfMatrix = System.Windows.Media.Matrix;
using WpfMatrixTransform = System.Windows.Media.MatrixTransform;
using WpfRect = System.Windows.Rect;
using WpfSize = System.Windows.Size;

if (args.Length < 1)
{
    Console.Error.WriteLine("Usage: WpfInference.Benchmark <model.onnx> [cpu|directml] [iterations] [streams|image1 image2 image3]");
    Console.Error.WriteLine("       WpfInference.Benchmark <ignored> --layout-plan <report.json>");
    return 2;
}

var modelPath = Path.GetFullPath(args[0]);

if (args.Length >= 2 && args[1].Equals("--settings-persistence", StringComparison.OrdinalIgnoreCase))
    return SettingsPersistenceProbe.Run();

if (args.Length >= 2 && args[1].Equals("--display-name-boundaries", StringComparison.OrdinalIgnoreCase))
    return DisplayNameProbe.Run();

// 与生产程序同一路径：先按档位预加载原生 ONNX Runtime（绝对路径），再建任何推理会话。
// 不做这一步时 DllImport 会按默认搜索顺序找根目录的 onnxruntime.dll，而它按设计已被移走，
// legacy 档会以无诊断信息的进程终止失败。
NativeLibrarySelector.Initialize();

if (args.Length >= 2 && args[1].Equals("--monitor-stop-contract", StringComparison.OrdinalIgnoreCase))
{
    var idleStopWatch = Stopwatch.StartNew();
    for (int sourceIndex = 0; sourceIndex < 4; sourceIndex++)
    {
        using var idleAlerts = new AlertService("idle-" + sourceIndex, "Idle");
        using var idleMonitor = new MonitorService(idleAlerts);
        idleMonitor.Stop();
        idleMonitor.Stop();
    }
    idleStopWatch.Stop();
    var stopReport = new { passed = idleStopWatch.ElapsedMilliseconds < 1000, idleStops = 12, elapsedMs = idleStopWatch.ElapsedMilliseconds };
    var stopJson = JsonSerializer.Serialize(stopReport, new JsonSerializerOptions { WriteIndented = true });
    Console.WriteLine(stopJson);
    if (args.Length == 3) File.WriteAllText(Path.GetFullPath(args[2]), stopJson, new System.Text.UTF8Encoding(false));
    return stopReport.passed ? 0 : 1;
}

// ── 解析契约探针 ───────────────────────────────────────────────────────────
// 目的：证明「所选模型 + 真实图片」经 YoloOutputParser 真的能出目标。
// 2026-09 的 Win7 漏检根因就是 legacy 档用 YOLOv5 [1,84,N] 模型却按 YOLO26 [1,300,6]
// 的下标解析：不报错、不出框、不推送。这个探针把该契约变成机器可判定的检查。
if (args.Length >= 2 && args[1].Equals("--parser-contract", StringComparison.OrdinalIgnoreCase))
{
    if (args.Length is < 4 or > 5)
    {
        Console.Error.WriteLine("Usage: WpfInference.Benchmark <model.onnx> --parser-contract <image> <expected-label> [min-confidence]");
        return 2;
    }

    var imagePath = Path.GetFullPath(args[2]);
    var expectedLabel = args[3];
    var minConfidence = args.Length == 5 && float.TryParse(args[4], System.Globalization.NumberStyles.Float,
        System.Globalization.CultureInfo.InvariantCulture, out var parsedMinimum) ? parsedMinimum : 0.5f;
    if (!File.Exists(imagePath))
    {
        Console.Error.WriteLine($"图片不存在：{imagePath}");
        return 2;
    }

    var contractChecks = new List<object>();
    var contractPassed = true;
    void Check(string name, bool ok, object? detail = null)
    {
        contractChecks.Add(new { name, passed = ok, detail });
        if (!ok) contractPassed = false;
    }

    // 阶段日志：托管/原生 ONNX Runtime 不匹配或原生崩溃时进程会直接终止且不打印异常，
    // 只有逐阶段即时落盘才能把「崩在哪一步」变成可带走的证据。
    var contractStage = "start";
    var stageLogPath = Environment.GetEnvironmentVariable("VISIONGUARD_PARSER_CONTRACT_STAGE_LOG");
    void Stage(string name)
    {
        contractStage = name;
        if (string.IsNullOrWhiteSpace(stageLogPath)) return;
        var fullStagePath = Path.GetFullPath(stageLogPath);
        Directory.CreateDirectory(Path.GetDirectoryName(fullStagePath)!);
        File.AppendAllText(fullStagePath, DateTime.Now.ToString("HH:mm:ss.fff") + " " + name + Environment.NewLine);
    }
    if (!string.IsNullOrWhiteSpace(stageLogPath)) File.WriteAllText(Path.GetFullPath(stageLogPath), "");
    Stage($"begin profile={ProbeProfile.Name} model={Path.GetFileName(modelPath)}");
    Stage("selector legacy=" + NativeLibrarySelector.IsLegacy + " ready=" + NativeLibrarySelector.IsReady
          + " dir=" + NativeLibrarySelector.SelectedDirectory
          + " failure=" + NativeLibrarySelector.FailureReason
          + " loaded=" + NativeLibrarySelector.LoadedOnnxRuntimePath());

    try
    {
        using (var contractEngine = new OnnxInferenceEngine(modelPath, preferredBackend: InferenceBackend.Cpu))
        {
            Stage("engine-created inputSize=" + contractEngine.ModelInputSize);
            var contractInputSize = contractEngine.ModelInputSize;
            float[] contractOutput;
            PreprocessedImage contractPreprocessed;
            using (var contractBitmap = new Bitmap(imagePath))
            {
                contractPreprocessed = ImagePreprocessor.Prepare(contractBitmap, contractInputSize);
                contractOutput = contractEngine.Run(
                    contractPreprocessed.Tensor,
                    ImagePreprocessor.InputShape(contractInputSize));
            }
            Stage("inference-done rawLength=" + contractOutput.Length);

            var rawLength = contractOutput.Length;
            var layout = YoloOutputParser.Describe(rawLength);
            YoloOutputParser.OutputLayout? detectedLayout = null;
            try { detectedLayout = YoloOutputParser.Detect(rawLength); }
            catch (InvalidOperationException) { }
            Stage("layout=" + layout);

            Check("output-layout-recognized", detectedLayout != null, layout);
            Check("output-layout-matches-profile",
                detectedLayout == (ProbeProfile.IsLegacy
                    ? YoloOutputParser.OutputLayout.YoloV5Anchors
                    : YoloOutputParser.OutputLayout.Yolo26EndToEnd),
                $"profile={ProbeProfile.Name}");
            Check("unrelated-tensor-length-rejected", ThrowsOnUnknownLength());
            // 原生 ONNX Runtime 必须来自本档位目录：SYSTEM32 或其它位置的同名库会按模块名抢占，
            // 与托管程序集不同代时表现为一推理就无诊断信息的进程终止（实测）。
            var loadedNative = NativeLibrarySelector.LoadedOnnxRuntimePath();
            Check("native-onnxruntime-from-profile-directory",
                loadedNative.StartsWith(NativeLibrarySelector.SelectedDirectory, StringComparison.OrdinalIgnoreCase),
                $"loaded={loadedNative} expectedDir={NativeLibrarySelector.SelectedDirectory}");
            var allClasses = YoloOutputParser.Parse(contractOutput, contractPreprocessed.Transform, 0.25f, new HashSet<string>());
            Stage("parsed detections=" + allClasses.Count);
            var wanted = allClasses.Where(d => string.Equals(d.Label, expectedLabel, StringComparison.OrdinalIgnoreCase)).ToArray();

            Check("expected-label-detected", wanted.Length > 0,
                $"threshold=0.25 detections=[{string.Join(", ", allClasses.Select(d => $"{d.Label}:{d.Confidence:F3}"))}]");
            Check("expected-label-above-min-confidence", wanted.Any(d => d.Confidence >= minConfidence),
                $"min={minConfidence} max={(wanted.Length == 0 ? 0f : wanted.Max(d => d.Confidence)):F3}");
            Check("boxes-within-frame", allClasses.All(d =>
                    d.BoundingBox.Width > 0 && d.BoundingBox.Height > 0
                    && d.BoundingBox.Left >= -1 && d.BoundingBox.Top >= -1
                    && d.BoundingBox.Right <= contractPreprocessed.Transform.SourceWidth + 1
                    && d.BoundingBox.Bottom <= contractPreprocessed.Transform.SourceHeight + 1),
                string.Join("; ", allClasses.Select(d => $"{d.Label} {d.BoundingBox.X:F0},{d.BoundingBox.Y:F0},{d.BoundingBox.Width:F0}x{d.BoundingBox.Height:F0}")));

            var contractReport = new
            {
                passed = contractPassed,
                model = Path.GetFileName(modelPath),
                image = Path.GetFileName(imagePath),
                profile = ProbeProfile.Name,
                inputSize = contractInputSize,
                rawOutputLength = rawLength,
                rawOutputLayout = layout,
                nativeOnnxRuntime = NativeLibrarySelector.LoadedOnnxRuntimePath(),
                expectedLabel,
                detectedLabel = allClasses.FirstOrDefault()?.Label,
                detections = allClasses.Select(d => new { d.Label, confidence = Math.Round(d.Confidence, 4),
                    box = new { x = Math.Round(d.BoundingBox.X, 1), y = Math.Round(d.BoundingBox.Y, 1),
                                w = Math.Round(d.BoundingBox.Width, 1), h = Math.Round(d.BoundingBox.Height, 1) } }),
                checks = contractChecks,
            };
            var contractJson = JsonSerializer.Serialize(contractReport, new JsonSerializerOptions { WriteIndented = true });
            var contractReportPath = Environment.GetEnvironmentVariable("VISIONGUARD_PARSER_CONTRACT_REPORT");
            if (!string.IsNullOrWhiteSpace(contractReportPath))
            {
                var fullContractPath = Path.GetFullPath(contractReportPath);
                Directory.CreateDirectory(Path.GetDirectoryName(fullContractPath)!);
                File.WriteAllText(fullContractPath, contractJson);
            }
            Stage("report-written passed=" + contractPassed);
            Console.WriteLine(contractJson);
            return contractPassed ? 0 : 1;
        }
    }
    catch (Exception ex)
    {
        Stage("exception " + ex.GetType().Name + ": " + ex.Message);
        Console.Error.WriteLine("解析契约探针在第 " + contractStage + " 阶段失败：" + ex.GetType().Name + ": " + ex.Message);
        return 1;
    }
}

// ── 有效来源与临时配置生命周期 ──────────────────────────────────
if (args.Length >= 2 && args[1].Equals("--source-lifecycle", StringComparison.OrdinalIgnoreCase))
{
    if (args.Length != 3) return 2;
    var file = Path.GetFullPath(args[2]); Directory.CreateDirectory(Path.GetDirectoryName(file)!);
    Environment.SetEnvironmentVariable("VISIONGUARD_ACCOUNT_DIR", Path.Combine(Path.GetDirectoryName(file)!, "lifecycle-account-"+Guid.NewGuid().ToString("N")));
    Environment.SetEnvironmentVariable("VISIONGUARD_SETTINGS_PATH",file);
    var store=typeof(MultiSourceViewModel).Assembly.GetType("VisionGuard.Detector.Windows.Utils.SettingsStore",true)!;
    var checks=new List<object>(); bool passed=true;
    void Check(string name,bool ok) {checks.Add(new{name,passed=ok}); if(!ok)passed=false;}
    void Seed(string indexes,string extra) { File.WriteAllText(file,"Source.Indexes="+indexes+"\nSource.LegacyMigrationCompleted=True\nSource.KeyMigrationCompleted=True\n"+extra,new System.Text.UTF8Encoding(false));store.GetMethod("Load")!.Invoke(null,null); }
    Exception? failure=null;
    var thread=new Thread(()=>{try {
      var env=new SettingsViewModel();
      Seed("1,2,3,4","Source.1.Name=empty\nSource.2.Name=bound\nSource.2.CaptureMode=ScreenRegion\nSource.2.ScreenRegion=0,0,320,240\nSource.3.CaptureMode=ScreenRegion\nSource.3.ScreenRegion=0,0,100,100\nSource.4.CaptureMode=RemoteStream\nSource.4.RemoteStreamId=offline-bound\n");
      using(var vm=new MultiSourceViewModel(null!,env)) {
        Check("only valid configurations retained",vm.Sources.Select(x=>x.Index).SequenceEqual(new[]{2,4}));
        Check("offline bound remote source retained",vm.Sources[1].IsTargetBound && !vm.Sources[1].IsReady);
        var before=File.ReadAllText(file); var draft=(SourceViewModel)Activator.CreateInstance(typeof(SourceViewModel), System.Reflection.BindingFlags.Instance|System.Reflection.BindingFlags.NonPublic,null,new object[]{1,vm,true},null)!;
        draft.SourceName="temporary"; draft.ThresholdPercent=80;
        typeof(SourceViewModel).GetMethod("NotifyTargetChanged",System.Reflection.BindingFlags.Instance|System.Reflection.BindingFlags.NonPublic)!.Invoke(draft,null);
        Check("draft never reaches persisted source list",vm.Sources.Count==2 && File.ReadAllText(file)==before);
        var source=vm.Sources[0]; var field=typeof(SourceViewModel).GetField("_screenRegion",System.Reflection.BindingFlags.Instance|System.Reflection.BindingFlags.NonPublic)!;
        var change=typeof(SourceViewModel).GetMethod("ChangeCaptureTarget",System.Reflection.BindingFlags.Instance|System.Reflection.BindingFlags.NonPublic)!;
        bool rejected=false;
        using(var locked=new FileStream(file,FileMode.Open,FileAccess.Read,FileShare.Read)) {
          try { change.Invoke(source,new object[]{(Action)(()=>field.SetValue(source,new System.Drawing.Rectangle(5,5,640,360)))}); } catch(System.Reflection.TargetInvocationException) {rejected=true;}
        }
        Check("failed capture replacement rolls back configuration",rejected && (System.Drawing.Rectangle)field.GetValue(source)! == new System.Drawing.Rectangle(0,0,320,240));
        Check("failed capture replacement preserves disk",File.ReadAllText(file)==before);
        vm.SelectedSource=vm.Sources[1]; typeof(MultiSourceViewModel).GetMethod("ApplySourceLimit",System.Reflection.BindingFlags.Instance|System.Reflection.BindingFlags.NonPublic)!.Invoke(vm,new object[]{1});
        Check("capacity handles sparse source indexes",vm.Sources.Count==1 && vm.SelectedSource?.Index==2);
        var canRemove=(bool)typeof(MultiSourceViewModel).GetMethod("CanRemoveSource",System.Reflection.BindingFlags.Instance|System.Reflection.BindingFlags.NonPublic)!.Invoke(vm,new object[]{vm.Sources[0]})!;
        Check("last configured local source removable",canRemove);
      }
      Seed("",""); using(var empty=new MultiSourceViewModel(null!,env)) {Check("empty list needs no placeholder source",!empty.HasSources && empty.SelectedSource==null); Check("empty list allows creation",empty.AddSourceCommand.CanExecute(null));}
      Seed(string.Join(",",Enumerable.Range(1,16)),string.Join("\n",Enumerable.Range(1,16).Select(i=>$"Source.{i}.CaptureMode=ScreenRegion\nSource.{i}.ScreenRegion=0,0,320,240")));
      using(var many=new MultiSourceViewModel(null!,env)) {Check("all sixteen configured sources available",many.Sources.Count==16); many.SelectedSource=many.Sources[15]; Check("sixteenth source selectable",many.SelectedSource?.Index==16);}
    }catch(Exception error){failure=error;}});thread.SetApartmentState(ApartmentState.STA);thread.Start();thread.Join();
    Check("lifecycle probe succeeds",failure==null);
    var json=JsonSerializer.Serialize(new{passed,checks,error=failure?.ToString()},new JsonSerializerOptions{WriteIndented=true});File.WriteAllText(file+".report.json",json,new System.Text.UTF8Encoding(false));Console.WriteLine(json);return passed?0:1;
}

// ── 画面等比与三分区组件补充检查 ──────────────────────────────
if (args.Length >= 2 && args[1].Equals("--layout-plan", StringComparison.OrdinalIgnoreCase))
{
    if (args.Length != 3) return 2;
    var checks = new List<object>(); bool passed = true;
    void CheckLayout(string name, bool ok, object? detail = null) { checks.Add(new { name, passed = ok, detail }); if (!ok) passed = false; }
    // 8) 等比留白输入：方形无黑边，2:1 边界仍保留一半有效面积，3:1 / 1:3 居中留黑边。
    // 同时检查张量中留白位置真的是纯黑，而不是 Bitmap 默认值或拉伸后的图像残留。
    void CheckLetterbox(string name, int width, int height, int expectedWidth, int expectedHeight, int expectedLeft, int expectedTop)
    {
        const int inputSize = 32;
        using var bitmap = new Bitmap(width, height);
        using (var graphics = Graphics.FromImage(bitmap)) graphics.Clear(System.Drawing.Color.Red);
        var prepared = ImagePreprocessor.Prepare(bitmap, inputSize);
        var transform = prepared.Transform;
        int blackIndex = 0;
        int contentX = transform.PadLeft + transform.ResizedWidth / 2;
        int contentY = transform.PadTop + transform.ResizedHeight / 2;
        int contentIndex = contentY * inputSize + contentX;
        bool hasBlackBorder = transform.PadLeft > 0 || transform.PadTop > 0;
        CheckLayout(name,
            prepared.Tensor.Length == 3 * inputSize * inputSize
            && transform.ResizedWidth == expectedWidth && transform.ResizedHeight == expectedHeight
            && transform.PadLeft == expectedLeft && transform.PadTop == expectedTop
            && (!hasBlackBorder || Math.Abs(prepared.Tensor[blackIndex]) < 0.001f)
            && prepared.Tensor[contentIndex] > 0.95f,
            new { transform.ResizedWidth, transform.ResizedHeight, transform.PadLeft, transform.PadTop, transform.EffectiveAreaRatio, black = prepared.Tensor[blackIndex], red = prepared.Tensor[contentIndex] });
    }

    CheckLetterbox("1:1 输入无黑边", 100, 100, 32, 32, 0, 0);
    CheckLetterbox("2:1 输入等比留黑边", 200, 100, 32, 16, 0, 8);
    CheckLetterbox("3:1 输入等比留黑边", 300, 100, 32, 11, 0, 10);
    CheckLetterbox("1:3 输入等比留黑边", 100, 300, 11, 32, 10, 0);

    // 9) 两种模型输出都要使用同一变换还原坐标：跨黑边的框裁剪，纯黑边中的框丢弃。
    var letterbox = LetterboxTransform.Create(300, 100, 300);
    var modernOutput = new float[300 * 6];
    modernOutput[0] = 60; modernOutput[1] = 110; modernOutput[2] = 120; modernOutput[3] = 150; modernOutput[4] = 0.9f; modernOutput[5] = 0;
    modernOutput[6] = 10; modernOutput[7] = 0; modernOutput[8] = 30; modernOutput[9] = 50; modernOutput[10] = 0.9f; modernOutput[11] = 0;
    var modernLetterboxDetections = YoloOutputParser.Parse(modernOutput, letterbox, 0.5f, new HashSet<string> { "person" });
    CheckLayout("YOLO26 留白坐标回映且过滤纯黑边框",
        modernLetterboxDetections.Count == 1
        && Math.Abs(modernLetterboxDetections[0].BoundingBox.X - 60) < 0.01f
        && Math.Abs(modernLetterboxDetections[0].BoundingBox.Y - 10) < 0.01f
        && Math.Abs(modernLetterboxDetections[0].BoundingBox.Width - 60) < 0.01f
        && Math.Abs(modernLetterboxDetections[0].BoundingBox.Height - 40) < 0.01f,
        modernLetterboxDetections.Select(detection => detection.BoundingBox).ToArray());

    const int legacyAnchors = 2100;
    var legacyOutput = new float[84 * legacyAnchors];
    legacyOutput[0 * legacyAnchors] = 90;
    legacyOutput[1 * legacyAnchors] = 130;
    legacyOutput[2 * legacyAnchors] = 60;
    legacyOutput[3 * legacyAnchors] = 40;
    legacyOutput[4 * legacyAnchors] = 0.9f;
    var legacyLetterboxDetections = YoloOutputParser.Parse(legacyOutput, letterbox, 0.5f, new HashSet<string> { "person" });
    CheckLayout("YOLOv5 留白坐标回映", legacyLetterboxDetections.Count == 1
        && Math.Abs(legacyLetterboxDetections[0].BoundingBox.X - 60) < 0.01f
        && Math.Abs(legacyLetterboxDetections[0].BoundingBox.Y - 10) < 0.01f
        && Math.Abs(legacyLetterboxDetections[0].BoundingBox.Width - 60) < 0.01f
        && Math.Abs(legacyLetterboxDetections[0].BoundingBox.Height - 40) < 0.01f,
        legacyLetterboxDetections.Select(detection => detection.BoundingBox).ToArray());


    Exception? failure = null;
    var thread = new Thread(() => {
      try {
        var app = new System.Windows.Application(); app.Resources.MergedDictionaries.Add(new System.Windows.ResourceDictionary { Source = new Uri("/VisionGuard.Detector.Windows;component/Themes/DarkTheme.xaml", UriKind.Relative) });
        foreach (var dimensions in new[] { new WpfSize(300,100),new WpfSize(100,300),new WpfSize(300,300) }) {
          var canvas = new Grid { Width=dimensions.Width,Height=dimensions.Height }; var presenter = new UniformFramePresenter { Child=canvas };
          presenter.Measure(new WpfSize(400,200)); presenter.Arrange(new WpfRect(0,0,400,200));
          var matrix = ((System.Windows.Media.MatrixTransform)canvas.RenderTransform).Matrix;
          CheckLayout("preview preserves aspect "+dimensions, Math.Abs(matrix.M11-matrix.M22)<.001 && presenter.DesiredSize.Width == 0 && presenter.DesiredSize.Height == 0);
        }
        var primary = new SourcePreviewCard { IsPrimary=true }; primary.Measure(new WpfSize(700,600)); primary.Arrange(new WpfRect(0,0,700,600));
        CheckLayout("primary stretches after dependency property initialization", double.IsNaN(primary.Height) && primary.ActualHeight == 600);
        var secondary = new SourcePreviewCard(); secondary.Measure(new WpfSize(260,double.PositiveInfinity)); secondary.Arrange(new WpfRect(0,0,260,195)); secondary.UpdateLayout();
        CheckLayout("source card bounded height",secondary.Height>=156 && secondary.Height<=420);
        var page = new GlobalSettingsPage(); page.Measure(new WpfSize(696,420)); page.Arrange(new WpfRect(0,0,696,420));
        CheckLayout("five settings categories",((System.Windows.Controls.ListBox)page.FindName("CategoryList")).Items.Count==5);
        var button = new System.Windows.Controls.Button { Style=(System.Windows.Style)app.Resources["PaneButton"] };
        var box = new System.Windows.Controls.TextBox { Style=(System.Windows.Style)app.Resources["PaneTextBox"] };
        var combo = new System.Windows.Controls.ComboBox { Style=(System.Windows.Style)app.Resources["PaneComboBox"] };
        CheckLayout("menu controls share height",button.Height==36 && box.Height==36 && combo.Height==36);
        // 回归分隔条边界：调用真实窗口使用的限制函数，再量测 WPF 三列。
        var limitMethod = typeof(MainWindow).GetMethod("LimitSidePaneWidth", System.Reflection.BindingFlags.Static | System.Reflection.BindingFlags.NonPublic)!;
        double Limit(double request, double other, double area, double min, double max, bool compact = false)
          => (double)limitMethod.Invoke(null, new object[] {request,other,area,min,max,compact})!;
        var panes = new Grid();
        var mainColumn = new ColumnDefinition { Width = new System.Windows.GridLength(1, System.Windows.GridUnitType.Star), MinWidth = 240 };
        var sourcesColumn = new ColumnDefinition { Width = new System.Windows.GridLength(280) };
        var inspectorColumn = new ColumnDefinition { Width = new System.Windows.GridLength(320) };
        panes.ColumnDefinitions.Add(mainColumn);
        panes.ColumnDefinitions.Add(new ColumnDefinition { Width = new System.Windows.GridLength(8) });
        panes.ColumnDefinitions.Add(sourcesColumn);
        panes.ColumnDefinitions.Add(new ColumnDefinition { Width = new System.Windows.GridLength(8) });
        panes.ColumnDefinitions.Add(inspectorColumn);
        void ArrangePanes() { panes.Measure(new WpfSize(1100,600)); panes.Arrange(new WpfRect(0,0,1100,600)); panes.UpdateLayout(); }
        ArrangePanes();
        sourcesColumn.Width = new System.Windows.GridLength(Limit(900,inspectorColumn.ActualWidth,1100,240,380)); ArrangePanes();
        CheckLayout("source drag clamps live without resizing inspector", sourcesColumn.ActualWidth==380 && inspectorColumn.ActualWidth==320 && mainColumn.ActualWidth>=240);
        inspectorColumn.Width = new System.Windows.GridLength(Limit(900,sourcesColumn.ActualWidth,1100,300,420)); ArrangePanes();
        CheckLayout("inspector can widen without resizing sources", inspectorColumn.ActualWidth==420 && sourcesColumn.ActualWidth==380 && mainColumn.ActualWidth>=240);
        CheckLayout("inspector stops at lower bound",Limit(-100,380,1100,300,420)==300);
        CheckLayout("sources stop at lower bound",Limit(-100,320,1100,240,380)==240);
        CheckLayout("reverse from upper bound follows pointer",Limit(410,380,1100,300,420)==410);
        CheckLayout("narrow wide mode reserves main preview",Limit(380,420,1042,240,380)==366);
        CheckLayout("compact resize uses one separator",Limit(900,0,682,240,380,true)==380);
      } catch(Exception error) { failure=error; }
    }); thread.SetApartmentState(ApartmentState.STA); thread.Start(); thread.Join();
    CheckLayout("component layout succeeds",failure==null,failure?.ToString());
    var json=JsonSerializer.Serialize(new {passed,checks},new JsonSerializerOptions{WriteIndented=true}); File.WriteAllText(args[2],json,new System.Text.UTF8Encoding(false)); Console.WriteLine(json); return passed?0:1;
}

// 2026-09-20 起取代容量基线：不再让用户配置「DirectML 几路 / CPU 几路」，直接看实测帧率。
if (args.Length >= 2 && args[1].Equals("--performance-watchdog", StringComparison.OrdinalIgnoreCase))
{
    if (args.Length != 3)
    {
        Console.Error.WriteLine("Usage: WpfInference.Benchmark <ignored> --performance-watchdog <report.json>");
        return 2;
    }

    var watchdogChecks = new List<object>();
    var watchdogPassed = true;
    void CheckWatchdog(string name, bool ok, object? detail = null)
    {
        watchdogChecks.Add(new { name, passed = ok, detail });
        if (!ok) watchdogPassed = false;
    }

    // 1) 判定边界：目标 3 FPS 时低于 2.4 才算不足，正好 2.4 不算（严格小于，带浮点容差）。
    CheckWatchdog("2.39/3.0 视为不达标", PerformanceWatchdog.IsBelowTarget(2.39, 3, true));
    CheckWatchdog("2.40/3.0 视为达标（边界严格小于）", !PerformanceWatchdog.IsBelowTarget(2.40, 3, true));
    CheckWatchdog("2.30/3.0 视为不达标", PerformanceWatchdog.IsBelowTarget(2.30, 3, true));
    CheckWatchdog("3.00/3.0 视为达标", !PerformanceWatchdog.IsBelowTarget(3, 3, true));
    CheckWatchdog("未运行时不判定", !PerformanceWatchdog.IsBelowTarget(0.5, 3, false));
    CheckWatchdog("尚无帧（fps=0）不判定", !PerformanceWatchdog.IsBelowTarget(0, 3, true));

    // 2) 持续时间：29.9 秒不给提示，30 秒开始给；达标时立刻不给提示。
    CheckWatchdog("持续 29.9 秒不给提示", PerformanceWatchdog.GetWarning(1, 3, 29.9, true).Length == 0);
    var sustained = PerformanceWatchdog.GetWarning(1, 3, 30, true);
    CheckWatchdog("持续 30 秒给出提示且含目标与实际",
        sustained.IndexOf("目标 3.0", StringComparison.Ordinal) >= 0
        && sustained.IndexOf("实际 1.0", StringComparison.Ordinal) >= 0
        && sustained.IndexOf("允许继续运行", StringComparison.Ordinal) >= 0,
        new { sustained });
    CheckWatchdog("达标时即使持续很久也不提示", PerformanceWatchdog.GetWarning(3, 3, 600, true).Length == 0);

    // 3) 口径常量固定：80% 阈值、30 秒持续、10 分钟弹窗冷却。
    CheckWatchdog("阈值/持续/冷却常量",
        Math.Abs(PerformanceWatchdog.TargetRatio - 0.8) < 0.0001
        && Math.Abs(PerformanceWatchdog.SustainedSeconds - 30) < 0.0001
        && Math.Abs(PerformanceWatchdog.AlertCooldownMinutes - 10) < 0.0001,
        new { PerformanceWatchdog.TargetRatio, PerformanceWatchdog.SustainedSeconds, PerformanceWatchdog.AlertCooldownMinutes });

    var watchdogReport = new { passed = watchdogPassed, probe = "performance-watchdog", checks = watchdogChecks };
    var watchdogJson = JsonSerializer.Serialize(watchdogReport, new JsonSerializerOptions { WriteIndented = true });
    var watchdogReportPath = Path.GetFullPath(args[2]);
    Directory.CreateDirectory(Path.GetDirectoryName(watchdogReportPath)!);
    File.WriteAllText(watchdogReportPath, watchdogJson, new System.Text.UTF8Encoding(false));
    Console.WriteLine(watchdogJson);
    return watchdogPassed ? 0 : 1;
}

// ── 模型下载契约探针 ───────────────────────────────────────────────────────
// 目的：驱动「全局设定」页模型清单里那个下载按钮背后的同一条 ViewModel 路径，
// 断言状态机与落盘结果。默认打生产服务器（模型下载本来就是线上行为），
// 用 --model-download <modelKey> [expectedBytes] 指定要下载的模型。
if (args.Length >= 2 && args[1].Equals("--model-download", StringComparison.OrdinalIgnoreCase))
{
    if (args.Length is < 3 or > 4)
    {
        Console.Error.WriteLine("Usage: WpfInference.Benchmark <ignored-or-model-path> --model-download <modelKey> [expectedBytes]");
        return 2;
    }

    var downloadKey = args[2];
    long expectedBytes = args.Length == 4 && long.TryParse(args[3], out var parsedBytes) ? parsedBytes : 0;
    var downloadChecks = new List<object>();
    var downloadPassed = true;
    void CheckDownload(string name, bool ok, object? detail = null)
    {
        downloadChecks.Add(new { name, passed = ok, detail });
        if (!ok) downloadPassed = false;
    }

    var downloadPath = VisionGuard.Detector.Windows.Utils.ModelManager.GetModelPath(downloadKey);
    if (File.Exists(downloadPath)) File.Delete(downloadPath);
    var tempPath = downloadPath + ".tmp";
    if (File.Exists(tempPath)) File.Delete(tempPath);

    var viewModel = new SettingsViewModel();
    var option = viewModel.Models.FirstOrDefault(m => m.Key == downloadKey);
    if (option == null)
    {
        Console.Error.WriteLine($"模型 {downloadKey} 不在本档位清单：" + string.Join(", ", viewModel.Models.Select(m => m.Key)));
        return 2;
    }

    var states = new List<string>();
    var progressSamples = new List<int>();
    option.PropertyChanged += (_, e) =>
    {
        states.Add($"{e.PropertyName}:downloading={option.IsDownloading},progress={option.Progress},downloaded={option.IsDownloaded}");
        if (e.PropertyName == nameof(ModelOption.Progress) && option.Progress > 0) progressSamples.Add(option.Progress);
    };

    var started = Stopwatch.StartNew();
    CheckDownload("command-invokable", option.DownloadCommand.CanExecute(null), $"canExecute={option.DownloadCommand.CanExecute(null)} status={option.StatusText}");
    option.DownloadCommand.Execute(null);
    // 命令是 async void：立刻给出「进入下载中」的机会，再等到状态机收敛。
    Thread.Sleep(200);
    CheckDownload("entered-downloading", option.IsDownloading || option.IsDownloaded,
        $"downloading={option.IsDownloading} downloaded={option.IsDownloaded} status={option.StatusText}");

    while (started.Elapsed < TimeSpan.FromMinutes(5) && option.IsDownloading) Thread.Sleep(200);
    started.Stop();

    CheckDownload("download-reported-success", option.IsDownloaded, $"status={option.StatusText} notice={viewModel.ModelDownloadProgress}");
    CheckDownload("file-on-disk", File.Exists(downloadPath), downloadPath);
    CheckDownload("temp-file-cleaned", !File.Exists(tempPath), tempPath);
    if (expectedBytes > 0)
    {
        long actual = File.Exists(downloadPath) ? new FileInfo(downloadPath).Length : 0;
        CheckDownload("file-size-matches-server", actual == expectedBytes, $"actual={actual} expected={expectedBytes}");
    }
    CheckDownload("progress-observed", progressSamples.Count > 0, $"samples={progressSamples.Count} last={(progressSamples.Count > 0 ? progressSamples[progressSamples.Count - 1] : 0)}");
    CheckDownload("available-model-list-updated", MultiSourceViewModel.AvailableModelKeys().Contains(downloadKey),
        string.Join(",", MultiSourceViewModel.AvailableModelKeys()));
    // 失败路径必须给出可读原因：否则用户机器上只看到“下载失败”，无法区分网络、证书还是服务端缺文件。
    CheckDownload("failure-reason-captured-or-cleared",
        option.IsDownloaded ? string.IsNullOrEmpty(VisionGuard.Detector.Windows.Utils.ModelManager.LastFailureReason)
                            : !string.IsNullOrEmpty(VisionGuard.Detector.Windows.Utils.ModelManager.LastFailureReason),
        option.IsDownloaded ? "(download ok; reason cleared)" : VisionGuard.Detector.Windows.Utils.ModelManager.LastFailureReason);
    // 本档位清单里必须只出现本档位模型：档位判定若晚于 ModelManager 的静态初始化，
    // legacy 档会列出 yolo26* 并去下载本档位跑不了的模型（2026-09-17 实测）。
    var profileKeys = viewModel.Models.Select(m => m.Key).ToArray();
    CheckDownload("model-list-matches-profile",
        profileKeys.All(k => ProbeProfile.IsLegacy ? k.StartsWith("yolov5", StringComparison.Ordinal) : k.StartsWith("yolo26", StringComparison.Ordinal)),
        string.Join(",", profileKeys));

    var downloadReport = new
    {
        passed = downloadPassed,
        modelKey = downloadKey,
        downloadPath,
        profile = ProbeProfile.Name,
        elapsedMs = started.ElapsedMilliseconds,
        sizeBytes = File.Exists(downloadPath) ? new FileInfo(downloadPath).Length : 0,
        finalStatus = option.StatusText,
        notice = viewModel.ModelDownloadProgress,
        progressSamples = progressSamples.Count,
        stateTransitions = states.Distinct().Take(20),
        checks = downloadChecks,
    };
    Console.WriteLine(JsonSerializer.Serialize(downloadReport, new JsonSerializerOptions { WriteIndented = true }));
    var downloadReportPath = Environment.GetEnvironmentVariable("VISIONGUARD_MODEL_DOWNLOAD_REPORT");
    if (!string.IsNullOrWhiteSpace(downloadReportPath))
    {
        var fullDownloadPath = Path.GetFullPath(downloadReportPath);
        Directory.CreateDirectory(Path.GetDirectoryName(fullDownloadPath)!);
        File.WriteAllText(fullDownloadPath, JsonSerializer.Serialize(downloadReport, new JsonSerializerOptions { WriteIndented = true }));
    }
    return downloadPassed ? 0 : 1;
}

// ── 来源参数自动保存 / 采集目标重置契约 ────────────────────────────────────
// 目的：逐项编辑的草稿不会提前保存；基础属性与采集目标仍按其自动保存契约落盘。
// 驱动真实 SourceViewModel 与参数行，再回读隔离 settings 文件；
// 再验证采集目标三件套的重置把窗口/选区/遮罩一起清掉并立即落盘。
// 需要 VISIONGUARD_SETTINGS_PATH 指向隔离文件，避免动到真实配置。
if (args.Length >= 2 && args[1].Equals("--source-autosave", StringComparison.OrdinalIgnoreCase))
{
    if (args.Length != 3)
    {
        Console.Error.WriteLine("Usage: WpfInference.Benchmark <ignored> --source-autosave <settings-path>");
        return 2;
    }

    var settingsPath = Path.GetFullPath(args[2]);
    var probeAccountRoot = Path.Combine(Path.GetDirectoryName(settingsPath)!, "parameter-probe-" + Guid.NewGuid().ToString("N"));
    Environment.SetEnvironmentVariable("VISIONGUARD_ACCOUNT_DIR", probeAccountRoot);
    Environment.SetEnvironmentVariable("VISIONGUARD_LOG_DIR", Path.Combine(probeAccountRoot, "logs"));
    Environment.SetEnvironmentVariable("VISIONGUARD_SETTINGS_PATH", settingsPath);
    if (File.Exists(settingsPath)) File.Delete(settingsPath);
    // 种子文件先写到旁边再改名：确认探针读到的是完整内容，而不是写了一半的文件。
    var seedPath = settingsPath + ".seed";
    File.WriteAllText(seedPath,
        "# VisionGuard 用户设置（自动保存契约验证）" + Environment.NewLine +
        "DeviceId=autosave-probe" + Environment.NewLine +
        "Source.Indexes=1" + Environment.NewLine +
        // 两个迁移标记必须先置位：否则 MultiSourceViewModel 的构造会把旧的单来源键迁移过来，
        // 直接覆盖这里预置的来源 1 配置，探针就测不到真实场景。
        "Source.LegacyMigrationCompleted=True" + Environment.NewLine +
        "Source.KeyMigrationCompleted=True" + Environment.NewLine +
        "Source.1.Initialized=True" + Environment.NewLine +
        "Source.1.Name=自动保存探针" + Environment.NewLine +
        "Source.1.CaptureMode=ScreenRegion" + Environment.NewLine +
        "Source.1.ScreenRegion=10,20,320,240" + Environment.NewLine +
        "Source.1.Masks=0.1,0.1,0.2,0.2" + Environment.NewLine +
        "Source.1.Threshold=45" + Environment.NewLine +
        "Source.1.Fps=3" + Environment.NewLine +
        "Source.1.Cooldown=5" + Environment.NewLine +
        "Source.1.Targets=person" + Environment.NewLine,
        new System.Text.UTF8Encoding(false));
    File.Move(seedPath, settingsPath);
    Environment.SetEnvironmentVariable("VISIONGUARD_SETTINGS_PATH", settingsPath);
    // 与真实启动入口一样先加载设置，避免未加载的共享存储跳过持久化。
    typeof(MultiSourceViewModel).Assembly.GetType("VisionGuard.Detector.Windows.Utils.SettingsStore", true)!
        .GetMethod("Load")!.Invoke(null, null);

    var autoSaveChecks = new List<object>();
    var stageLogPath = settingsPath + ".stages.log";
    File.WriteAllText(stageLogPath, string.Empty);
    void Stage(string message) => File.AppendAllText(stageLogPath, DateTime.Now.ToString("HH:mm:ss.fff") + " " + message + Environment.NewLine);
    var autoSavePassed = true;
    void CheckAutoSave(string name, bool ok, object? detail = null)
    {
        autoSaveChecks.Add(new { name, passed = ok, detail });
        if (!ok) autoSavePassed = false;
    }

    string ReadSetting(string key)
    {
        foreach (var line in File.ReadAllLines(settingsPath))
        {
            int separator = line.IndexOf('=');
            if (separator > 0 && line.Substring(0, separator).Trim() == key) return line.Substring(separator + 1).Trim();
        }
        return string.Empty;
    }

    // 防抖定时器是 DispatcherTimer：必须让 Dispatcher 真正跑起来，否则 Tick 永远不触发。
    var dispatcherDone = new ManualResetEventSlim(false);

    var thread = new Thread(() =>
    {
        // Dispatcher 必须在工作线程上创建：探针的 ViewModel 与定时器都要挂在这个线程的消息循环上。
        var dispatcher = Dispatcher.CurrentDispatcher;
        var timeoutTimer = new DispatcherTimer(DispatcherPriority.Normal, dispatcher) { Interval = TimeSpan.FromSeconds(30) };
        timeoutTimer.Tick += (_, _) => { timeoutTimer.Stop(); dispatcherDone.Set(); };
        timeoutTimer.Start();

        // 不创建 System.Windows.Application：它自带消息泵，会和这里的 Dispatcher.Run() 抢消息，
        // 实测会让探针的定时器永远不被调度。本模式只驱动 ViewModel + DispatcherTimer，不需要 Application。
        Stage("skipping-application");
        var coordinator = new MultiSourceViewModel(null!, new SettingsViewModel());
        Stage("coordinator-created sources=" + coordinator.Sources.Count);
        var source = coordinator.Sources[0];
        Stage("source-ready pending=[" + source.PendingApplyText + "] target=[" + source.TargetInfo + "] mask=[" + source.MaskInfo
            + "] captureMode=[" + ReadSetting("Source.1.CaptureMode") + "] screenRegion=[" + ReadSetting("Source.1.ScreenRegion")
            + "] masks=[" + ReadSetting("Source.1.Masks") + "]");

        // 1) 打开时不应有「已保存待生效」的项：磁盘上的值就是已生效值。
        CheckAutoSave("no-pending-on-load", !source.HasPendingApply, source.PendingApplyText);
        var applyStatus = typeof(SourceViewModel).GetMethod("ApplyStatus", System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic)!;
        using (var runningEditor = new SourceParametersEditorViewModel(source))
        {
            runningEditor.Confidence = 70;
            applyStatus.Invoke(source, new object[] { new VisionGuard.Detector.Windows.Models.MonitorSourceStatus { IsMonitoring = true, ActualFps = 3 } });
            CheckAutoSave("running-parameters-readonly", !runningEditor.CanEdit && !runningEditor.CanSave && !runningEditor.TrySave() && source.ThresholdPercent == 45);
            CheckAutoSave("running-fps-shows-current-measurement", source.FpsText == "3.0 FPS");
            applyStatus.Invoke(source, new object[] { new VisionGuard.Detector.Windows.Models.MonitorSourceStatus { IsMonitoring = false, ActualFps = 3 } });
            CheckAutoSave("stopped-fps-does-not-show-stale-measurement", source.FpsText == "0.0 FPS" && source.ActualFps == 3);
        }

        using (var editor = new SourceParametersEditorViewModel(source))
        {
            editor.Confidence = 70; editor.Fps = 2; editor.Cooldown = 30;
            CheckAutoSave("parameter-draft-isolated", source.ThresholdPercent == 45 && source.TargetFps == 3 && source.Cooldown == 5 && ReadSetting("Source.1.Threshold") == "45");
            editor.Confidence = 96; editor.Fps = 0; editor.Cooldown = 301;
            CheckAutoSave("parameter-selection-bounds", editor.Confidence == 95 && editor.Fps == 1 && editor.Cooldown == 300);
            editor.RestoreCommand.Execute(null);
            CheckAutoSave("parameter-restore-current", editor.Confidence == 45 && editor.Fps == 3 && editor.Cooldown == 5 && !editor.CanSave);
            editor.Targets.Single(x => x.EnglishName == "person").IsSelected = false;
            CheckAutoSave("parameter-last-target-protected", editor.Targets.Count(x => x.IsSelected) == 1 && editor.Message.Length > 0);
            editor.Search = "car";
            CheckAutoSave("parameter-target-search", editor.FilteredTargets.Any(x => x.EnglishName == "car") && editor.FilteredTargets.All(x => (x.EnglishName + x.ChineseName).IndexOf("car", StringComparison.OrdinalIgnoreCase) >= 0));
            editor.Confidence = 70;
            source.Cooldown = 6;
            CheckAutoSave("parameter-stale-draft-blocked", editor.SourceChanged && !editor.CanSave && !editor.TrySave() && source.ThresholdPercent == 45);
            editor.RestoreCommand.Execute(null);
            CheckAutoSave("parameter-restore-refreshes-baseline", editor.Cooldown == 6 && !editor.SourceChanged);
            editor.ModelKey = "unsupported-model";
            CheckAutoSave("parameter-unavailable-model-blocked", !editor.CanSave && !editor.TrySave() && ReadSetting("Source.1.Threshold") == "45");
            editor.RestoreCommand.Execute(null);
            editor.Confidence = 70; editor.Fps = 3; editor.Cooldown = 5;
            CheckAutoSave("parameter-batch-save-confirmed", editor.TrySave() && source.ThresholdPercent == 70 && source.TargetFps == 3 && source.Cooldown == 5 && ReadSetting("Source.1.Threshold") == "70" && ReadSetting("Source.1.Cooldown") == "5" && !source.HasPendingApply, editor.Message);
        }
        using (var canceled = new SourceParametersEditorViewModel(source)) { canceled.Confidence = 80; canceled.Cooldown = 300; }
        CheckAutoSave("parameter-cancel-keeps-value", source.ThresholdPercent == 70 && source.Cooldown == 5 && ReadSetting("Source.1.Threshold") == "70");
        using (var failedEditor = new SourceParametersEditorViewModel(source))
        {
            failedEditor.Confidence = 75; failedEditor.Fps = 4; failedEditor.Cooldown = 60;
            using (var lockedSettings = new FileStream(settingsPath, FileMode.Open, FileAccess.Read, FileShare.Read))
                CheckAutoSave("parameter-write-failure-rolls-back-all-fields", !failedEditor.TrySave()
                    && source.ThresholdPercent == 70 && source.TargetFps == 3 && source.Cooldown == 5
                    && ReadSetting("Source.1.Threshold") == "70" && ReadSetting("Source.1.Fps") == "3" && ReadSetting("Source.1.Cooldown") == "5"
                    && failedEditor.Confidence == 75 && failedEditor.Fps == 4 && failedEditor.Cooldown == 60 && failedEditor.Message.Length > 0, failedEditor.Message);
        }

        // 2) 改阈值：不需要任何保存动作，防抖到点后必须已经在磁盘上。
        source.ThresholdPercent = 61;
        CheckAutoSave("pending-lists-parameter", source.HasPendingApply && source.PendingApplyText.Contains("阈值"), source.PendingApplyText);
        CheckAutoSave("not-yet-persisted-before-debounce", ReadSetting("Source.1.Threshold") == "70", "disk=" + ReadSetting("Source.1.Threshold"));

        var wait = new DispatcherTimer(DispatcherPriority.Normal, dispatcher) { Interval = TimeSpan.FromMilliseconds(1200) };
        wait.Tick += (_, _) =>
        {
            wait.Stop();
            CheckAutoSave("threshold-auto-saved", ReadSetting("Source.1.Threshold") == "61", "disk=" + ReadSetting("Source.1.Threshold"));

            // 3) 频率与冷却同样自动保存。
            source.TargetFps = 5;
            source.Cooldown = 9;

            var wait2 = new DispatcherTimer(DispatcherPriority.Normal, dispatcher) { Interval = TimeSpan.FromMilliseconds(1200) };
            wait2.Tick += (_, _) =>
            {
                wait2.Stop();
                CheckAutoSave("fps-auto-saved", ReadSetting("Source.1.Fps") == "5", "disk=" + ReadSetting("Source.1.Fps"));
                CheckAutoSave("cooldown-auto-saved", ReadSetting("Source.1.Cooldown") == "9", "disk=" + ReadSetting("Source.1.Cooldown"));
                CheckAutoSave("pending-text-lists-all", source.PendingApplyText.Contains("阈值") && source.PendingApplyText.Contains("频率") && source.PendingApplyText.Contains("冷却"),
                    source.PendingApplyText);

                CheckAutoSave("configured-target-and-masks-preserved", source.IsTargetBound && source.MaskRegions.Count == 1);
                CheckAutoSave("target-and-masks-persisted", ReadSetting("Source.1.ScreenRegion") == "10,20,320,240" && ReadSetting("Source.1.Masks") == "0.1,0.1,0.2,0.2");
                Stage("after-persist-checks");
                CheckAutoSave("pending-does-not-track-target", !source.PendingApplyText.Contains("窗口") && !source.PendingApplyText.Contains("遮罩"), source.PendingApplyText);
                Stage("checks-complete");
                dispatcherDone.Set();
            };
            wait2.Start();
        };
        wait.Start();
        Stage("entering-dispatcher-loop");
        Dispatcher.Run();
        Stage("dispatcher-loop-exited");
    });
    thread.SetApartmentState(ApartmentState.STA);
    thread.Start();
    dispatcherDone.Wait(TimeSpan.FromSeconds(40));

    var autoSaveReport = new
    {
        passed = autoSavePassed,
        settingsPath,
        profile = ProbeProfile.Name,
        checks = autoSaveChecks,
    };
    var autoSaveJson = JsonSerializer.Serialize(autoSaveReport, new JsonSerializerOptions { WriteIndented = true });
    // 结果先落盘：这个模式的 Dispatcher 循环结束时 WPF 清理可能让进程以原生错误码退出，
    // 只靠 stdout 会丢掉已经得到的结论。
    var autoSaveReportPath = Environment.GetEnvironmentVariable("VISIONGUARD_AUTOSAVE_REPORT");
    if (string.IsNullOrWhiteSpace(autoSaveReportPath)) autoSaveReportPath = settingsPath + ".report.json";
    File.WriteAllText(Path.GetFullPath(autoSaveReportPath), autoSaveJson, new System.Text.UTF8Encoding(false));
    Console.WriteLine(autoSaveJson);
    Console.Out.Flush();
    // 不再等 WPF 自然收尾：结论已经落盘，直接按结果退出，避免把结论丢在一次退出期崩溃里。
    Environment.Exit(autoSavePassed ? 0 : 1);
    return autoSavePassed ? 0 : 1;
}

var requestedBackend = args.Length >= 2 && args[1].Equals("cpu", StringComparison.OrdinalIgnoreCase)
    ? InferenceBackend.Cpu
    : InferenceBackend.DirectML;
// legacy 档（Win7 SP1）编译期就没有 DirectML 提供程序：显式请求必须报错，
// 不能因为要在现代系统上验证 legacy 档就静默换后端掩盖档位事实。
if (requestedBackend == InferenceBackend.DirectML && !OnnxInferenceEngine.SupportsDirectMl)
{
    Console.Error.WriteLine("本档位不支持 DirectML（legacy/Win7 档固定 CPU）。请改用 cpu 后端。");
    return 2;
}

var iterations = args.Length >= 3 && int.TryParse(args[2], out var parsedIterations)
    ? Net472Compat.Clamp(parsedIterations, 10, 1000)
    : 100;
var hasImageInputs = args.Length >= 4 && !int.TryParse(args[3], out _);
var imagePaths = hasImageInputs ? args.Skip(3).Select(Path.GetFullPath).Take(3).ToArray() : Array.Empty<string>();
var streamCount = hasImageInputs
    ? imagePaths.Length
    : args.Length >= 4 && int.TryParse(args[3], out var parsedStreams) ? Net472Compat.Clamp(parsedStreams, 1, 3) : 1;

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
        return ImagePreprocessor.Prepare(bitmap, inputSize).Tensor;
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
            input = ImagePreprocessor.Prepare(bitmap, inputSize).Tensor;
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
    double Percentile(double percentile) => samples[Net472Compat.Clamp((int)Math.Ceiling(percentile * samples.Length) - 1, 0, samples.Length - 1)];
    return new
    {
        stream = streamIndex + 1,
        meanMs = samples.Average(),
        p50Ms = Percentile(0.50),
        p95Ms = Percentile(0.95),
        p99Ms = Percentile(0.99),
        minMs = samples[0],
        maxMs = samples[samples.Length - 1]
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

/// <summary>
/// 形态判定必须是封闭的：不符合任一档契约的张量长度必须显式报错，
/// 否则会退回「按某一档下标硬读另一档张量」的静默漏检。1800 与 84*N 都不匹配 12345。
/// </summary>
static bool ThrowsOnUnknownLength()
{
    try { _ = YoloOutputParser.Detect(12345); return false; }
    catch (InvalidOperationException) { return true; }
}

/// <summary>
/// 本探针自身的档位由编译期开关决定（与 detector/windows-wpf 共用 NativeLibraries.props）。
/// 不能用 OnnxInferenceEngine.SupportsDirectMl 代替：那读的是被引用工程（生产程序集）的开关，
/// 探针档位与被引用工程档位是两件事，混用会造成假验证。
/// </summary>
internal static class ProbeProfile
{
#if ORT_LEGACY
    public const bool IsLegacy = true;
#else
    public const bool IsLegacy = false;
#endif
    public const string Name = IsLegacy ? "legacy" : "modern";
}
