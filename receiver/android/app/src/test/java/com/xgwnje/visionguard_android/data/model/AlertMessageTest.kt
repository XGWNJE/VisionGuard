package com.xgwnje.visionguard_android.data.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class AlertMessageTest {
    @Test
    fun sourceIdentityIsReadAndLegacyPayloadRemainsCompatible() {
        val current = Gson().fromJson(
            """{"alertId":"a1","deviceId":"d1","deviceName":"Detector","sourceId":"window-1","sourceName":"Front Door","timestamp":"now"}""",
            AlertMessage::class.java,
        )
        assertEquals("window-1", current.sourceId)
        assertEquals("Front Door", current.sourceName)

        val legacy = Gson().fromJson(
            """{"alertId":"a2","deviceId":"d1","deviceName":"Detector","timestamp":"now"}""",
            AlertMessage::class.java,
        )
        assertEquals("", legacy.sourceId)
        assertEquals("", legacy.sourceName)
    }
}
