package com.xgwnje.visionguard.account
import org.junit.Assert.*
import org.junit.Test

class StableReleasePolicyTest {
    private fun release(version: String, day: Int, client: String = "Detector", digest: String = "sha256:" + "a".repeat(64)) = StableRelease("v$version", "2026-10-${day.toString().padStart(2, '0')}T00:00:00Z", false, false,
        listOf(ReleaseAsset("VisionGuard-$client-v$version.apk", "https://github.com/XGWNJE/VisionGuard/releases/download/v$version/VisionGuard-$client-v$version.apk", 123, digest, "uploaded")))
    @Test fun partialReleaseUsesLatestChronologicalComponentAndIgnoresHistoricalHighVersion() {
        val releases = listOf(release("0.6.0", 3), release("4.5.1", 1), release("0.7.0", 4), release("0.8.0", 5, "Notifier"))
        assertEquals("0.7.0", StableReleasePolicy.select(releases, "0.6.0", "android-camera")!!.version)
        assertEquals("0.8.0", StableReleasePolicy.select(releases, "0.6.0", "android-notifier")!!.version)
    }
    @Test fun damagedLatestAssetFailsRatherThanFallingBackToOlderVersion() {
        assertTrue(runCatching { StableReleasePolicy.select(listOf(release("0.6.0", 3), release("0.7.0", 4), release("0.8.0", 5, digest = "")), "0.6.0", "android-camera") }.isFailure)
    }
    @Test fun unpublishedBuildStaysInItsVersionLineButPublishedMajorUpgradeCanBeSelected() {
        assertNull(StableReleasePolicy.select(listOf(release("4.5.1", 1)), "0.6.0", "android-camera"))
        assertEquals("1.0.0", StableReleasePolicy.select(listOf(release("0.6.0", 3), release("1.0.0", 4)), "0.6.0", "android-camera")!!.version)
    }
    @Test fun draftsPrereleasesWrongAssetsAndUntrustedUrlsAreRejected() {
        assertNull(StableReleasePolicy.select(listOf(release("0.7.0", 4).copy(draft = true), release("0.8.0", 5).copy(prerelease = true), release("0.9.0", 6, "Receiver")), "0.6.0", "android-camera"))
        val bad = release("0.7.0", 4).let { it.copy(assets = it.assets.map { a -> a.copy(url = a.url.replace("github.com", "example.com")) }) }
        assertTrue(runCatching { StableReleasePolicy.select(listOf(bad), "0.6.0", "android-camera") }.isFailure)
        assertTrue(runCatching { StableReleasePolicy.select(listOf(release("0.7.0", 4)), "0.6.0", "android-console") }.isFailure)
    }
    @Test fun versionsAreNumericAndMalformedVersionsAreNotStable() {
        assertEquals("0.10.0", StableReleasePolicy.select(listOf(release("0.10.0", 4)), "0.9.0", "android-camera")!!.version)
        for (bad in listOf("1", "1.0", "01.0.0", "1.0.0-beta", "999999999999.0.0", "-1.0.0")) assertNull(StableReleasePolicy.version(bad))
    }
}
