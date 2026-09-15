package com.xgwnje.visionguard_android.data.remote

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentDeviceInfoParserTest {
    private val gson = Gson()

    @Test
    fun acceptsCurrentDeviceRecord() {
        val device = parseCurrentDeviceInfo(JsonParser.parseString(currentDeviceJson()), gson)

        assertEquals("device-1", device?.deviceId)
        assertTrue(device?.capabilities?.contains("source-control") == true)
        assertEquals("running", device?.components?.get("detectorApp"))
    }

    @Test
    fun readsSourceLimitFieldsAndDefaultsThemForOlderServers() {
        val withLimit = currentDeviceJson()
            .replace("\"sources\":[]", "\"sources\":[],\"maxSources\":6,\"sourceLimitExceeded\":true")
        val device = parseCurrentDeviceInfo(JsonParser.parseString(withLimit), gson)
        assertEquals(6, device?.maxSources)
        assertEquals(true, device?.sourceLimitExceeded)

        val legacy = parseCurrentDeviceInfo(JsonParser.parseString(currentDeviceJson()), gson)
        assertNull(legacy?.maxSources)
        assertEquals(false, legacy?.sourceLimitExceeded)
    }

    @Test
    fun rejectsOldRecordWithoutCapabilities() {
        val json = currentDeviceJson().replace(
            "\"capabilities\":[\"source-control\"],",
            ""
        )

        assertNull(parseCurrentDeviceInfo(JsonParser.parseString(json), gson))
    }

    @Test
    fun rejectsMalformedCurrentCollections() {
        val json = currentDeviceJson().replace(
            "\"components\":{\"detectorApp\":\"running\"}",
            "\"components\":null"
        )

        assertNull(parseCurrentDeviceInfo(JsonParser.parseString(json), gson))
    }

    private fun currentDeviceJson(): String = """
        {
          "deviceId":"device-1",
          "deviceName":"Detector",
          "online":true,
          "isMonitoring":false,
          "isReady":true,
          "lastSeen":"2026-09-11T00:00:00.000Z",
          "cooldown":5,
          "confidence":0.45,
          "targets":"person",
          "targetSamplingRate":3,
          "modelKey":"yolo26n_320",
          "modelOptions":["yolo26n_320"],
          "canSwitchModelWhileMonitoring":true,
          "hasPendingConfigChanges":false,
          "clientType":"android-detector",
          "capabilities":["source-control"],
          "components":{"detectorApp":"running"},
          "sources":[]
        }
    """.trimIndent()
}
