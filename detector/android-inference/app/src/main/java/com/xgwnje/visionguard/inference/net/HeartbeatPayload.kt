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
    val performanceWarning: String?,
    /** 后端细节：输入尺寸、线程、量化边界，或「为何没用 GPU」的回落原因。 */
    val backendNote: String = "",
    /** 该来源配置在同机上的并发上限（单路 ≥5 帧/秒），与控制台显示一致。 */
    val concurrencyLimit: Int = 0
)

/**
 * 检测端心跳负载。
 *
 * 与 Windows `Services/ServerPushService.cs#SendHeartbeatNow` 对齐：顶层状态取任一来源的聚合值，
 * `cooldown/confidence/targets/targetSamplingRate/modelKey` 取第一个来源；完整回传模型标签与目标列表，
 * 不截断。Android 端不声明 `directml`，并额外声明 `video-subscribe`（远程镜头订阅能力）。
 *
 * 本端另外上报两件服务端无法自行推断的事实：
 * - `inferenceBackends` / `inferenceLimits`：本机各模型配置的加速后端与并发上限，控制台据此
 *   解释并校验「为什么这个设备加不了更多来源」，不必把设备型号硬编码进服务端；
 * - `overConcurrencyLimit`：运行来源数是否超过实际后端的能力上限。
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
        "visual-inference",
        "acceleration-reporting"
    )

    fun build(
        gson: Gson,
        deviceId: String,
        deviceName: String,
        sources: List<HeartbeatSource>,
        modelOptions: List<String>,
        modelLabels: Map<String, List<Pair<String, String>>>,
        components: Map<String, String> = mapOf("detectorApp" to "running"),
        /** 模型键到 `cpu` / `gpu` 的实际后端，只在模型可用时出现。 */
        inferenceBackends: Map<String, String> = emptyMap(),
        /** `模型键@后端` 到并发上限。 */
        inferenceLimits: Map<String, Int> = emptyMap(),
        /** 运行来源数是否超过实际后端的能力上限。 */
        overConcurrencyLimit: Boolean = false
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
                if (source.backendNote.isNotBlank()) addProperty("backendNote", source.backendNote)
                if (source.concurrencyLimit > 0) addProperty("concurrencyLimit", source.concurrencyLimit)
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
            add("inferenceBackends", stringMapJson(inferenceBackends))
            add("inferenceLimits", intMapJson(inferenceLimits))
            addProperty("overConcurrencyLimit", overConcurrencyLimit)
            add("components", JsonObject().apply { components.forEach { (key, value) -> addProperty(key, value) } })
            add("sources", array)
        }
    }

    private fun stringMapJson(values: Map<String, String>): JsonObject = JsonObject().apply {
        for ((key, value) in values) addProperty(key, value)
    }

    private fun intMapJson(values: Map<String, Int>): JsonObject = JsonObject().apply {
        for ((key, value) in values) addProperty(key, value)
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
