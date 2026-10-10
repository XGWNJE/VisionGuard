package com.xgwnje.visionguard.inference.model

import java.io.File
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

    data class Result(
        val model: String,
        val provider: InferenceProvider,
        val runs: Int,
        val medianMs: Double,
        val minMs: Double,
        val maxMs: Double,
        val note: String = ""
    )

    /** 测量报告：同一设备上的全部读数与失败项。 */
    data class Report(val device: String, val results: List<Result>, val failures: List<String>) {

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
                }
                // 门槛只对同模型、同精度有意义：基线取该模型的 CPU 档（正式运行档位）。
                val baseline = rows.firstOrNull { it.provider == InferenceProvider.production }
                if (baseline != null) {
                    rows.filter { it.provider.usesNnapi }.forEach { row ->
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
        }
    }

    /** 中位数；空列表返回 0。 */
    fun median(ordered: List<Double>): Double {
        if (ordered.isEmpty()) return 0.0
        val middle = ordered.size / 2
        return if (ordered.size % 2 == 1) ordered[middle] else (ordered[middle - 1] + ordered[middle]) / 2.0
    }

    /** 待测模型：目录下的 `.onnx`，按名称排序保证多次运行一致。 */
    fun modelsIn(dir: File): List<File> =
        dir.listFiles { file -> file.isFile && file.name.endsWith(".onnx") }?.sortedBy { it.name }.orEmpty()

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
