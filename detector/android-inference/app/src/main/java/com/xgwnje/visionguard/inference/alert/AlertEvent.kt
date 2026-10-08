package com.xgwnje.visionguard.inference.alert

import com.xgwnje.visionguard.inference.detect.Detection
import java.time.Instant

/**
 * 一次值得上报的检测事件。
 *
 * [timestampMs] 使用 NTP 校正后的 UTC 毫秒；[timingsMs] 保存各阶段耗时，键名与 Windows 端一致
 * （`captureMs`、`preprocessMs`、`inferMs`、`parseMs`，远程来源另有 `remoteCachedAgeMs`）。
 */
data class AlertEvent(
    val alertId: String,
    val sourceId: String,
    val sourceName: String,
    val detections: List<Detection>,
    val timingsMs: Map<String, Long>,
    val timestampMs: Long
) {
    val timestampIso: String get() = Instant.ofEpochMilli(timestampMs).toString()

    /** 实时期限：事件发生后 30 秒；过期队列项不补传。 */
    val expiresAtIso: String get() = Instant.ofEpochMilli(timestampMs + REALTIME_DEADLINE_MS).toString()

    val expiresAtMs: Long get() = timestampMs + REALTIME_DEADLINE_MS

    companion object {
        const val REALTIME_DEADLINE_MS = 30_000L
        const val EVENT_KIND = "visual-detection"
        const val SUMMARY = "视觉检测告警"
    }
}

/**
 * 逐来源报警冷却：与 Windows `Services/AlertService.cs` 一致，
 * 只有出现检测框、且距上次报警超过冷却秒数时才产生报警。
 */
class AlertGate {
    private var lastAlertAtMs: Long? = null

    /** 是否应当报警；返回 true 时同时记录本次报警时间。 */
    fun shouldAlert(detections: List<Detection>, nowMs: Long, cooldownSeconds: Int): Boolean {
        if (detections.isEmpty()) return false
        val previous = lastAlertAtMs
        if (previous != null && nowMs - previous < cooldownSeconds * 1000L) return false
        lastAlertAtMs = nowMs
        return true
    }

    /** 主动停止或重新配置时调用，避免停止前的冷却沿用。 */
    fun reset() {
        lastAlertAtMs = null
    }

    /** 最后报警时间，用于界面显示。 */
    fun lastAlertAtMs(): Long? = lastAlertAtMs
}
