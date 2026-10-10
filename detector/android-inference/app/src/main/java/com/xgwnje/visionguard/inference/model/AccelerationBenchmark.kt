package com.xgwnje.visionguard.inference.model

import android.util.Log
import java.io.File

/**
 * 真机推理加速测量：同一模型在 CPU 与 NNAPI 各档位上的单帧耗时。
 *
 * 只在显式触发时运行（`am start ... --ez benchmark true`），正常启动不做任何测量，
 * 正式路径也不经过这里。存在的唯一目的，是按硬门槛判定量化模型能否使用加速档：
 * 加速档耗时必须 ≤ 同精度 CPU 的 70%（见 [AccelerationMeasure.ACCELERATION_RATIO_LIMIT]）。
 */
object AccelerationBenchmark {

    const val TAG = "VGInferenceBench"

    /** 目录：待测模型。adb 推送位置，与生产模型目录分开，不参与正式选择。 */
    const val MODEL_DIR = "bench-models"

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
     * 测量 [modelFile] 在 [providers] 上的单帧耗时。
     *
     * 返回读数和失败原因；NNAPI 档位可能因驱动拒绝、模型不被接受或设备不支持而失败，
     * 失败原因必须原样记下来，不能当成"没测过"。
     */
    fun run(
        modelFile: File,
        providers: List<InferenceProvider>,
        maxTotalMs: Long = MAX_DURATION_MS * 4,
        log: (String) -> Unit = { Log.i(TAG, it) }
    ): Pair<List<AccelerationMeasure.Result>, List<String>> {
        val results = mutableListOf<AccelerationMeasure.Result>()
        val failures = mutableListOf<String>()
        val startedAt = System.currentTimeMillis()

        providers.forEach { provider ->
            if (System.currentTimeMillis() - startedAt > maxTotalMs) {
                failures += "${modelFile.name} · ${provider.title}：整体超时，未测量"
                return@forEach
            }
            val samples = mutableListOf<Double>()
            var note = ""
            try {
                val engine = OnnxEngine.create(modelFile, provider)
                try {
                    val tensor = AccelerationMeasure.syntheticInput(engine.modelSize)
                    repeat(WARMUP_RUNS) { engine.run(tensor) }
                    for (frame in 0 until MAX_RUNS) {
                        val began = System.nanoTime()
                        engine.run(tensor)
                        val elapsed = (System.nanoTime() - began) / 1_000_000.0
                        samples += elapsed
                        if (elapsed > SLOW_FRAME_MS) {
                            note = "单帧超过 ${SLOW_FRAME_MS.toInt()} ms，提前停止"
                            break
                        }
                        val overBudget = System.currentTimeMillis() - startedAt > maxTotalMs
                        if (samples.size >= MIN_RUNS && overBudget) break
                    }
                } finally {
                    engine.close()
                }
            } catch (error: Throwable) {
                val reason = error.message ?: error::class.java.simpleName
                failures += "${modelFile.name} · ${provider.title}：$reason"
                log("失败 ${modelFile.name} · ${provider.title}：$reason")
                return@forEach
            }

            val ordered = samples.sorted()
            val row = AccelerationMeasure.Result(
                model = modelFile.name,
                provider = provider,
                runs = ordered.size,
                medianMs = AccelerationMeasure.median(ordered),
                minMs = ordered.firstOrNull() ?: 0.0,
                maxMs = ordered.lastOrNull() ?: 0.0,
                note = note
            )
            results += row
            log(
                "%s · %s：中位 %.1f ms / 最小 %.1f / 最大 %.1f（%d 帧）%s"
                    .format(modelFile.name, provider.title, row.medianMs, row.minMs, row.maxMs, row.runs, note)
            )
        }
        return results to failures
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
