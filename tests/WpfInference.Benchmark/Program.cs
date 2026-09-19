using System.Diagnostics;
using System.Drawing;
using System.Text.Json;
using System.Threading;
using System.Windows.Threading;
using VisionGuard.Inference;
using VisionGuard.Runtime;
using VisionGuard.Services;
using VisionGuard.ViewModels;

if (args.Length < 1)
{
    Console.Error.WriteLine("Usage: WpfInference.Benchmark <model.onnx> [cpu|directml] [iterations] [streams|image1 image2 image3]");
    Console.Error.WriteLine("       WpfInference.Benchmark <ignored> --layout-plan <report.json>");
    return 2;
}

var modelPath = Path.GetFullPath(args[0]);

// 与生产程序同一路径：先按档位预加载原生 ONNX Runtime（绝对路径），再建任何推理会话。
// 不做这一步时 DllImport 会按默认搜索顺序找根目录的 onnxruntime.dll，而它按设计已被移走，
// legacy 档会以无诊断信息的进程终止失败。
NativeLibrarySelector.Initialize();

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
            using (var contractBitmap = new Bitmap(imagePath))
                contractOutput = contractEngine.Run(
                    ImagePreprocessor.ToTensor(contractBitmap, contractInputSize),
                    ImagePreprocessor.InputShape(contractInputSize));
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
                $"loaded={loadedNative} expectedDir={NativeLibrarySelector.SelectedDirectory}");            var contractRegion = new Rectangle(0, 0, contractInputSize, contractInputSize);
            var allClasses = YoloOutputParser.Parse(contractOutput, contractRegion, 0.25f, new HashSet<string>(), contractInputSize);
            Stage("parsed detections=" + allClasses.Count);
            var wanted = allClasses.Where(d => string.Equals(d.Label, expectedLabel, StringComparison.OrdinalIgnoreCase)).ToArray();

            Check("expected-label-detected", wanted.Length > 0,
                $"threshold=0.25 detections=[{string.Join(", ", allClasses.Select(d => $"{d.Label}:{d.Confidence:F3}"))}]");
            Check("expected-label-above-min-confidence", wanted.Any(d => d.Confidence >= minConfidence),
                $"min={minConfidence} max={(wanted.Length == 0 ? 0f : wanted.Max(d => d.Confidence)):F3}");
            Check("boxes-within-frame", allClasses.All(d =>
                    d.BoundingBox.Width > 0 && d.BoundingBox.Height > 0
                    && d.BoundingBox.Left >= -1 && d.BoundingBox.Top >= -1
                    && d.BoundingBox.Right <= contractInputSize + 1 && d.BoundingBox.Bottom <= contractInputSize + 1),
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

// ── 卡片区布局契约探针 ─────────────────────────────────────────────────────
// 目的：把「只有一个来源时只用了一半预览区」这类布局问题变成机器可判定的检查。
// 这里只调 CardLayoutPlanner（纯计算），不开窗口、不建推理会话，因此任何机器都能跑；
// 它证明布局数学，不证明真实界面的视觉与拖拽手感（那部分必须 owner 目检）。
if (args.Length >= 2 && args[1].Equals("--layout-plan", StringComparison.OrdinalIgnoreCase))
{
    if (args.Length != 3)
    {
        Console.Error.WriteLine("Usage: WpfInference.Benchmark <ignored> --layout-plan <report.json>");
        return 2;
    }

    var layoutChecks = new List<object>();
    var layoutPassed = true;
    void CheckLayout(string name, bool ok, object? detail = null)
    {
        layoutChecks.Add(new { name, passed = ok, detail });
        if (!ok) layoutPassed = false;
    }

    // 与 MainWindow 一致的两个固定占用：窗口标题栏/边框，以及卡片区底部工具条。
    const double WindowChromeHeight = 45;
    const double ToolBarHeight = 44;

    // 卡片区可用宽度 = 窗口宽度 - 卡片区外边距 - 检查区最小宽度 - 分隔条宽度。
    double CardsWidthFor(double windowWidth) => Math.Max(
        CardLayoutPlanner.MinimumCardsPanelWidth - CardLayoutPlanner.HostMarginWidth,
        windowWidth - CardLayoutPlanner.HostMarginWidth - CardLayoutPlanner.InspectorMinWidth - CardLayoutPlanner.SplitterWidth);

    // 网格面板可用高度 = 窗口高度 - 标题栏/边框 - 卡片区外边距 - 底部工具条。
    double CardsHeightFor(double windowHeight) => Math.Max(
        CardLayoutPlanner.MinimumPictureEdge,
        windowHeight - WindowChromeHeight - CardLayoutPlanner.HostMarginHeight - ToolBarHeight);

    CardLayoutPlan PlanFor(int visible, double windowWidth, double windowHeight, double? aspectRatio = 1d)
        => CardLayoutPlanner.Compute(new CardLayoutRequest
        {
            VisibleCount = visible,
            TotalWidth = CardsWidthFor(windowWidth),
            TotalHeight = CardsHeightFor(windowHeight),
            UniformAspectRatio = aspectRatio,
        });

    var layoutWindows = new[]
    {
        (Name: "最小窗口 1200x880", Width: CardLayoutPlanner.MinimumWindowWidth, Height: CardLayoutPlanner.MinimumWindowHeight),
        (Name: "1420x880", Width: 1420d, Height: 880d),
        (Name: "1920x1080", Width: 1920d, Height: 1080d),
    };

    // 1) 比例约束（2026-09-20 新契约）：卡片必须接近方形，宽高比落在 1:1.2 ~ 1.2:1。
    //    不论预览几张、窗口多大，越界时求解器都要收窄格子并留成间隔，而不是把卡片拉宽。
    foreach (var window in layoutWindows)
    {
        for (int visible = 1; visible <= CardLayoutPlanner.MaximumVisibleCards; visible++)
        {
            var plan = PlanFor(visible, window.Width, window.Height);
            double ratio = plan.CellHeight <= 0 ? 0 : plan.CellWidth / plan.CellHeight;
            CheckLayout($"{visible} 张 {window.Name} 卡片比例在 1:1.2~1.2:1",
                plan.IsValid
                && ratio >= CardLayoutPlanner.MinimumCardAspectRatio - 0.001
                && ratio <= CardLayoutPlanner.MaximumCardAspectRatio + 0.001,
                new { plan.Rows, plan.Columns, plan.CellWidth, plan.CellHeight, ratio });
        }
    }

    // 2) 网格必须恰好排得下、不越出可用空间，行列数与张数匹配：
    //    1 张 = 1×1、2 张 = 1×2 或 2×1、3–4 张 = 2×2（不再有分页，也不允许把第 4 张挤出可视区）。
    foreach (var window in layoutWindows)
    {
        for (int visible = 1; visible <= CardLayoutPlanner.MaximumVisibleCards; visible++)
        {
            var plan = PlanFor(visible, window.Width, window.Height);
            double gridWidth = plan.Columns * plan.CellWidth + (plan.Columns - 1) * CardLayoutPlanner.CardSpacing;
            double gridHeight = plan.Rows * plan.CellHeight + (plan.Rows - 1) * CardLayoutPlanner.CardSpacing;
            bool rowsAndColumnsMatch = visible <= 2
                ? plan.Rows * plan.Columns == visible
                : plan.Rows == 2 && plan.Columns == 2;
            CheckLayout($"{visible} 张 {window.Name} 排得下且不裁剪",
                plan.IsValid && rowsAndColumnsMatch
                && gridWidth <= CardsWidthFor(window.Width) + 0.5
                && gridHeight <= CardsHeightFor(window.Height) + 0.5,
                new { plan.Rows, plan.Columns, gridWidth, gridHeight });
        }
    }

    // 3) 画面短边回归线：1:1 是模型的默认识别画幅。压缩卡片内垂直占位（103→68）后，最小窗口下
    //    1/2/4 张的画面短边分别达到 500/380/320。
    var minSingle = PlanFor(1, CardLayoutPlanner.MinimumWindowWidth, CardLayoutPlanner.MinimumWindowHeight);
    CheckLayout("最小窗口 1 张 1:1 画面短边 >= 500",
        minSingle.IsValid && minSingle.MinimumPictureEdge >= 500,
        new { minSingle.CellWidth, minSingle.CellHeight, minSingle.PictureWidth, minSingle.PictureHeight });
    var minPair = PlanFor(2, CardLayoutPlanner.MinimumWindowWidth, CardLayoutPlanner.MinimumWindowHeight);
    CheckLayout("最小窗口 2 张 1:1 画面短边 >= 380",
        minPair.IsValid && minPair.MinimumPictureEdge >= 380,
        new { minPair.CellWidth, minPair.CellHeight, minPair.PictureWidth, minPair.PictureHeight });
    var minFour = PlanFor(4, CardLayoutPlanner.MinimumWindowWidth, CardLayoutPlanner.MinimumWindowHeight);
    CheckLayout("最小窗口 4 张 1:1 画面短边 >= 320",
        minFour.IsValid && minFour.MinimumPictureEdge >= CardLayoutPlanner.MinimumPictureEdge - 0.5,
        new { minFour.CellWidth, minFour.CellHeight, minFour.PictureWidth, minFour.PictureHeight });

    // 4) 极端可用空间：宽扁 / 窄高容器下比例仍受约束，靠留白吸收差异，绝不出现宽扁条或细高条。
    var wideShallow = CardLayoutPlanner.Compute(new CardLayoutRequest
    {
        VisibleCount = 4, TotalWidth = 1800, TotalHeight = 300, UniformAspectRatio = null,
    });
    CheckLayout("宽扁容器 4 张比例不超 1.2:1",
        wideShallow.IsValid
        && wideShallow.CellWidth / wideShallow.CellHeight <= CardLayoutPlanner.MaximumCardAspectRatio + 0.001,
        new { wideShallow.Rows, wideShallow.Columns, wideShallow.CellWidth, wideShallow.CellHeight });
    var narrowTall = CardLayoutPlanner.Compute(new CardLayoutRequest
    {
        VisibleCount = 4, TotalWidth = 420, TotalHeight = 1400, UniformAspectRatio = null,
    });
    CheckLayout("窄高容器 4 张比例不低于 1:1.2",
        narrowTall.IsValid
        && narrowTall.CellWidth / narrowTall.CellHeight >= CardLayoutPlanner.MinimumCardAspectRatio - 0.001,
        new { narrowTall.Rows, narrowTall.Columns, narrowTall.CellWidth, narrowTall.CellHeight });

    // 5) 确定性：同一输入两次求解必须完全一致，界面重排与净室断言都建立在这一点上。
    var first = PlanFor(3, 1420d, 880d);
    var second = PlanFor(3, 1420d, 880d);
    CheckLayout("同一输入结果确定",
        first.Rows == second.Rows && first.Columns == second.Columns
        && first.CellWidth == second.CellWidth && first.CellHeight == second.CellHeight,
        new { first.Rows, first.Columns, first.CellWidth, first.CellHeight });

    // 6) 退化输入不得抛异常，也不得返回“看起来有效”的布局（窗口最小化时视图应原样跳过）。
    var degenerate = CardLayoutPlanner.Compute(new CardLayoutRequest { VisibleCount = 3, TotalWidth = 0, TotalHeight = 0 });
    CheckLayout("零可用空间返回无效布局", !degenerate.IsValid, new { degenerate.CellWidth, degenerate.CellHeight });
    var noAspect = PlanFor(2, 1420d, 880d, null);
    CheckLayout("无画面比例时仍能求解", noAspect.IsValid && noAspect.Rows * noAspect.Columns >= 2,
        new { noAspect.CellWidth, noAspect.CellHeight });

    var layoutReport = new
    {
        passed = layoutPassed,
        probe = "card-layout-plan",
        constants = new
        {
            CardLayoutPlanner.MaximumVisibleCards,
            CardLayoutPlanner.MinimumPictureEdge,
            CardLayoutPlanner.MinimumCardAspectRatio,
            CardLayoutPlanner.MaximumCardAspectRatio,
            CardLayoutPlanner.CardChromeWidth,
            CardLayoutPlanner.CardChromeHeight,
            CardLayoutPlanner.MinimumCardsPanelWidth,
            CardLayoutPlanner.MinimumInspectorPanelWidth,
            CardLayoutPlanner.MaximumInspectorPanelWidth,
            CardLayoutPlanner.MinimumWindowWidth,
            CardLayoutPlanner.MinimumWindowHeight,
        },
        samples = new[]
        {
            new { visible = 1, window = "最小窗口 1200x880", plan = PlanFor(1, CardLayoutPlanner.MinimumWindowWidth, CardLayoutPlanner.MinimumWindowHeight) },
            new { visible = 2, window = "最小窗口 1200x880", plan = PlanFor(2, CardLayoutPlanner.MinimumWindowWidth, CardLayoutPlanner.MinimumWindowHeight) },
            new { visible = 4, window = "最小窗口 1200x880", plan = PlanFor(4, CardLayoutPlanner.MinimumWindowWidth, CardLayoutPlanner.MinimumWindowHeight) },
            new { visible = 4, window = "1420x880", plan = PlanFor(4, 1420d, 880d) },
            new { visible = 4, window = "1920x1080", plan = PlanFor(4, 1920d, 1080d) },
        },
        checks = layoutChecks,
    };
    var layoutReportPath = Path.GetFullPath(args[2]);
    Directory.CreateDirectory(Path.GetDirectoryName(layoutReportPath)!);
    var layoutJson = JsonSerializer.Serialize(layoutReport, new JsonSerializerOptions { WriteIndented = true });
    File.WriteAllText(layoutReportPath, layoutJson, new System.Text.UTF8Encoding(false));
    Console.WriteLine(layoutJson);
    return layoutPassed ? 0 : 1;
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

    var downloadPath = VisionGuard.Utils.ModelManager.GetModelPath(downloadKey);
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
        option.IsDownloaded ? string.IsNullOrEmpty(VisionGuard.Utils.ModelManager.LastFailureReason)
                            : !string.IsNullOrEmpty(VisionGuard.Utils.ModelManager.LastFailureReason),
        option.IsDownloaded ? "(download ok; reason cleared)" : VisionGuard.Utils.ModelManager.LastFailureReason);
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
// 目的：来源页已移除保存按钮，参数必须「改动即已保存」。
// 这里驱动真实的 SourceViewModel：改参数 → 等防抖定时器 → 回读隔离 settings 文件；
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

        // 2) 改阈值：不需要任何保存动作，防抖到点后必须已经在磁盘上。
        source.ThresholdPercent = 61;
        CheckAutoSave("pending-lists-parameter", source.HasPendingApply && source.PendingApplyText.Contains("阈值"), source.PendingApplyText);
        CheckAutoSave("not-yet-persisted-before-debounce", ReadSetting("Source.1.Threshold") == "45", "disk=" + ReadSetting("Source.1.Threshold"));

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

                // 4) 采集目标三件套：重置必须把窗口/选区/遮罩一起清掉并立即落盘（不需要保存按钮）。
                Stage("before-reset hasTarget=" + source.HasAnyTarget);
                try
                {
                    source.ResetTargetCommand.Execute(null);
                }
                catch (Exception ex)
                {
                    Stage("reset-threw " + ex.GetType().FullName + ": " + ex.Message + " | " + ex.StackTrace);
                }
                Stage("after-reset hasTarget=" + source.HasAnyTarget);
                CheckAutoSave("target-cleared", !source.HasAnyTarget, source.TargetInfo + " / " + source.MaskInfo);
                CheckAutoSave("target-persisted", ReadSetting("Source.1.ScreenRegion") == "" && ReadSetting("Source.1.Masks") == "",
                    "region=[" + ReadSetting("Source.1.ScreenRegion") + "] masks=[" + ReadSetting("Source.1.Masks") + "]");
                CheckAutoSave("reset-survived-reconfigure", source.ResetTargetCommand.CanExecute(null) == false
                    || source.HasAnyTarget, "canExecute=" + source.ResetTargetCommand.CanExecute(null) + " hasTarget=" + source.HasAnyTarget);
                Stage("after-persist-checks");
                CheckAutoSave("pending-does-not-track-target", !source.PendingApplyText.Contains("窗口") && !source.PendingApplyText.Contains("遮罩"), source.PendingApplyText);
                CheckAutoSave("save-button-removed", source.GetType().GetProperty("SaveCommand") == null && source.GetType().GetProperty("CancelCommand") == null,
                    "SaveCommand/CancelCommand present on SourceViewModel");
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
