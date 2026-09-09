package com.xgwnje.visionguard_android.data.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class WsMessageTest {
    private val gson = Gson()

    @Test
    fun commandCarriesRequestIdentity() {
        val message = WsCommandMessage(
            requestId = "request-12345678",
            targetDeviceId = "detector-1",
            command = "pause"
        )

        val json = gson.toJsonTree(message).asJsonObject
        assertEquals("request-12345678", json["requestId"].asString)
        assertEquals("detector-1", json["targetDeviceId"].asString)
        assertEquals("pause", json["command"].asString)
    }

    @Test
    fun commandAckDefaultsLegacyPayloadToCompletedPhase() {
        val ack = gson.fromJson(
            """{"type":"command-ack","command":"pause","success":true}""",
            WsCommandAck::class.java
        )

        assertEquals("", ack.requestId)
        assertEquals("completed", ack.phase)
    }

    @Test
    fun deviceStatusParsesCapabilitiesAndComponents() {
        val device = gson.fromJson(
            """{"deviceId":"d1","deviceName":"Detector","online":true,"isMonitoring":false,"isReady":true,"lastSeen":"now","capabilities":["request-correlation"],"components":{"detectorApp":"running"}}""",
            DeviceInfo::class.java
        )

        assertEquals(listOf("request-correlation"), device.capabilities)
        assertEquals("running", device.components["detectorApp"])
    }

    @Test
    fun deviceStatusParsesPerSourceConfiguration() {
        val device = gson.fromJson(
            """{"deviceId":"d1","deviceName":"Detector","online":true,"isMonitoring":true,"isReady":true,"lastSeen":"now","sources":[{"sourceId":"front","sourceName":"Front","isMonitoring":true,"isReady":true,"modelKey":"yolo26n_320","cooldown":12,"confidence":0.63,"targets":"person,car","targetSamplingRate":4}]}""",
            DeviceInfo::class.java
        )

        val source = device.sources.single()
        assertEquals(12, source.cooldown)
        assertEquals(0.63, source.confidence!!, 0.0001)
        assertEquals("person,car", source.targets)
        assertEquals(4, source.targetSamplingRate)
    }

    @Test
    fun legacySourceWithoutConfigurationRemainsCompatible() {
        val device = gson.fromJson(
            """{"deviceId":"d1","deviceName":"Legacy","online":true,"isMonitoring":false,"isReady":true,"lastSeen":"now","sources":[{"sourceId":"default","sourceName":"默认来源","isMonitoring":false,"isReady":true,"modelKey":"yolo26n_320"}]}""",
            DeviceInfo::class.java
        )

        val source = device.sources.single()
        assertEquals(null, source.cooldown)
        assertEquals(null, source.confidence)
        assertEquals(null, source.targets)
        assertEquals(null, source.targetSamplingRate)
    }

    @Test
    fun screenshotPushPreservesSourceAssociation() {
        val push = gson.fromJson(
            """{"type":"screenshot-data","alertId":"a1","deviceId":"d1","sourceId":"window-2","sourceName":"Back Door","imageBase64":"AA=="}""",
            WsScreenshotDataPush::class.java
        )

        assertEquals("d1", push.deviceId)
        assertEquals("window-2", push.sourceId)
        assertEquals("Back Door", push.sourceName)
    }
}
