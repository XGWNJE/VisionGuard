package com.xgwnje.visionguard.inference.model

import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * 推理加速测量的纯逻辑：档位顺序、耗时统计、报告文本、测量输入。
 *
 * 不引用 Android 与 ONNX Runtime 类型，便于 JVM 单元测试固定契约；实际跑会话的部分在
 * [AccelerationBenchmark]。
 *
 * 门槛来自本分支的验收口径：加速档耗时必须 ≤ 同精度 CPU 的 70%。达不到就按
 * 「放弃硬件加速」处理，不做「快一点点也算」的妥协。
 */
object AccelerationMeasure {

    /** 加速档判定门槛：加速档 / CPU 基线 必须 ≤ 该比值。 */
    const val ACCELERATION_RATIO_LIMIT = 0.70

    /**
     * 报告首行的设备指纹。
     *
     * 除厂商/型号/API 外要带上**芯片型号与核心数**：档位结论里的「CPU 4 线程最优」是否与核心
     * 拓扑绑定，只能靠这两个字段判断。`soc` 取不到时跳过，不用占位符编造。
     */
    fun deviceFingerprint(
        manufacturer: String,
        model: String,
        apiLevel: Int,
        soc: String?,
        cores: Int
    ): String = buildString {
        append("$manufacturer $model".trim())
        append(" / API $apiLevel")
        if (!soc.isNullOrBlank() && !soc.equals("unknown", ignoreCase = true)) append(" / $soc")
        append(" / $cores 核")
    }

    data class Result(
        val model: String,
        val provider: InferenceProvider,
        val runs: Int,
        val medianMs: Double,
        val minMs: Double,
        val maxMs: Double,
        val note: String = "",
        /**
         * 同一档位同一画面上的质量读数（`person 3 个 / 最高分 0.812 / 最高框 …`）。
         *
         * 耗时全档可比，但只有在**同一模型**的不同精度 / 不同档位之间比质量才有意义：
         * 它回答的是"这一档有没有把目标检出来"。为空表示这一档没取到质量读数（失败档位）。
         */
        val quality: String = ""
    )

    /**
     * 多路并发读数：同一档位同时跑 [streams] 路，每路 [framesPerStream] 帧。
     *
     * 单路中位数回答"一路多快"，本结构回答"多路压力下还顶不顶得住"：
     * [framesPerSecond] 反映总算力（总帧数 ÷ 墙钟时间，并行执行不能把每路帧率相加），
     * [medianMs] / [p95Ms] 反映每路的排队情况。
     */
    data class ParallelResult(
        val model: String,
        val provider: InferenceProvider,
        val streams: Int,
        val framesPerStream: Int,
        val elapsedMs: Double,
        val medianMs: Double,
        val p95Ms: Double,
        val maxMs: Double
    ) {
        /** 聚合吞吐：总帧数 ÷ 墙钟时间。 */
        val framesPerSecond: Double
            get() = if (elapsedMs <= 0) 0.0 else streams * framesPerStream * 1000.0 / elapsedMs

        /** 报告文本：一眼看出该档位在多路压力下够不够用。 */
        fun lines(): List<String> = listOf(
            ("  【多路 %d 路 × %d 帧】%s：合计 %.0f ms，吞吐 %.1f 帧/秒，"
                + "单路延迟 中位 %.1f / P95 %.1f / 最大 %.1f ms")
                .format(streams, framesPerStream, provider.title, elapsedMs, framesPerSecond, medianMs, p95Ms, maxMs)
        )
    }

    /** 测量报告：同一设备上的全部读数与失败项。 */
    data class Report(
        val device: String,
        val results: List<Result>,
        val failures: List<String>,
        /** 多路并发读数；本机不跑多路时为空。 */
        val parallel: List<ParallelResult> = emptyList()
    ) {

        fun lines(): List<String> = buildList {
            add("设备：$device")
            results.groupBy { it.model }.forEach { (model, rows) ->
                add("模型：$model")
                rows.forEach { row ->
                    val note = if (row.note.isEmpty()) "" else "  ${row.note}"
                    add(
                        "  ${row.provider.title}：中位 %.1f ms / 最小 %.1f / 最大 %.1f（%d 帧）%s"
                            .format(row.medianMs, row.minMs, row.maxMs, row.runs, note)
                    )
                    if (row.quality.isNotEmpty()) add("    质量：${row.quality}")
                }
                // 门槛只在「同运行时、同精度」之间比才有意义：NNAPI 对 ONNX 的 CPU 基线，
                // GPU/NPU 对各自运行时的 CPU 基线。基线优先取正式档位（CPU · 4 线程），
                // 该档没有读数时退到同一运行时里有的其它 CPU 档。
                InferenceRuntimeKind.entries.forEach { runtime ->
                    val cpuTiers = rows.filter { it.provider.runtime == runtime && !it.provider.usesAccelerator }
                    val baseline = cpuTiers.firstOrNull { it.provider == InferenceProvider.cpuBaseline(runtime) }
                        ?: cpuTiers.firstOrNull()
                        ?: return@forEach
                    rows.filter { it.provider.usesAccelerator && it.provider.runtime == runtime }
                        .forEach { row ->
                            val ratio = row.medianMs / baseline.medianMs
                            val verdict = if (ratio <= ACCELERATION_RATIO_LIMIT) "达标" else "未达标"
                            add(
                                "  ${row.provider.title} / ${baseline.provider.title} = %.2f×（门槛 ≤ %.2f×，%s）"
                                    .format(ratio, ACCELERATION_RATIO_LIMIT, verdict)
                            )
                        }
                }
            }
            failures.forEach { add("失败：$it") }
            if (parallel.isNotEmpty()) {
                add("多路并发（同一档位同时跑多路，看总吞吐与单路排队）")
                parallel.groupBy { it.model }.forEach { (model, rows) ->
                    add("模型：$model")
                    rows.forEach { row -> addAll(row.lines()) }
                }
            }
        }
    }

    /**
     * 质量行：同画面同档位下检出了什么。
     *
     * 为什么要有这一行：耗时全档可比，但"最快的那一档有没有把目标检出来"必须单独证明，
     * 否则可能把"模型量化坏了所以很快"当成结论。坐标用归一化中心与宽高，便于跨输入尺寸对照。
     */
    fun qualityLine(detections: Int, topScore: Float, centerX: Float, centerY: Float, width: Float, height: Float): String =
        "person %d 个 / 最高分 %.3f / 最高框 中心 %.3f,%.3f 宽高 %.3f,%.3f"
            .format(Locale.US, detections, topScore, centerX, centerY, width, height)

    /** 中位数；空列表返回 0。 */
    fun median(ordered: List<Double>): Double {
        if (ordered.isEmpty()) return 0.0
        val middle = ordered.size / 2
        return if (ordered.size % 2 == 1) ordered[middle] else (ordered[middle - 1] + ordered[middle]) / 2.0
    }

    /**
     * 固定合成输入：斜向渐变叠加固定位置的方块。
     *
     * 不用纯随机噪声——噪声的激活分布与真实画面差得远，量化模型在噪声上更容易被截断；
     * 这里只求"每档看到同一张图"，测的是耗时，与相机画面无关。
     */
    fun syntheticInput(size: Int): FloatArray {
        val tensor = FloatArray(3 * size * size)
        val plane = size * size
        for (y in 0 until size) {
            for (x in 0 until size) {
                val base = (x + y) % 256 / 255.0f
                val index = y * size + x
                tensor[index] = base
                tensor[plane + index] = 1.0f - base
                tensor[2 * plane + index] = abs(base - 0.5f) * 2.0f
            }
        }
        listOf(0.2f to 0.3f, 0.6f to 0.2f, 0.4f to 0.7f).forEach { (fx, fy) ->
            val x0 = (size * fx).toInt().coerceIn(0, size - 1)
            val y0 = (size * fy).toInt().coerceIn(0, size - 1)
            val side = (size / 8).coerceAtLeast(1)
            for (y in y0 until (y0 + side).coerceAtMost(size)) {
                for (x in x0 until (x0 + side).coerceAtMost(size)) {
                    val index = y * size + x
                    tensor[index] = 0.95f
                    tensor[plane + index] = 0.95f
                    tensor[2 * plane + index] = 0.95f
                }
            }
        }
        return tensor
    }
}
