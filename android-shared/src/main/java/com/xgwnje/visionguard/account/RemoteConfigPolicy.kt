package com.xgwnje.visionguard.account

object RemoteConfigPolicy {
    const val MAX_AUDIO_BYTES = 512 * 1024
    const val MAX_AUDIO_ENTRIES = 100
    fun cameraValue(key: String, value: String, streaming: Boolean): String {
        when (key) {
            "cameraResolution" -> { require(!streaming) { "请先停止推流再修改规格" }; require(value in setOf("480p", "720p")) { "规格只能为 480p 或 720p" } }
            "cameraDimScreen", "cameraHidePreview" -> require(value == "true" || value == "false") { "必须为布尔值" }
            else -> error("不支持的相机配置")
        }
        return value
    }
    fun loopCount(value: String): Int {
        require(Regex("(?:[1-9]|10)").matches(value)) { "播放次数须为 1–10 的整数" }
        return value.toInt()
    }
    fun audioId(value: String): Boolean = Regex("(?:system-default|system-current|silent|preset:[a-z0-9_]{1,64}|library:[a-f0-9]{64})").matches(value)
}
