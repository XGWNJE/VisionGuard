package com.xgwnje.visionguard.inference.model

import android.util.Log
import com.xgwnje.visionguard.inference.detect.ArgbImage
import com.xgwnje.visionguard.inference.detect.YoloOutputParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 真机推理加速测量：同一模型在 CPU 与 NNAPI 各档位上的单帧耗时。
 *
 * 只在显式触发时运行（`am start ... --ez benchmark true`），正常启动不做任何测量，
 * 正式路径也不经过这里。存在的唯一目的，是按硬门槛判定量化模型能否使用加速档：
 * 加速档耗时必须 ≤ 同精度 CPU 的 70%（见 [AccelerationMeasure.ACCELERATION_RATIO_LIMIT]）。
 */
object AccelerationBenchmark {

    const val TAG = "VGInferenceBench"

    /** 预热帧数：第一帧含图优化与会话初始化，不能计入稳态。 */
    private const val WARMUP_RUNS = 2

    /** 每次测量的最少帧数。 */
    private const val MIN_RUNS = 3

    /** 每档最多测多少帧：取中位数即可，避免慢档位长时间占住设备。 */
    private const val MAX_RUNS = 10

    /** 单档位整体时间上限。 */
    private const val MAX_DURATION_MS = 60_000L

    /** 单帧耗时超过它就提前结束该档位，读数仍记录。 */
    private const val SLOW_FRAME_MS = 15_000.0

    /**
     * 质量探针的阈值与目标类：与正式默认档位一致（`DetectionParams` 默认 45% 与 `person`）。
     *
     * 取正式默认值而不是另定一套，是为了让报告里的质量读数能直接和正式链路对照。
     */
    private const val QUALITY_CONFIDENCE = 0.45f
    private const val QUALITY_TARGET = "person"

    /**
     * 测量一个模型在 [providers] 上的单帧耗时。
     *
     * [onnxFile] 与 [tfliteFile] 分别是同一模型的 ONNX 与 LiteRT 产物：按档位的运行时选用对应
     * 文件，缺哪个就只跳过哪些档位并记一条失败，不做格式转换。
     *
     * [argb] 为真实画面时按与正式链路相同的 letterbox 预处理生成输入；为空则用固定合成输入。
     * 两种输入都只影响耗时测量，不影响质量结论（质量在桌面比对）。
     *
     * 返回读数和失败原因；加速档位可能因驱动拒绝、模型不被接受或设备不支持而失败，
     * 失败原因必须原样记下来，不能当成"没测过"。
     */
    fun run(
        modelName: String,
        onnxFile: File?,
        tfliteFile: File?,
        providers: List<InferenceProvider>,
        argb: ArgbImage? = null,
        maxTotalMs: Long = MAX_DURATION_MS * 4,
        log: (String) -> Unit = { Log.i(TAG, it) }
    ): Pair<List<AccelerationMeasure.Result>, List<String>> {
        val results = mutableListOf<AccelerationMeasure.Result>()
        val failures = mutableListOf<String>()
        val startedAt = System.currentTimeMillis()

        providers.forEach { provider ->
            val modelFile = when (provider.runtime) {
                InferenceRuntimeKind.ONNX -> onnxFile
                InferenceRuntimeKind.LITERT -> tfliteFile
            }
            if (modelFile == null) {
                failures += "$modelName · ${provider.title}：缺 ${extensionOf(provider.runtime)} 产物"
                return@forEach
            }
            if (System.currentTimeMillis() - startedAt > maxTotalMs) {
                failures += "$modelName · ${provider.title}：整体超时，未测量"
                return@forEach
            }
            val samples = mutableListOf<Double>()
            var note = ""
            var quality = ""
            var size = 0
            try {
                val tensorOf: (Int) -> FloatArray = { modelSize ->
                    argb?.toLetterboxTensor(modelSize)?.tensor
                        ?: AccelerationMeasure.syntheticInput(modelSize)
                }
                when (provider.runtime) {
                    InferenceRuntimeKind.ONNX -> {
                        val engine = OnnxEngine.create(modelFile, provider)
                        try {
                            size = engine.modelSize
                            val tensor = tensorOf(size)
                            repeat(WARMUP_RUNS) { engine.run(tensor) }
                            measure(samples, startedAt, maxTotalMs) { engine.run(tensor) }
                                .also { if (it.isNotEmpty()) note = it }
                            quality = qualityProbe(argb, size) { engine.run(tensor) }
                        } finally {
                            engine.close()
                        }
                    }

                    InferenceRuntimeKind.LITERT -> {
                        if (provider.usesLitertAcceleratorApi) {
                            val engine = LitertAcceleratedEngine.create(modelFile, provider)
                            try {
                                size = engine.modelSize
                                val tensor = tensorOf(size)
                                repeat(WARMUP_RUNS) { engine.run(tensor) }
                                measure(samples, startedAt, maxTotalMs) { engine.run(tensor) }
                                    .also { if (it.isNotEmpty()) note = it }
                                quality = qualityProbe(argb, size) { engine.run(tensor) }
                                if (note.isEmpty()) {
                                    note = listOfNotNull(
                                        engine.cpuNote.takeIf { it.isNotEmpty() },
                                        "可用加速器 ${engine.availableAccelerators}"
                                    ).joinToString("；")
                                }
                            } finally {
                                engine.close()
                            }
                        } else {
                            val engine = LitertEngine.create(modelFile, provider)
                            try {
                                size = engine.modelSize
                                val tensor = tensorOf(size)
                                repeat(WARMUP_RUNS) { engine.run(tensor) }
                                measure(samples, startedAt, maxTotalMs) { engine.run(tensor) }
                                    .also { if (it.isNotEmpty()) note = it }
                                quality = qualityProbe(argb, size) { engine.run(tensor) }
                                if (note.isEmpty()) note = engine.quantizationNote
                            } finally {
                                engine.close()
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                val reason = error.message ?: error::class.java.simpleName
                // 加速器档位带上配置说明：失败时才能分清是哪种 GPU 配置、哪个模型结构的问题。
                val variant = provider.gpuVariantNote.takeIf { it.isNotEmpty() }?.let { "（$it）" } ?: ""
                failures += "$modelName · ${provider.title}$variant：$reason"
                log("失败 $modelName · ${provider.title}$variant：$reason")
                // 有些 JDK 异常的 message 是 null（例如 java.nio 的 BufferUnderflowException），
                // 只记消息等于什么都没记；把栈连同 cause 一起写进日志，失败才能定位到行。
                log("失败栈 $modelName · ${provider.title}：\n" + error.stackTraceToString())
                return@forEach
            }

            val ordered = samples.sorted()
            val row = AccelerationMeasure.Result(
                model = modelName,
                provider = provider,
                runs = ordered.size,
                medianMs = AccelerationMeasure.median(ordered),
                minMs = ordered.firstOrNull() ?: 0.0,
                maxMs = ordered.lastOrNull() ?: 0.0,
                note = note,
                quality = quality
            )
            results += row
            log(
                "%s · %s：中位 %.1f ms / 最小 %.1f / 最大 %.1f（%d 帧，%d 输入）%s"
                    .format(modelName, provider.title, row.medianMs, row.minMs, row.maxMs, row.runs, size, note)
            )
        }
        return results to failures
    }

    /**
     * 多路并发测量：同一档位、[streams] 路**同时**跑 [iterations] 帧，看多路压力下的表现。
     *
     * 为什么要单独测：单路中位数回答的是"一路多快"，而实际部署是多个来源同时推理。
     * CPU 档在多路下会互相抢核（每路排队，单路延迟上升、总算力封顶），
     * GPU 档则可能因为并行度不足而吃不满——**两者的优劣在多路下可能反转**，
     * 所以必须用聚合吞吐（总帧数 ÷ 墙钟时间）与单路延迟分位数一起看。
     *
     * 每路各自建独立会话（真实部署也是多个来源各自持有推理链）。
     */
    suspend fun runParallel(
        modelName: String,
        onnxFile: File?,
        tfliteFile: File?,
        provider: InferenceProvider,
        streams: Int,
        iterations: Int,
        argb: ArgbImage? = null,
        log: (String) -> Unit = { Log.i(TAG, it) }
    ): Pair<AccelerationMeasure.ParallelResult?, List<String>> {
        val modelFile = when (provider.runtime) {
            InferenceRuntimeKind.ONNX -> onnxFile
            InferenceRuntimeKind.LITERT -> tfliteFile
        }
        if (modelFile == null) {
            return null to listOf("$modelName · ${provider.title}：缺 ${extensionOf(provider.runtime)} 产物")
        }
        val warmup = WARMUP_RUNS
        return try {
            val outcome: Pair<List<DoubleArray>, Double> = withContext(Dispatchers.Default) {
                val startedAt = System.nanoTime()
                val perStream = (0 until streams).map {
                    async { runOneStream(modelFile, provider, argb, warmup, iterations) }
                }
                val all: List<DoubleArray> = awaitAll(*perStream.toTypedArray())
                all to (System.nanoTime() - startedAt) / 1_000_000.0
            }
            val elapsedMs = outcome.second
            val merged: List<Double> = outcome.first.flatMap { it.toList() }.sorted()
            val result = AccelerationMeasure.ParallelResult(
                model = modelName,
                provider = provider,
                streams = streams,
                framesPerStream = iterations,
                elapsedMs = elapsedMs,
                medianMs = AccelerationMeasure.median(merged),
                p95Ms = percentile(merged, 0.95),
                maxMs = merged.lastOrNull() ?: 0.0
            )
            log(
                "%s · %s：%d 路 × %d 帧，合计 %.0f ms，吞吐 %.1f 帧/秒，单路延迟 中位 %.1f / P95 %.1f / 最大 %.1f ms"
                    .format(
                        modelName, provider.title, streams, iterations, elapsedMs,
                        result.framesPerSecond, result.medianMs, result.p95Ms, result.maxMs
                    )
            )
            result to emptyList()
        } catch (error: Throwable) {
            val reason = error.message ?: error::class.java.simpleName
            log("失败 $modelName · ${provider.title}（多路 $streams）：$reason")
            null to listOf("$modelName · ${provider.title}（多路 $streams）：$reason")
        }
    }

    /**
     * 一路的推理循环：独立会话 + 预热 + 逐帧计时。返回每帧耗时（ms）。
     *
     * 每路各建独立会话，是因为真实部署里多个来源各自持有推理链；共用会话会低估内存与调度压力。
     */
    private fun runOneStream(
        modelFile: File,
        provider: InferenceProvider,
        argb: ArgbImage?,
        warmup: Int,
        iterations: Int
    ): DoubleArray {
        val (size, session, close) = createEngine(modelFile, provider)
        try {
            val tensor = argb?.toLetterboxTensor(size)?.tensor ?: AccelerationMeasure.syntheticInput(size)
            repeat(warmup) { session.run(tensor) }
            return DoubleArray(iterations) { frame ->
                val began = System.nanoTime()
                session.run(tensor)
                (System.nanoTime() - began) / 1_000_000.0
            }
        } finally {
            close()
        }
    }

    /** 已建会话的统一包装：ONNX 与两种 LiteRT 入口在这里合成一条路径。 */
    private fun interface Session {
        /** 跑一帧；入参为 CHW `float[3*size*size]`。 */
        fun run(tensor: FloatArray): FloatArray
    }

    /**
     * 建一个独立会话，返回 (输入边长, 会话, 关闭函数)。
     *
     * 三元组而不是自定义类：这里只需要这三个值，多一层包装只会增加阅读成本。
     */
    private fun createEngine(
        modelFile: File,
        provider: InferenceProvider
    ): Triple<Int, Session, () -> Unit> =
        when (provider.runtime) {
            InferenceRuntimeKind.ONNX -> {
                val engine = OnnxEngine.create(modelFile, provider)
                Triple(engine.modelSize, Session { tensor -> engine.run(tensor) }) { engine.close() }
            }

            InferenceRuntimeKind.LITERT -> if (provider.usesLitertAcceleratorApi) {
                val engine = LitertAcceleratedEngine.create(modelFile, provider)
                Triple(engine.modelSize, Session { tensor -> engine.run(tensor) }) { engine.close() }
            } else {
                val engine = LitertEngine.create(modelFile, provider)
                Triple(engine.modelSize, Session { tensor -> engine.run(tensor) }) { engine.close() }
            }
        }

    /** 分位数（[ordered] 必须已排序）。 */
    private fun percentile(ordered: List<Double>, fraction: Double): Double {
        if (ordered.isEmpty()) return 0.0
        val index = ((ordered.size - 1) * fraction).toInt().coerceIn(0, ordered.size - 1)
        return ordered[index]
    }

    /**
     * 质量探针：测速结束后用**同一个已建会话**多跑一帧，把"检出了什么"与解析耗时记进报告。
     * 为什么放在这里而不是另起一次测量：会话已经建好、输入也是同一张真实画面，
     * 多一帧的开销可以忽略；另起一次测量则要重新加载模型，还会因为不同档位的
     * 精度差异（FP32 / INT8）而失去可比性。质量帧失败只记原因，不影响耗时行。
     *
     * 解析耗时单列（`解析 xx ms`）：锚点档的解码 + 逐类 NMS 在端侧 CPU 上跑，属于加速档的
     * **净新增开销**，端到端能不能达标要用它来算，不能只看推理耗时。
     */
    private fun qualityProbe(argb: ArgbImage?, inputSize: Int, infer: () -> FloatArray): String {
        if (argb == null || inputSize <= 0) return ""
        return try {
            val frame = argb.toLetterboxTensor(inputSize)
            val raw = infer()
            val parseStarted = System.nanoTime()
            val detections = YoloOutputParser.parse(raw, frame.transform, QUALITY_CONFIDENCE, setOf(QUALITY_TARGET))
            val parseMs = (System.nanoTime() - parseStarted) / 1_000_000.0
            val suffix = String.format(Locale.US, " / 解析 %.1f ms", parseMs)
            if (detections.isEmpty()) {
                AccelerationMeasure.qualityLine(0, 0f, 0f, 0f, 0f, 0f) + suffix
            } else {
                val top = detections.maxByOrNull { it.confidence }!!
                AccelerationMeasure.qualityLine(
                    detections.size,
                    top.confidence,
                    (top.box.left + top.box.width / 2f) / argb.width,
                    (top.box.top + top.box.height / 2f) / argb.height,
                    top.box.width / argb.width,
                    top.box.height / argb.height
                ) + suffix
            }
        } catch (error: Throwable) {
            "取质量失败：${error.message ?: error::class.java.simpleName}"
        }
    }

    /** 跑一档，返回提前停止的说明（没有则为空串）。 */
    private inline fun measure(
        samples: MutableList<Double>,
        startedAt: Long,
        maxTotalMs: Long,
        runOnce: () -> Unit
    ): String {
        for (frame in 0 until MAX_RUNS) {
            val began = System.nanoTime()
            runOnce()
            val elapsed = (System.nanoTime() - began) / 1_000_000.0
            samples += elapsed
            if (elapsed > SLOW_FRAME_MS) {
                return "单帧超过 ${SLOW_FRAME_MS.toInt()} ms，提前停止"
            }
            if (samples.size >= MIN_RUNS && System.currentTimeMillis() - startedAt > maxTotalMs) break
        }
        return ""
    }

    private fun extensionOf(runtime: InferenceRuntimeKind): String = when (runtime) {
        InferenceRuntimeKind.ONNX -> ".onnx"
        InferenceRuntimeKind.LITERT -> LitertEngine.MODEL_EXTENSION
    }

    /** 把报告写入 [directory] 下的时间戳文件，返回路径；失败返回 null。 */
    fun persist(directory: File, report: AccelerationMeasure.Report): String? = runCatching {
        directory.mkdirs()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = File(directory, "acceleration-bench-$stamp.txt")
        file.writeText(report.lines().joinToString("\n"))
        file.absolutePath
    }.getOrNull()
}
