package com.xgwnje.visionguard.notifier

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.xgwnje.visionguard.account.ClientUpdater
import com.xgwnje.visionguard.account.StableReleasePolicy
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Optional device regression: install an older, isolated Debug app and supply a newer APK
 * with the same package/signature as the test-only current-client.apk asset. Normal test APKs
 * omit that asset and skip these cases. Only transport is intercepted; the production release
 * policy, HTTP download, cache, PackageManager checks and FileProvider all run on the device.
 */
@RunWith(AndroidJUnit4::class)
class ClientUpdaterTransportTest {
    private lateinit var context: HandoffContext
    private lateinit var server: AssetServer
    private lateinit var apk: ByteArray
    private lateinit var candidate: String
    private lateinit var installed: String
    private val updaters = mutableListOf<ClientUpdater>()
    private lateinit var target: File
    private lateinit var directory: File
    private var initialized = false
    private var foregroundActivity: MainActivity? = null
    private var preserveInstallerCandidate = false

    @Suppress("DEPRECATION") @Before fun prepare() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Optional signed update fixture is not bundled", "current-client.apk" in instrumentation.context.assets.list("").orEmpty())
        val realContext = instrumentation.targetContext
        // Never run cache cleanup against a user's installed client.
        assumeTrue("Use an isolated acceptance package", realContext.packageName.endsWith(".acceptance"))
        context = HandoffContext(realContext)
        apk = instrumentation.context.assets.open("current-client.apk").use { it.readBytes() }
        val archive = File(realContext.cacheDir, "update-fixture-identity.apk")
        try {
            archive.writeBytes(apk)
            val next = realContext.packageManager.getPackageArchiveInfo(archive.absolutePath, 0)!!
            val own = realContext.packageManager.getPackageInfo(realContext.packageName, 0)
            candidate = requireNotNull(next.versionName)
            installed = requireNotNull(own.versionName)
            val newCode = if (Build.VERSION.SDK_INT >= 28) next.longVersionCode else next.versionCode.toLong()
            val oldCode = if (Build.VERSION.SDK_INT >= 28) own.longVersionCode else own.versionCode.toLong()
            assumeTrue("Fixture must update this exact package", next.packageName == own.packageName && newCode > oldCode)
        } finally { archive.delete() }
        directory = File(realContext.cacheDir, "client-updates").apply { mkdirs() }
        target = File(directory, "VisionGuard-Notifier-v$candidate.apk")
        assertTrue("Cannot clear fixture cache", !target.exists() || target.delete())
        File(directory, target.name + ".part").delete()
        server = AssetServer(apk)
        initialized = true
    }

    @After fun finish() {
        updaters.forEach { it.close() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (foregroundActivity != null) {
            repeat(3) {
                if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.android.settings") {
                    instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    Thread.sleep(100)
                }
            }
            instrumentation.runOnMainSync { foregroundActivity?.finish() }
        }
        if (initialized) {
            server.close()
            if (!preserveInstallerCandidate) target.delete()
            File(directory, target.name + ".part").delete()
        }
    }

    @Test fun verifiedDownloadReusesCacheAndPreparesPermissionHandoffOnMainThread() {
        foreground()
        val updater = updater()
        discover(updater)
        updater.download(); awaitIdle(updater)
        assertTrue(updater.state.value.message, updater.state.value.ready)
        assertEquals(1, server.requests.get())
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", target)
        val shared = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertEquals(hash(apk), hash(shared))
        updater.download(); awaitIdle(updater)
        assertTrue(updater.state.value.ready)
        assertEquals("Verified cached APK was downloaded again", 1, server.requests.get())

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            updater.install()
            assertTrue("Install verification blocked the main thread", updater.state.value.busy)
            updater.install()
        }
        awaitIdle(updater)
        assertEquals("Duplicate install started another handoff", 1, context.launches.get())
        assertTrue(context.launchedOnMain)
        val intent = requireNotNull(context.intent)
        if (!context.packageManager.canRequestPackageInstalls()) {
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
            assertEquals("package:${context.packageName}", intent.data.toString())
            assertEquals("请在系统设置允许安装后返回，再点安装", updater.state.value.message)
        } else {
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals("已请求打开系统安装器；请以系统界面为准", updater.state.value.message)
        }
        // The context records the intent; this is preparation, not installation acceptance.
    }

    @Test fun cancellingActiveDownloadCleansPartialAndAllowsRetry() {
        server.mode = Mode.SLOW
        val updater = updater(); discover(updater); updater.download()
        assertTrue("Download never started", server.started.await(15, TimeUnit.SECONDS))
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (updater.state.value.bytes == 0L && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
        assertTrue("No payload reached the updater", updater.state.value.bytes > 0)
        updater.cancel(); awaitIdle(updater)
        assertFalse(updater.state.value.ready)
        assertEquals("下载已取消", updater.state.value.message)
        assertFalse(target.exists())
        assertFalse(File(directory, target.name + ".part").exists())
        server.mode = Mode.VALID; server.resume.countDown()
        updater.download(); awaitIdle(updater)
        assertTrue(updater.state.value.message, updater.state.value.ready)
        assertEquals(hash(apk), hash(target.readBytes()))
    }

    @Test fun truncatedAndCorruptDownloadsNeverBecomeInstallableAndCanRecover() {
        val updater = updater(); discover(updater)
        for (mode in listOf(Mode.SHORT, Mode.CORRUPT, Mode.OVERSIZED)) {
            server.mode = mode
            updater.download(); awaitIdle(updater)
            assertFalse("$mode became installable", updater.state.value.ready)
            assertFalse("$mode retained a candidate", target.exists())
            assertFalse("$mode retained partial data", File(directory, target.name + ".part").exists())
        }
        server.mode = Mode.VALID; updater.download(); awaitIdle(updater)
        assertTrue(updater.state.value.message, updater.state.value.ready)
    }

    @Test fun corruptionAfterDownloadClearsInstallStateAndAllowsFreshDownload() {
        val updater = updater(); discover(updater); updater.download(); awaitIdle(updater)
        assertTrue(updater.state.value.ready)
        target.outputStream().use { it.write(0) }
        updater.install(); awaitIdle(updater)
        assertEquals("安装包大小不匹配", updater.state.value.message)
        assertFalse(updater.state.value.ready)
        assertEquals("Corrupt APK reached an installation activity", 0, context.launches.get())
        updater.download(); awaitIdle(updater)
        assertTrue(updater.state.value.message, updater.state.value.ready)
        assertEquals(2, server.requests.get())
    }

    @Test fun packageManagerRejectsValidHashWithWrongDeclaredVersion() {
        val version = StableReleasePolicy.version(candidate)!!.let { "${it[0]}.${it[1]}.${it[2] + 1}" }
        val updater = updater(version); discover(updater); updater.download(); awaitIdle(updater)
        assertFalse(updater.state.value.ready)
        assertEquals("安装包不属于当前客户端或版本不符", updater.state.value.message)
        assertFalse(File(directory, "VisionGuard-Notifier-v$version.apk").exists())
        assertFalse(File(directory, "VisionGuard-Notifier-v$version.apk.part").exists())
        assertEquals(0, context.launches.get())
    }

    @Test fun permissionSettingsHandoffOpensWithoutChangingInstallPermission() {
        val realContext = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("This case only opens the permission page; it never changes permission", !realContext.packageManager.canRequestPackageInstalls())
        foreground()
        val updater = updater(actualHandoff = true)
        discover(updater); updater.download(); awaitIdle(updater)
        assertTrue(updater.state.value.message, updater.state.value.ready)
        updater.install(); awaitIdle(updater)
        assertEquals("请在系统设置允许安装后返回，再点安装", updater.state.value.message)
        assertFalse("Install permission was unexpectedly changed", realContext.packageManager.canRequestPackageInstalls())
        assertTrue("Opening a permission page must not imply installation", updater.state.value.ready)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.elapsedRealtime() + 10_000
        val label = realContext.packageManager.getApplicationLabel(realContext.applicationInfo).toString()
        var openedOwnRow = false
        fun loadedSwitch(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.isCheckable) return !node.isChecked
            return (0 until node.childCount).any { loadedSwitch(node.getChild(it)) }
        }
        fun loadedPermissionPage(): Boolean = automation.rootInActiveWindow.let {
            it?.packageName?.toString() == "com.android.settings" && loadedSwitch(it)
        }
        while (!loadedPermissionPage() && SystemClock.elapsedRealtime() < deadline) {
            // Some OEMs ignore the package URI and open the source list. Navigate only
            // the uniquely named fixture app; never toggle an installation permission.
            val root = automation.rootInActiveWindow
            if (!openedOwnRow && root?.packageName?.toString() == "com.android.settings") {
                val labels = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                fun findOwnRow(node: android.view.accessibility.AccessibilityNodeInfo?) {
                    if (node == null) return
                    if (node.text?.toString() == label) labels += node
                    for (index in 0 until node.childCount) findOwnRow(node.getChild(index))
                }
                findOwnRow(root)
                if (labels.size == 1) {
                    var row: android.view.accessibility.AccessibilityNodeInfo? = labels.single()
                    while (row != null && !row.isClickable) row = row.parent
                    openedOwnRow = row?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
                }
            }
            Thread.sleep(50)
        }
        automation.takeScreenshot()?.let { screenshot ->
            File(realContext.externalCacheDir, "updater-permission.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        }
        val nodes = mutableListOf<String>()
        fun describe(node: android.view.accessibility.AccessibilityNodeInfo?) {
            if (node == null) return
            nodes += "${node.className}: ${node.text} / ${node.contentDescription}; checkable=${node.isCheckable}; checked=${node.isChecked}"
            for (index in 0 until node.childCount) describe(node.getChild(index))
        }
        describe(automation.rootInActiveWindow)
        File(realContext.externalCacheDir, "updater-permission.txt").writeText(nodes.joinToString("\n"))
        assertEquals("The system permission page did not actually open", "com.android.settings", automation.rootInActiveWindow?.packageName?.toString())
        assertTrue("The disabled installation permission switch did not finish loading", loadedPermissionPage())
    }

    @Test fun backgroundInstallRequiresReturnToAppAndKeepsVerifiedCache() {
        val updater = updater(); discover(updater); updater.download(); awaitIdle(updater)
        assertTrue(updater.state.value.ready)
        updater.install(); awaitIdle(updater)
        assertEquals("请返回应用后再点安装", updater.state.value.message)
        assertTrue(updater.state.value.ready)
        assertEquals(0, context.launches.get())
    }

    /** Opt-in visible system handoff; the operator approves this fixture's permission/install UI.
     * A completed test proves handoff only. Check the installed package/version/hash separately.
     */
    @Test fun actualInstallerHandoffWithOperatorApproval() {
        assumeTrue("Actual installation requires explicit operator approval",
            InstrumentationRegistry.getArguments().getString("installHandoff") == "true")
        assumeTrue("Approve installation permission for this isolated fixture before running",
            InstrumentationRegistry.getInstrumentation().targetContext.packageManager.canRequestPackageInstalls())
        foreground()
        val updater = updater(actualHandoff = true)
        discover(updater); updater.download(); awaitIdle(updater)
        assertTrue(updater.state.value.message, updater.state.value.ready)
        preserveInstallerCandidate = true
        updater.install(); awaitIdle(updater)
        assertEquals("已请求打开系统安装器；请以系统界面为准", updater.state.value.message)
        println("Actual installer requested; installation is not yet confirmed")
        // Keep the calling Activity/process alive while the operator observes OEM
        // confirmation and the system installer. Replacing this app may end instrumentation.
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
    }

    @Test fun liveGitHubCheckAllowsUiThreadDisposalWithoutNetworkCrash() {
        assumeTrue("Real GitHub access is opt-in", InstrumentationRegistry.getArguments().getString("liveUpdates") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val updater = ClientUpdater(instrumentation.targetContext, installed, "android-notifier")
        updaters += updater
        updater.check(); awaitIdle(updater)
        assertTrue("Real GitHub check failed: ${updater.state.value.message}", updater.state.value.update != null || updater.state.value.message == "当前已是最新版本")
        println("Live GitHub stable check: ${updater.state.value.message}")
        instrumentation.runOnMainSync { updater.close(); updater.close() }
        // Give disposal/lifecycle callbacks a turn to expose a main-thread socket close crash.
        Thread.sleep(200)
        instrumentation.runOnMainSync { }
    }

    private fun discover(updater: ClientUpdater) {
        updater.check(); awaitIdle(updater)
        assertNotNull(updater.state.value.message, updater.state.value.update)
    }

    private fun updater(version: String = candidate, actualHandoff: Boolean = false): ClientUpdater {
        val updater = ClientUpdater(if (actualHandoff) requireNotNull(foregroundActivity) else context, installed, "android-notifier")
        val name = "VisionGuard-Notifier-v$version.apk"
        val release = JSONArray().put(JSONObject()
            .put("tag_name", "v$version").put("published_at", "2026-10-05T00:00:00Z")
            .put("draft", false).put("prerelease", false)
            .put("assets", JSONArray().put(JSONObject().put("name", name)
                .put("browser_download_url", "https://github.com/${StableReleasePolicy.REPOSITORY}/releases/download/v$version/$name")
                .put("size", apk.size).put("digest", "sha256:${hash(apk)}").put("state", "uploaded"))))
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host == "api.github.com") {
                Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body(release.toString().toResponseBody("application/json".toMediaType())).build()
            } else {
                assertEquals("github.com", request.url.host)
                chain.proceed(request.newBuilder().url("http://127.0.0.1:${server.port}/asset").build())
            }
        }.build()
        // Transport injection stays in the test APK; production URLs and validation are unchanged.
        ClientUpdater::class.java.getDeclaredField("http").apply { isAccessible = true }.set(updater, transport)
        updaters += updater
        return updater
    }

    private fun awaitIdle(updater: ClientUpdater) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (updater.state.value.busy && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
        assertFalse("Updater did not finish: ${updater.state.value}", updater.state.value.busy)
    }

    private fun foreground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Launch normally through the shell, as with an adb UI test. An instrumentation
        // process in the background cannot reliably launch its own Activity on all devices.
        val command = "am start -W -n ${instrumentation.targetContext.packageName}/${MainActivity::class.java.name}"
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (foregroundActivity == null && SystemClock.elapsedRealtime() < deadline) {
            val packageName = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
            if (packageName?.endsWith("permissioncontroller") == true || packageName == "com.lbe.security.miui")
                instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            instrumentation.runOnMainSync {
                foregroundActivity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull()
            }
            if (foregroundActivity == null) Thread.sleep(50)
        }
        context = HandoffContext(requireNotNull(foregroundActivity) { "The isolated application did not reach the foreground" })
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class HandoffContext(base: Context) : ContextWrapper(base) {
        val launches = AtomicInteger()
        @Volatile var intent: Intent? = null
        @Volatile var launchedOnMain = false
        override fun getApplicationContext(): Context = this
        override fun startActivity(intent: Intent) {
            this.intent = intent
            launchedOnMain = Looper.myLooper() == Looper.getMainLooper()
            launches.incrementAndGet()
        }
    }

    private enum class Mode { VALID, SLOW, SHORT, CORRUPT, OVERSIZED }
    private class AssetServer(private val apk: ByteArray) : AutoCloseable {
        private val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = listener.localPort
        val requests = AtomicInteger()
        val started = CountDownLatch(1)
        val resume = CountDownLatch(1)
        @Volatile var mode = Mode.VALID
        private val worker = thread(isDaemon = true, name = "update-asset-fixture") {
            while (!listener.isClosed) {
                runCatching { listener.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    while (input.readLine()?.isNotEmpty() == true) { /* consume HTTP headers */ }
                    requests.incrementAndGet()
                    val current = mode
                    val payload = if (current == Mode.CORRUPT) apk.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
                        else if (current == Mode.OVERSIZED) apk + byteArrayOf(0) else apk
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    if (current == Mode.SLOW) {
                        output.write(payload, 0, 65_536); output.flush(); started.countDown()
                        check(resume.await(20, TimeUnit.SECONDS))
                        output.write(payload, 65_536, payload.size - 65_536)
                    } else output.write(payload, 0, if (current == Mode.SHORT) payload.size / 2 else payload.size)
                    output.flush()
                } }
            }
        }
        override fun close() { resume.countDown(); listener.close(); worker.join(1_000) }
    }
}
