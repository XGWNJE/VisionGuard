package com.xgwnje.visionguard.inference.detect

/**
 * 单路来源的检测参数与边界，取值口径与 Windows 视觉推理节点一致：
 * 置信度 10–95 整数百分比、采样 1–5 整数 FPS、报警冷却 1–300 整数秒，
 * 类别过滤是逗号分隔的英文 COCO 类名（最长 4096 字符）。
 *
 * 参数是冷改动：运行中不允许修改，改动下次启动生效。
 */
data class DetectionParams(
    val confidencePercent: Int = DEFAULT_CONFIDENCE_PERCENT,
    val targetFps: Int = DEFAULT_TARGET_FPS,
    val cooldownSeconds: Int = DEFAULT_COOLDOWN_SECONDS,
    val modelKey: String,
    val watchedClasses: Set<String> = setOf(DEFAULT_TARGET)
) {
    /** 置信度阈值 `[0,1]`，用于解析与远程改参校验。 */
    val confidence: Float get() = confidencePercent / 100f

    /** `targets` 持久化串（逗号分隔英文类名）。 */
    fun watchedClassesText(): String = watchedClasses.joinToString(",")

    companion object {
        const val DEFAULT_CONFIDENCE_PERCENT = 45
        const val DEFAULT_TARGET_FPS = 3
        const val DEFAULT_COOLDOWN_SECONDS = 5
        const val DEFAULT_TARGET = "person"

        const val MIN_CONFIDENCE_PERCENT = 10
        const val MAX_CONFIDENCE_PERCENT = 95
        const val MIN_TARGET_FPS = 1
        const val MAX_TARGET_FPS = 5
        const val MIN_COOLDOWN_SECONDS = 1
        const val MAX_COOLDOWN_SECONDS = 300
        const val MAX_TARGETS_TEXT_LENGTH = 4096
        const val MAX_TARGET_COUNT = 256

        /** 解析 `targets` 串；空串归一化为默认 person，出现未知类名返回 null。 */
        fun parseWatchedClasses(raw: String?): Set<String>? {
            if (raw.isNullOrBlank()) return setOf(DEFAULT_TARGET)
            if (raw.length > MAX_TARGETS_TEXT_LENGTH) return null
            val parts = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return setOf(DEFAULT_TARGET)
            if (parts.size > MAX_TARGET_COUNT) return null
            val known = CocoClassMap.EnglishNames.toHashSet()
            if (parts.any { it.length > 64 || !known.contains(it) }) return null
            return parts.toSet()
        }

        /** 校验置信度百分比；非法返回可读原因。 */
        fun validateConfidence(percent: Int): String? =
            if (percent in MIN_CONFIDENCE_PERCENT..MAX_CONFIDENCE_PERCENT) null else "置信度需为 $MIN_CONFIDENCE_PERCENT–$MAX_CONFIDENCE_PERCENT 的整数百分比"

        /** 校验采样频率；非法返回可读原因。 */
        fun validateTargetFps(fps: Int): String? =
            if (fps in MIN_TARGET_FPS..MAX_TARGET_FPS) null else "采样频率需为 $MIN_TARGET_FPS–$MAX_TARGET_FPS 帧/秒"

        /** 校验报警冷却；非法返回可读原因。 */
        fun validateCooldown(seconds: Int): String? =
            if (seconds in MIN_COOLDOWN_SECONDS..MAX_COOLDOWN_SECONDS) null else "报警冷却需为 $MIN_COOLDOWN_SECONDS–$MAX_COOLDOWN_SECONDS 秒"
    }
}
