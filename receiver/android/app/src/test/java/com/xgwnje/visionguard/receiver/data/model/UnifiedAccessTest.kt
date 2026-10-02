package com.xgwnje.visionguard.receiver.data.model

import com.google.gson.Gson
import com.xgwnje.visionguard.receiver.ui.home.buildAlertCardUiModel
import com.xgwnje.visionguard.receiver.ui.home.buildDeviceCardUiModel
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class UnifiedAccessTest {
    @Test fun cameraPublishesVideoWithoutInferenceControls() {
        val camera = DeviceInfo("c", "镜头", true, false, true, "now", component = "android-camera",
            clientType = "android-camera", platform = "android", isStreaming = true, capabilities = listOf("video-publish"))
        val card = buildDeviceCardUiModel(camera)
        assertFalse(card.showLegacyControls)
        assertFalse(card.controlsEnabled)
        assertEquals("推流中", card.statusLabel)
        assertEquals("镜头推流", card.typeLabel)
        assertEquals("推流已停止", buildDeviceCardUiModel(camera.copy(isStreaming = false)).statusLabel)
        assertEquals("离线", buildDeviceCardUiModel(camera.copy(online = false)).statusLabel)
    }
    @Test fun consoleAuthCannotSelfGrantIdentity() {
        val json = Gson().toJsonTree(WsAuthMessage(token = "test-token")).asJsonObject
        assertEquals("auth", json["type"].asString)
        assertEquals("test-token", json["token"].asString)
        assertFalse(json.has("role"))
        assertFalse(json.has("deviceId"))
        assertFalse(json.has("apiKey"))
    }

    @Test fun sensorAndInterruptionEventsHaveMeaningWithoutDetectionsOrPicture() {
        val event = AlertMessage(alertId = "a", deviceId = "sensor", eventKind = "sensor-detection",
            nodeType = "sensor", summary = "有人经过", expiresAt = "2026-10-02T00:00:30Z")
        assertEquals("有人经过", buildAlertCardUiModel(event).targetChips.single().label)
        assertTrue(event.isRealtime(Instant.parse("2026-10-02T00:00:29Z").toEpochMilli()))
        assertFalse(event.isRealtime(Instant.parse("2026-10-02T00:00:30Z").toEpochMilli()))
        assertFalse(event.copy(expiresAt = "bad").isRealtime(0))
        assertEquals("检测运行中断", event.copy(eventKind = "detection-interrupted", summary = "").eventLabel())
    }

    @Test fun notificationNodeHasNoDetectorControls() {
        val node = DeviceInfo("n", "通知节点", true, false, true, "now", role = "notifier",
            nodeType = "notification", platform = "linux", clientType = "notification",
            capabilities = listOf("notification-receipt", "connection-watchdog"))
        val card = buildDeviceCardUiModel(node)
        assertFalse(card.controlsEnabled)
        assertFalse(card.showLegacyControls)
        assertEquals("通知节点", card.typeLabel)
    }
}
