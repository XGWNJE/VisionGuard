package com.xgwnje.visionguard.notifier

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xgwnje.visionguard.account.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ClientUpdaterCacheTest {
    @Suppress("DEPRECATION") @Test fun damagedCacheThatCannotBeRemovedNeverBecomesInstallable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val updater = ClientUpdater(context, "android-notifier")
        val installed = requireNotNull(context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        val parts = StableReleasePolicy.version(installed)!!
        val newer = "${parts[0]}.${parts[1]}.${parts[2] + 1}"
        val name = "cache-test-${UUID.randomUUID()}.apk"
        val damaged = File(context.cacheDir, "client-updates/$name").apply { mkdirs() }
        val retained = File(damaged, "retained").apply { writeText("fixture") }
        try {
            @Suppress("UNCHECKED_CAST")
            val state = ClientUpdater::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(updater) as MutableStateFlow<UpdateState>
            state.value = UpdateState(update = ClientUpdate(newer, ReleaseAsset(name, "http://127.0.0.1:1/unused", -1, "sha256:" + "0".repeat(64), "uploaded")))
            updater.download()
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
            while (updater.state.value.busy && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
            assertFalse("Cache failure did not complete", updater.state.value.busy)
            assertFalse("Unverified cache was offered for installation", updater.state.value.ready)
            assertEquals("无法移除损坏的暂存安装包，请重试", updater.state.value.message)
            assertEquals("fixture", retained.readText())
        } finally {
            updater.close()
            retained.delete()
            damaged.delete()
        }
    }
}
