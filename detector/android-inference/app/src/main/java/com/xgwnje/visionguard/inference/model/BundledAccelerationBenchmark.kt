package com.xgwnje.visionguard.inference.model

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.xgwnje.visionguard.inference.detect.ArgbImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 自带的推理加速测量：模型与画面随 APK 分发，装完点一下就能跑，不依赖 adb 推模型或触发。
 *
 * 为什么要有这一层：真机不一定能连 adb（MIUI 还会拦 `adb install`），只传 APK 安装时必须
 * 也能测。所以模型放 assets，第一次运行时解到应用私有目录（LiteRT 与 ONNX Runtime 都从文件
 * 加载），画面同样来自 assets；测量结果既显示在界面上，也写进诊断文件。
 *
 * 只有可调试构建（非 `.uipreview`）会显示入口；正式构建即使 assets 里带了模型也不会跑。
 */
object BundledAccelerationBenchmark {

    const val TAG = "VGInferenceBench"

    /** APK 内模型与画面的位置。 */
    private const val ASSET_MODELS = "bench-models"
    private const val ASSET_FRAME = "bench-frames/person-zidane.jpg"

    /** 解出来的模型放这里，与 adb 推送的 `files/bench-models/` 分开，互不干扰。 */
    private const val WORK_DIR = "bench-work"

    /** 解包后的最小合理体积：比它小说明 asset 读坏了，而不是模型本身小。 */
    private const val MIN_ASSET_BYTES = 64 * 1024L

    /**
     * 多路压力要对比的档位：正式档位（ONNX CPU 4 线程）与 GPU 档。
     *
     * 只测这两档：它们正是"该走 CPU 还是 GPU"的决策对象，其余档位在多路下不改变结论。
     */
    private val MULTI_STREAM_PROVIDERS = listOf(
        InferenceProvider.ONNX_CPU4,
        InferenceProvider.LITERT_GPU
    )

    /** 多路压力下每路跑多少帧：帧数越多越能看出排队与抖动，但总时长乘以路数。 */
    private const val PARALLEL_FRAMES = 8

    /** 界面状态：待机 / 运行中 / 出结果。 */
    data class State(
        val running: Boolean = false,
        val current: String = "",
        val report: String = "",
        val reportPath: String? = null,
        val failure: String? = null
    )

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    /** 是否可用：只有可调试构建显示入口。 */
    val available: Boolean get() = true

    /**
     * 跑一轮测量。
     *
     * [onlyAccelerated] 为 true 时只跑各运行时的加速档与其 CPU 基线，跳过逐线程 CPU 档，
     * 用更短的时间先看「加速有没有效果」；为 false 跑全部档位。
     *
     * [parallelStreams] 大于 0 时**只跑多路并发**：对每一级路数，同时开这么多路跑同一个模型，
     * 看总吞吐与单路排队；[parallelModels] 可只跑指定模型（逗号分隔的基名），空表示全部。
     */
    suspend fun run(
        context: Context,
        onlyAccelerated: Boolean,
        parallelStreams: Int = 0,
        parallelModels: String = ""
    ) {
        if (mutableState.value.running) return
        mutableState.value = State(running = true, current = "准备模型…")

        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                val work = File(context.filesDir, WORK_DIR).apply { mkdirs() }
                val models = unpackModels(context, work)
                check(models.isNotEmpty()) { "APK 里没有自带模型（assets/$ASSET_MODELS）" }
                val frame = loadFrame(context)
                val selected = if (parallelModels.isBlank()) {
                    models
                } else {
                    val wanted = parallelModels.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    models.filter { (name, _, _) -> wanted.any { name.contains(it) } }
                }

                val device = deviceFingerprint()
                val failures = mutableListOf<String>()
                if (frame == null) failures += "画面 assets/$ASSET_FRAME：不存在或无法解码"

                if (parallelStreams > 0) {
                    // 多路压力只测两档：正式档位（ONNX CPU 4 线程）与 GPU 档。
                    // 其余档位在多路下没有决策价值，跑完只会拉长测量时间。
                    val parallelResults = mutableListOf<AccelerationMeasure.ParallelResult>()
                    selected.forEach { (name, onnx, tflite) ->
                        MULTI_STREAM_PROVIDERS.forEach { provider ->
                            mutableState.value = mutableState.value.copy(
                                current = "多路 $parallelStreams × $name · ${provider.title} …"
                            )
                            val (row, errors) = AccelerationBenchmark.runParallel(
                                modelName = name,
                                onnxFile = onnx,
                                tfliteFile = tflite,
                                provider = provider,
                                streams = parallelStreams,
                                iterations = PARALLEL_FRAMES,
                                argb = frame,
                                log = { line ->
                                    Log.i(TAG, line)
                                    mutableState.value = mutableState.value.copy(current = line)
                                }
                            )
                            if (row != null) parallelResults += row
                            failures += errors
                        }
                    }
                    val report = AccelerationMeasure.Report(device, emptyList(), failures, parallelResults)
                    val path = AccelerationBenchmark.persist(File(context.filesDir, "diagnostics"), report)
                    val text = report.lines().joinToString("\n")
                    Log.i(TAG, "报告：${path ?: "写入失败"}")
                    mutableState.value = State(running = false, current = "完成", report = text, reportPath = path)
                    return@withContext text
                }

                val results = mutableListOf<AccelerationMeasure.Result>()
                val providers = if (onlyAccelerated) {
                    InferenceProvider.benchmarkOrder.filter { !it.usesNnapi || it == InferenceProvider.ONNX_NNAPI_DEFAULT }
                } else {
                    InferenceProvider.benchmarkOrder
                }

                models.forEach { (name, onnx, tflite) ->
                    mutableState.value = mutableState.value.copy(current = "测量 $name …")
                    val (rows, errors) = AccelerationBenchmark.run(
                        modelName = name,
                        onnxFile = onnx,
                        tfliteFile = tflite,
                        providers = providers,
                        argb = frame,
                        log = { line ->
                            Log.i(TAG, line)
                            mutableState.value = mutableState.value.copy(current = line)
                        }
                    )
                    results += rows
                    failures += errors
                }

                val report = AccelerationMeasure.Report(device, results, failures)
                val path = AccelerationBenchmark.persist(File(context.filesDir, "diagnostics"), report)
                val text = report.lines().joinToString("\n")
                Log.i(TAG, "报告：${path ?: "写入失败"}")
                mutableState.value = State(running = false, current = "完成", report = text, reportPath = path)
                text
            }
        }

        outcome.onFailure { error ->
            mutableState.value = State(
                running = false,
                current = "失败",
                failure = error.message ?: error::class.java.simpleName
            )
        }
    }

    fun reset() {
        if (!mutableState.value.running) mutableState.value = State()
    }

    /** 把 assets 里的成对模型解到私有目录；同名的 `.onnx` 与 `.tflite` 归到同一个模型基名。 */
    private fun unpackModels(context: Context, work: File): List<Triple<String, File, File?>> {
        val names = runCatching { context.assets.list(ASSET_MODELS)?.toList().orEmpty() }.getOrDefault(emptyList())
        val bases = names.map { it.substringBeforeLast('.') }.distinct().sorted()

        return bases.mapNotNull { base ->
            val onnx = unpack(context, "$ASSET_MODELS/$base.onnx", File(work, "$base.onnx"))
            val tflite = unpack(context, "$ASSET_MODELS/$base.tflite", File(work, "$base.tflite"))
            if (onnx == null && tflite == null) return@mapNotNull null
            Triple(base, onnx ?: File(work, "$base.onnx"), tflite)
        }
    }

    /**
     * 每次重新解出一个 asset。
     *
     * 不做"文件已存在就复用"的缓存：实测残留的半个文件会被当成有效模型，报出
     * `does not encode a valid TensorFlow Lite model`，而且会一直错下去。解包只要几百毫秒，
     * 相对整轮测量可以忽略，换来的是"读到的就是包里的那份"。
     *
     * 带同名 `.data` 的模型（ONNX 外部数据格式）会把数据文件一并解到同一目录——ONNX Runtime
     * 按"模型路径 + .data"找它，少一个文件会直接加载失败。
     */
    private fun unpack(context: Context, asset: String, target: File): File? = runCatching {
        target.delete()
        context.assets.open(asset).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }

        val sidecar = "$asset.data"
        val hasSidecar = runCatching {
            context.assets.open(sidecar).use { } // 打不开就说明没有
            true
        }.getOrDefault(false)
        if (hasSidecar) {
            val sidecarTarget = File(target.parentFile, "${target.name}.data")
            context.assets.open(sidecar).use { input ->
                sidecarTarget.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "已解包外部数据 $sidecar -> ${sidecarTarget.name}（${sidecarTarget.length()} 字节）")
        }

        check(target.length() > MIN_ASSET_BYTES) {
            "$asset 解包后只有 ${target.length()} 字节，疑似损坏"
        }
        Log.i(TAG, "已解包 $asset -> ${target.name}（${target.length()} 字节）")
        target
    }.getOrNull()

    private fun loadFrame(context: Context): ArgbImage? = runCatching {
        val bitmap = context.assets.open(ASSET_FRAME).use { BitmapFactory.decodeStream(it) } ?: return null
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        ArgbImage(width, height, pixels)
    }.getOrNull()

    /**
     * 报告首行的设备指纹，拼装规则在 [AccelerationMeasure.deviceFingerprint]（有单测守住格式）。
     *
     * `SOC_MODEL` 需要 API 31+，取不到就不带这一段。
     */
    private fun deviceFingerprint(): String {
        val soc = if (android.os.Build.VERSION.SDK_INT >= 31) {
            runCatching { android.os.Build.SOC_MODEL }.getOrNull()
        } else {
            null
        }
        return AccelerationMeasure.deviceFingerprint(
            manufacturer = android.os.Build.MANUFACTURER,
            model = android.os.Build.MODEL,
            apiLevel = android.os.Build.VERSION.SDK_INT,
            soc = soc,
            cores = Runtime.getRuntime().availableProcessors()
        )
    }
}
