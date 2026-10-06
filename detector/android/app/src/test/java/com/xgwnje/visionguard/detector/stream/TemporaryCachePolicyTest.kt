package com.xgwnje.visionguard.detector.stream

import com.xgwnje.visionguard.account.TemporaryCachePolicy as Policy
import org.junit.Assert.*
import org.junit.Test

class TemporaryCachePolicyTest {
    @Test fun protectedAndRecentPackagesSurviveEvenAtTheRetentionBoundary() {
        val name = "VisionGuard-Detector-v0.6.5.apk"
        val now = 10 * Policy.DAY
        assertFalse(Policy.expiredUpdate(name, now - 7 * Policy.DAY, now, false))
        assertTrue(Policy.expiredUpdate(name, now - 7 * Policy.DAY - 1, now, false))
        assertFalse(Policy.expiredUpdate(name, 0, now, true))
        assertFalse(Policy.expiredUpdate(name, now + Policy.DAY, now, false))
    }
    @Test fun unknownFilesAndPathsAreNeverCacheDeletionCandidates() {
        for (name in listOf("config.json", "model.onnx", "../VisionGuard-Detector-v0.6.5.apk", "VisionGuard-Detector-v0.6.5.apk.backup", "random.part"))
            assertFalse(Policy.expiredUpdate(name, 0, 10 * Policy.DAY, false))
        assertTrue(Policy.expiredUpdate("VisionGuard-Notifier-v0.6.5.apk.part", 0, 10 * Policy.DAY, false))
    }
}
