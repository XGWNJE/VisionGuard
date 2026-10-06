package com.xgwnje.visionguard.account

import org.junit.Assert.*
import org.junit.Test

class DeviceIdentityPolicyTest {
    @Test fun registrationIsIndependentOfVersionAndLocalDataAndSeparatesPackagesAndDevices() {
        val value = DeviceIdentityPolicy.identity("abcdef1234567890", "com.xgwnje.visionguard.notifier")
        assertEquals(64, value.length)
        assertEquals(value, DeviceIdentityPolicy.identity("ABCDEF1234567890", "com.xgwnje.visionguard.notifier"))
        assertNotEquals(value, DeviceIdentityPolicy.identity("abcdef1234567891", "com.xgwnje.visionguard.notifier"))
        assertNotEquals(value, DeviceIdentityPolicy.identity("abcdef1234567890", "com.xgwnje.visionguard.notifier.uipreview"))
        for (invalid in listOf(null, "", "0", "unavailable")) {
            try { DeviceIdentityPolicy.identity(invalid, "com.example"); fail("invalid identity accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun modelGenerationBoundsUtf16AndDoesNotCutEmoji() {
        assertEquals("Android设备", DeviceIdentityPolicy.model(null))
        assertEquals("Model X", DeviceIdentityPolicy.model("Model\nX"))
        assertEquals(48, DeviceIdentityPolicy.model("门".repeat(60)).length)
        assertEquals(47, DeviceIdentityPolicy.model("门".repeat(47) + "😀").length)
    }
}
