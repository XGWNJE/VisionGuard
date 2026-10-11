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
        performanceWarning = null,
        backendNote = "输入 640px，可用加速器 GPU,CPU",
        concurrencyLimit = 2
    )

    private fun build(
        sources: List<HeartbeatSource>,
        inferenceBackends: Map<String, String> = emptyMap(),
        inferenceLimits: Map<String, Int> = emptyMap(),
        overConcurrencyLimit: Boolean = false
    ) = JsonParser.parseString(
        gson.toJson(
            HeartbeatPayload.build(
                gson = gson,
                deviceId = "dev-1",
                deviceName = "视觉推理节点 · Pixel",
                sources = sources,
                modelOptions = listOf("yolo26n_320"),
                modelLabels = HeartbeatPayload.standardModelLabels(listOf("yolo26n_320")),
                inferenceBackends = inferenceBackends,
                inferenceLimits = inferenceLimits,
                overConcurrencyLimit = overConcurrencyLimit
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
        // 控制台据此知道本端会解释「为什么加不了更多来源」
        assertTrue(capabilities.contains("acceleration-reporting"))
        assertFalse(capabilities.contains("directml"))
        assertFalse(capabilities.contains("video-publish"))
    }

    @Test
    fun `推理能力随心跳上报`() {
        val json = build(
            sources = listOf(source("camera", true, true)),
            inferenceBackends = mapOf("yolo26m_640" to "gpu"),
            inferenceLimits = mapOf("yolo26m_640@cpu" to 2, "yolo26m_640@gpu" to 32),
            overConcurrencyLimit = true
        )
        assertEquals("gpu", json["inferenceBackends"].asJsonObject["yolo26m_640"].asString)
        assertEquals(2, json["inferenceLimits"].asJsonObject["yolo26m_640@cpu"].asInt)
        assertEquals(32, json["inferenceLimits"].asJsonObject["yolo26m_640@gpu"].asInt)
        assertTrue(json["overConcurrencyLimit"].asBoolean)
    }

    @Test
    fun `来源上带后端细节与并发上限`() {
        val json = build(listOf(source("camera", true, true)))
        val item = json["sources"].asJsonArray[0].asJsonObject
        assertEquals("输入 640px，可用加速器 GPU,CPU", item["backendNote"].asString)
        assertEquals(2, item["concurrencyLimit"].asInt)
    }

    @Test
    fun `没来源时不带后端细节字段也不报超限`() {
        val json = build(emptyList())
        assertEquals(0, json["sources"].asJsonArray.size())
        assertEquals(0, json["inferenceLimits"].asJsonObject.size())
        assertFalse(json["overConcurrencyLimit"].asBoolean)
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
