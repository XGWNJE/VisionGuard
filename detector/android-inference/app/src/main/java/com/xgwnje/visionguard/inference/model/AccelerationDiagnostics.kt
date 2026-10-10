package com.xgwnje.visionguard.inference.model

import android.os.Build
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 加速诊断：逐个方案建会话并跑固定输入，产出可以直接反馈的文本报告。
 *
 * 用在两台机器上看同样的报告：真机（系统加速器可用）与模拟器（通常只有 CPU）。
 * 报告含设备摘要、每个方案的可用性 / 失败原因 / 单帧耗时统计，以及自动模式的当前胜出方案。
 * 日志同时写到 logcat 的 `VisionGuardAccel` 标签，便于 adb 抓取。
 */
object AccelerationDiagnostics {

    private const val TAG = "VisionGuardAccel"

    /** 每个方案统计的帧数：太少噪声大，太多用户等得久。 */
    private const val MEASURE_FRAMES = 6

    fun deviceSummary(): String = buildString {
        append("设备：${Build.MANUFACTURER} ${Build.MODEL}")
        append(" / SoC ${Build.SOC_MODEL.ifBlank { "未知" }}")
        append(" / ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")
        append(" / Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
    }

    /** 跑一遍诊断并返回报告文本；不抛异常，失败原因都写进报告。 */
    suspend fun run(modelFile: File, selected: AccelerationMode, resolved: AccelerationMode?): String =
        withContext(Dispatchers.Default) {
            val lines = mutableListOf<String>()
            lines += "VisionGuard 加速诊断"
            lines += deviceSummary()
            lines += "模型：${modelFile.name}（${modelFile.length() / 1024 / 1024} MiB）"
            lines += "当前设置：${selected.title}" + if (resolved != null) "（自动上次胜出：${resolved.title}）" else ""
            lines += "── 逐方案 ──"
            Log.i(TAG, "diagnostics start: ${lines.joinToString(" | ")}")

            for (mode in AccelerationMode.AUTO_ORDER) {
                val line = probe(modelFile, mode)
                lines += line
                Log.i(TAG, "diagnostics $line")
            }

            lines += "── 说明 ──"
            lines += "耗时是同一张合成输入连续 ${MEASURE_FRAMES} 帧的统计，仅用于横向比较；"
            lines += "模拟器没有系统加速器，NNAPI 一档通常会失败，这是预期结果。"
            lines.joinToString("\n")
        }

    /** 单个方案的测量结果。 */
    data class Probe(
        val mode: AccelerationMode,
        val backend: String?,
        val averageMs: Double,
        val minMs: Long,
        val maxMs: Long,
        val message: String?
    ) {
        val usable: Boolean get() = backend != null
    }

    /** 按方案建会话并测 [frames] 帧；建会话失败时返回不可用原因。 */
    suspend fun measure(modelFile: File, mode: AccelerationMode, frames: Int = MEASURE_FRAMES): Probe =
        withContext(Dispatchers.Default) {
            val created = runCatching { OnnxEngine.create(modelFile, mode) }
            val engine = created.getOrNull()
                ?: return@withContext Probe(mode, null, 0.0, 0, 0, created.exceptionOrNull()?.message?.take(120))
            try {
                val size = engine.modelSize
                val input = FloatArray(3 * size * size) { ((it % 255) / 255f) }
                runCatching { engine.run(input) }
                val samples = LongArray(frames)
                for (i in 0 until frames) {
                    val started = System.nanoTime()
                    engine.run(input)
                    samples[i] = (System.nanoTime() - started) / 1_000_000
                }
                Probe(mode, engine.backend, samples.average(), samples.min(), samples.max(), null)
            } catch (error: Exception) {
                Probe(mode, null, 0.0, 0, 0, error.message?.take(120))
            } finally {
                engine.close()
            }
        }

    private fun probe(modelFile: File, mode: AccelerationMode): String {
        val created = runCatching { OnnxEngine.create(modelFile, mode) }
        val engine = created.getOrNull()
            ?: return "${pad(mode.title)}不可用  ${created.exceptionOrNull()?.message?.take(120)}"

        return try {
            val size = engine.modelSize
            val input = FloatArray(3 * size * size) { ((it % 255) / 255f) }
            // 预热一帧：首帧包含图构建与内存分配，不能算进稳态耗时。
            runCatching { engine.run(input) }
            val samples = LongArray(MEASURE_FRAMES)
            for (i in 0 until MEASURE_FRAMES) {
                val started = System.nanoTime()
                engine.run(input)
                samples[i] = (System.nanoTime() - started) / 1_000_000
            }
            val average = samples.average()
            "${pad(mode.title)}可用  ${pad(engine.backend, 18)}平均 %.1f ms（最小 %d / 最大 %d，%d 帧）"
                .format(average, samples.min(), samples.max(), MEASURE_FRAMES)
        } catch (error: Exception) {
            "${pad(mode.title)}可建会话但推理失败  ${error.message?.take(120)}"
        } finally {
            engine.close()
        }
    }

    /** 报告用等宽对齐：中文标题宽度按两个字符算。 */
    private fun pad(text: String, width: Int = 20): String {
        val visible = text.sumOf { if (it.code > 0x2E80) 2 else 1 }
        return text + " ".repeat((width - visible).coerceAtLeast(0))
    }

    /** 把报告写入应用私有目录，返回文件路径；失败返回 null。 */
    fun writeReport(context: android.content.Context, report: String): String? = runCatching {
        val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = File(dir, "accel-$stamp.txt")
        file.writeText("${deviceSummary()}\n\n$report\n")
        file.absolutePath
    }.getOrNull()
}
