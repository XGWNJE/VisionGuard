package com.xgwnje.visionguard.inference.net

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.xgwnje.visionguard.inference.detect.CocoClassMap

/** 心跳里的一路来源快照；由运行中的来源状态构造。 */
data class HeartbeatSource(
    val sourceId: String,
    val sourceName: String,
    val isMonitoring: Boolean,
    val isReady: Boolean,
    val modelKey: String,
    val actualFps: Double,
    val error: String?,
    val activeBackend: String,
    val cooldown: Int,
    val confidence: Double,
    val targets: String,
    val targetSamplingRate: Int,
    val monitoringExpected: Boolean,
    val lastProgressAtIso: String?,
    val performanceWarning: String?
)

/**
 * 检测端心跳负载。
 *
 * 与 Windows `Services/ServerPushService.cs#SendHeartbeatNow` 对齐：顶层状态取任一来源的聚合值，
 * `cooldown/confidence/targets/targetSamplingRate/modelKey` 取第一个来源；完整回传模型标签与目标列表，
 * 不截断。Android 端不声明 `directml`，并额外声明 `video-subscribe`（远程镜头订阅能力）。
 */
object HeartbeatPayload {

    /** 本端声明的能力集合，服务端按 `allowedCapabilities` 过滤后落库。 */
    val CAPABILITIES = listOf(
        "monitor-control",
        "config-control",
        "request-correlation",
        "cache-maintenance",
        "screenshot-on-demand",
        "source-control",
        "video-subscribe",
        "visual-inference"
    )

    fun build(
        gson: Gson,
        deviceId: String,
        deviceName: String,
        sources: List<HeartbeatSource>,
        modelOptions: List<String>,
        modelLabels: Map<String, List<Pair<String, String>>>,
        components: Map<String, String> = mapOf("detectorApp" to "running")
    ): JsonObject {
        val array = JsonArray()
        for (source in sources) {
            array.add(JsonObject().apply {
                addProperty("sourceId", source.sourceId)
                addProperty("sourceName", source.sourceName)
                addProperty("isMonitoring", source.isMonitoring)
                addProperty("isReady", source.isReady)
                addProperty("modelKey", source.modelKey)
                addProperty("actualFps", source.actualFps)
                if (!source.error.isNullOrBlank()) addProperty("error", source.error) else addProperty("error", "")
                addProperty("activeBackend", source.activeBackend)
                addProperty("cooldown", source.cooldown)
                addProperty("confidence", source.confidence)
                addProperty("targets", source.targets)
                addProperty("targetSamplingRate", source.targetSamplingRate.coerceIn(1, 5))
                addProperty("monitoringExpected", source.monitoringExpected)
                addProperty("lastProgressAt", source.lastProgressAtIso ?: "")
                if (!source.performanceWarning.isNullOrBlank()) addProperty("performanceWarning", source.performanceWarning)
            })
        }

        val first = sources.firstOrNull()
        return JsonObject().apply {
            addProperty("deviceId", deviceId)
            addProperty("deviceName", deviceName)
            addProperty("isMonitoring", sources.any { it.isMonitoring })
            addProperty("isReady", sources.any { it.isReady })
            addProperty("cooldown", first?.cooldown ?: 5)
            addProperty("confidence", first?.confidence ?: 0.45)
            addProperty("targets", first?.targets ?: "person")
            addProperty("targetSamplingRate", (first?.targetSamplingRate ?: 3).coerceIn(1, 5))
            addProperty("modelKey", first?.modelKey ?: "")
            add("modelOptions", JsonArray().apply { modelOptions.forEach { add(it) } })
            add("modelLabels", labelsJson(modelLabels))
            addProperty("canSwitchModelWhileMonitoring", false)
            addProperty("hasPendingConfigChanges", false)
            add("capabilities", JsonArray().apply { CAPABILITIES.forEach { add(it) } })
            add("components", JsonObject().apply { components.forEach { (key, value) -> addProperty(key, value) } })
            add("sources", array)
        }
    }

    /** 每个模型键回传全部 80 个 COCO 类的中英文标签，供控制台选择检测目标。 */
    fun labelsJson(modelLabels: Map<String, List<Pair<String, String>>>): JsonObject = JsonObject().apply {
        for ((key, labels) in modelLabels) {
            add(key, JsonArray().apply {
                for ((value, label) in labels) {
                    add(JsonObject().apply {
                        addProperty("value", value)
                        addProperty("label", label)
                    })
                }
            })
        }
    }

    /** 模型标签表：键为模型键，值为英文/中文类名对。 */
    fun standardModelLabels(keys: List<String>): Map<String, List<Pair<String, String>>> =
        keys.associateWith { CocoClassMap.EnglishNames.map { name -> name to CocoClassMap.displayName(name) } }
}
