package com.xgwnje.visionguard_android.ui.home

import com.xgwnje.visionguard_android.data.model.AlertMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePreferencesTest {
    private val alerts = listOf(
        AlertMessage(alertId = "a", deviceId = "pc", deviceName = "电脑", sourceId = "cam-1", sourceName = "门口", createdAt = 1),
        AlertMessage(alertId = "b", deviceId = "pc", deviceName = "电脑", sourceId = "cam-2", sourceName = "车库", createdAt = 2),
        AlertMessage(alertId = "c", deviceId = "old", deviceName = "旧设备", sourceId = "", sourceName = "", createdAt = 3),
        AlertMessage(alertId = "d", deviceId = "pc", deviceName = "电脑", sourceId = "cam-1", sourceName = "新门口", createdAt = 4),
    )

    @Test fun stableKeysKeepSourcesAndLegacyDefaultSeparate() {
        assertEquals("old/default", alerts[2].sourcePreferenceKey())
        assertEquals(listOf("a", "d"), filterAlertsBySource(alerts, "pc/cam-1").map { it.alertId })
    }

    @Test fun optionsUseLatestNameSnapshotWithoutMergingIdentity() {
        val options = buildAlertSourceOptions(alerts)
        assertEquals(3, options.size)
        assertTrue(options.any { it.key == "pc/cam-1" && it.label == "电脑 · 新门口" })
        assertTrue(options.any { it.key == "old/default" && it.label == "旧设备 · 默认来源" })
    }

    @Test fun mutingOneSourceDoesNotMuteOthersOrRemoveHistory() {
        val muted = setOf("pc/cam-1")
        assertTrue(!shouldNotifyForAlert(alerts[0], muted))
        assertTrue(shouldNotifyForAlert(alerts[1], muted))
        assertEquals(4, alerts.size)
    }
}
