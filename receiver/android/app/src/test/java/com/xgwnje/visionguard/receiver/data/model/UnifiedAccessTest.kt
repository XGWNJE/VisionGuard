package com.xgwnje.visionguard.receiver.data.model

import com.google.gson.Gson
import com.xgwnje.visionguard.receiver.ui.home.buildAlertCardUiModel
import com.xgwnje.visionguard.receiver.ui.home.buildDeviceCardUiModel
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class UnifiedAccessTest {
    @Test fun consoleIdentitySeparatesRoleTypeAndPlatform() {
        val json = Gson().toJsonTree(WsAuthMessage(apiKey = "test", deviceId = "console")).asJsonObject
        assertEquals("console", json["role"].asString)
        assertEquals("console", json["nodeType"].asString)
        assertEquals("android", json["platform"].asString)
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
