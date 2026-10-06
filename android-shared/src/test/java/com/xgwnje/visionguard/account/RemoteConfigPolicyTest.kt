package com.xgwnje.visionguard.account

import org.junit.Assert.*
import org.junit.Test

class RemoteConfigPolicyTest {
    @Test fun cameraPolicyRejectsInvalidOrRunningResolution() {
        assertEquals("720p", RemoteConfigPolicy.cameraValue("cameraResolution", "720p", false))
        assertEquals("true", RemoteConfigPolicy.cameraValue("cameraHidePreview", "true", true))
        for ((key, value, running) in listOf(Triple("cameraResolution", "720p", true), Triple("cameraResolution", "1080p", false), Triple("cameraDimScreen", "1", false), Triple("unknown", "false", false))) {
            assertTrue(runCatching { RemoteConfigPolicy.cameraValue(key, value, running) }.isFailure)
        }
    }
    @Test fun loopCountAndAudioIdAreBounded() {
        assertEquals(1, RemoteConfigPolicy.loopCount("1")); assertEquals(10, RemoteConfigPolicy.loopCount("10"))
        for (value in listOf("0", "11", "01", "1.5", "")) assertTrue(runCatching { RemoteConfigPolicy.loopCount(value) }.isFailure)
        assertTrue(RemoteConfigPolicy.audioId("library:" + "a".repeat(64)))
        assertFalse(RemoteConfigPolicy.audioId("library:../../private"))
        assertEquals(512 * 1024, RemoteConfigPolicy.MAX_AUDIO_BYTES)
        assertEquals(100, RemoteConfigPolicy.MAX_AUDIO_ENTRIES)
    }
}
