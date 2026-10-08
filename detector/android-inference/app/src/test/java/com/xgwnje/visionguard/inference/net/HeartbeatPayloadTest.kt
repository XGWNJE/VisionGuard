package com.xgwnje.visionguard.inference.net

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 心跳负载契约：字段与统一服务 `WsHeartbeat` 对齐，能力集合不包含 Windows 专用项。 */
class HeartbeatPayloadTest {

    private val gson = Gson()

    private fun source(
        id: String,
        monitoring: Boolean,
        ready: Boolean,
        fps: Double = 4.5,
        backend: String = "NNAPI",
        samplingRate: Int = 3
    ) = HeartbeatSource(
        sourceId = id,
        sourceName = "来源 $id",
        isMonitoring = monitoring,
        isReady = ready,
        modelKey = "yolo26n_320",
        actualFps = fps,
        error = if (ready) null else "等待画面",
        activeBackend = backend,
        cooldown = 5,
        confidence = 0.45,
        targets = "person",
        targetSamplingRate = samplingRate,
        monitoringExpected = monitoring,
        lastProgressAtIso = "2026-10-09T00:00:00Z",
        performanceWarning = null
    )

    private fun build(sources: List<HeartbeatSource>) = JsonParser.parseString(
        gson.toJson(
            HeartbeatPayload.build(
                gson = gson,
                deviceId = "dev-1",
                deviceName = "视觉推理节点 · Pixel",
                sources = sources,
                modelOptions = listOf("yolo26n_320"),
                modelLabels = HeartbeatPayload.standardModelLabels(listOf("yolo26n_320"))
            )
        )
    ).asJsonObject

    @Test
    fun `能力集合声明订阅与远控但不声明 directml`() {
        val capabilities = HeartbeatPayload.CAPABILITIES
        assertTrue(capabilities.contains("video-subscribe"))
        assertTrue(capabilities.contains("source-control"))
        assertTrue(capabilities.contains("config-control"))
        assertTrue(capabilities.contains("screenshot-on-demand"))
        assertFalse(capabilities.contains("directml"))
        assertFalse(capabilities.contains("video-publish"))
    }

    @Test
    fun `顶层状态取任一来源的聚合值其余取第一路`() {
        val json = build(listOf(source("camera", monitoring = false, ready = false), source("signal-1", monitoring = true, ready = true)))
        assertEquals("dev-1", json["deviceId"].asString)
        assertTrue(json["isMonitoring"].asBoolean)
        assertTrue(json["isReady"].asBoolean)
        assertEquals(5, json["cooldown"].asInt)
        assertEquals(0.45, json["confidence"].asDouble, 1e-6)
        assertEquals("person", json["targets"].asString)
        assertEquals(3, json["targetSamplingRate"].asInt)
        assertEquals("yolo26n_320", json["modelKey"].asString)
        assertEquals(1, json["modelOptions"].asJsonArray.size())
    }

    @Test
    fun `没有来源时给出可用的空状态`() {
        val json = build(emptyList())
        assertFalse(json["isMonitoring"].asBoolean)
        assertFalse(json["isReady"].asBoolean)
        assertEquals(0, json["sources"].asJsonArray.size())
        assertEquals("person", json["targets"].asString)
    }

    @Test
    fun `逐来源字段完整且采样频率被夹到一至五`() {
        val json = build(listOf(source("camera", true, true, samplingRate = 9)))
        val item = json["sources"].asJsonArray[0].asJsonObject
        assertEquals("camera", item["sourceId"].asString)
        assertEquals("来源 camera", item["sourceName"].asString)
        assertEquals("NNAPI", item["activeBackend"].asString)
        assertEquals(4.5, item["actualFps"].asDouble, 1e-6)
        assertEquals("2026-10-09T00:00:00Z", item["lastProgressAt"].asString)
        assertTrue(item["monitoringExpected"].asBoolean)
        assertEquals(5, item["targetSamplingRate"].asInt)
    }

    @Test
    fun `模型标签完整回传八十类中英文`() {
        val json = build(listOf(source("camera", true, true)))
        val labels = json["modelLabels"].asJsonObject["yolo26n_320"].asJsonArray
        assertEquals(80, labels.size())
        assertEquals("person", labels[0].asJsonObject["value"].asString)
        assertEquals("人", labels[0].asJsonObject["label"].asString)
    }

    @Test
    fun `组件状态用于控制台显示运行阶段`() {
        val json = build(listOf(source("camera", true, true)))
        assertEquals("running", json["components"].asJsonObject["detectorApp"].asString)
    }
}
