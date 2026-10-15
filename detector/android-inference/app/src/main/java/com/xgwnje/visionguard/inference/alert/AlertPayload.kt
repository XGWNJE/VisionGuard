package com.xgwnje.visionguard.inference.alert

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.xgwnje.visionguard.inference.detect.Detection

/**
 * 报警上报负载，字段与统一服务 `WsAlertPush` / `AlertMeta` 契约逐项对应。
 *
 * 与 Windows `Services/ServerPushService.cs#PushAlert` 完全同构，便于两端对拍：
 * `type/alertId/deviceId/deviceName/sourceId/sourceName/timestamp/eventKind/summary/expiresAt/detections/timings/capturedAt`，
 * 检测项为 `{label, confidence, bbox:{x,y,w,h}}`。
 */
object AlertPayload {

    fun build(gson: Gson, event: AlertEvent, deviceId: String, deviceName: String): String {
        val detections = JsonArray()
        for (detection in event.detections) {
            detections.add(detectionJson(detection))
        }

        val timings = JsonObject()
        for ((key, value) in event.timingsMs) timings.addProperty(key, value)

        val message = JsonObject().apply {
            addProperty("type", "alert")
            addProperty("alertId", event.alertId)
            addProperty("deviceId", deviceId)
            addProperty("deviceName", deviceName)
            addProperty("sourceId", event.sourceId)
            addProperty("sourceName", event.sourceName)
            addProperty("timestamp", event.timestampIso)
            addProperty("eventKind", AlertEvent.EVENT_KIND)
            addProperty("summary", AlertEvent.SUMMARY)
            addProperty("expiresAt", event.expiresAtIso)
            add("detections", detections)
            add("timings", timings)
            addProperty("capturedAt", event.timestampIso)
        }
        return gson.toJson(message)
    }

    private fun detectionJson(detection: Detection): JsonObject = JsonObject().apply {
        addProperty("label", detection.label)
        addProperty("confidence", detection.confidence)
        add("bbox", JsonObject().apply {
            addProperty("x", detection.box.left)
            addProperty("y", detection.box.top)
            addProperty("w", detection.box.width)
            addProperty("h", detection.box.height)
        })
    }
}
