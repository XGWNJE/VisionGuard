package com.xgwnje.visionguard.inference.alert

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.xgwnje.visionguard.inference.detect.Detection
import com.xgwnje.visionguard.inference.detect.DetectionBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** 报警负载必须逐字段满足统一服务契约，避免端上改名后线上静默拒收。 */
class AlertPayloadTest {

    private val gson = Gson()

    private fun event() = AlertEvent(
        alertId = "11111111-2222-3333-4444-555555555555",
        sourceId = "default",
        sourceName = "本机相机",
        detections = listOf(
            Detection(0, "person", 0.87f, DetectionBox(10f, 20f, 30f, 40f)),
            Detection(2, "car", 0.55f, DetectionBox(1.5f, 2.5f, 3.5f, 4.5f))
        ),
        timingsMs = mapOf("captureMs" to 5L, "preprocessMs" to 7L, "inferMs" to 42L, "parseMs" to 1L, "processMs" to 55L),
        timestampMs = 1_700_000_000_000L
    )

    @Test
    fun `负载包含服务端要求的全部字段`() {
        val json = JsonParser.parseString(
            AlertPayload.build(gson, event(), deviceId = "dev-1", deviceName = "视觉推理节点 · Pixel", capturedAtIso = "2026-10-09T00:00:00Z")
        ).asJsonObject

        assertEquals("alert", json["type"].asString)
        assertEquals("11111111-2222-3333-4444-555555555555", json["alertId"].asString)
        assertEquals("dev-1", json["deviceId"].asString)
        assertEquals("视觉推理节点 · Pixel", json["deviceName"].asString)
        assertEquals("default", json["sourceId"].asString)
        assertEquals("本机相机", json["sourceName"].asString)
        assertEquals("visual-detection", json["eventKind"].asString)
        assertEquals("视觉检测告警", json["summary"].asString)
        assertEquals("2026-10-09T00:00:00Z", json["capturedAt"].asString)
        assertEquals(5, json["timings"].asJsonObject.size())
    }

    @Test
    fun `实时期限固定为事件后三十秒`() {
        val json = JsonParser.parseString(AlertPayload.build(gson, event(), "dev-1", "节点", "2026-10-09T00:00:00Z")).asJsonObject
        val timestamp = Instant.parse(json["timestamp"].asString)
        val expiresAt = Instant.parse(json["expiresAt"].asString)
        assertEquals(30L, expiresAt.epochSecond - timestamp.epochSecond)
        assertTrue(expiresAt.toEpochMilli() - timestamp.toEpochMilli() <= AlertEvent.REALTIME_DEADLINE_MS)
    }

    @Test
    fun `检测项使用 label confidence 与 bbox 四字段`() {
        val json = JsonParser.parseString(AlertPayload.build(gson, event(), "dev-1", "节点", "2026-10-09T00:00:00Z")).asJsonObject
        val detections = json["detections"].asJsonArray
        assertEquals(2, detections.size())

        val first = detections[0].asJsonObject
        assertEquals("person", first["label"].asString)
        assertEquals(0.87f, first["confidence"].asFloat, 1e-6f)
        val bbox = first["bbox"].asJsonObject
        assertEquals(setOf("x", "y", "w", "h"), bbox.keySet())
        assertEquals(10f, bbox["x"].asFloat, 1e-6f)
        assertEquals(40f, bbox["h"].asFloat, 1e-6f)
    }
}
