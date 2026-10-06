package com.xgwnje.visionguard.account

import android.content.Context
import android.content.Intent
import android.app.Activity
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

data class UpdateState(val busy: Boolean = false, val downloading: Boolean = false, val bytes: Long = 0, val update: ClientUpdate? = null, val ready: Boolean = false, val message: String = "")
class ClientUpdater(context: Context, private val installed: String, private val client: String) {
    companion object {
        private val operations = Mutex()
        private val protectedFiles = java.util.concurrent.ConcurrentHashMap<ClientUpdater, File>()
        suspend fun <T> withCacheLock(block: () -> T): T = operations.withLock { block() }
        fun isProtected(file: File) = protectedFiles.values.any { it.absoluteFile == file.absoluteFile }
    }
    private val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().firstOrNull()?.let { WeakReference(it) }
    private val context = context.applicationContext
    private val directory = File(this.context.cacheDir.canonicalFile, "client-updates").apply { mkdirs() }
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    @Volatile private var call: Call? = null
    @Volatile private var cancelled = false
    @Volatile private var closed = false
    private var job: Job? = null
    @Synchronized fun check() {
        if (closed || mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, message = "正在检查 GitHub 稳定版…")
        cancelled = false
        job = scope.launch { operations.withLock {
            try {
                val releases = mutableListOf<StableRelease>()
                for (page in 1..10) {
                    val request = Request.Builder().url("https://api.github.com/repos/${StableReleasePolicy.REPOSITORY}/releases?per_page=100&page=$page").header("User-Agent", "VisionGuard/$installed").header("Accept", "application/vnd.github+json").build()
                    val rows = execute(request).use { response ->
                        check(response.isSuccessful) { "检查失败（HTTP ${response.code}）" }
                        val body = response.body ?: error("发行列表为空")
                        val text = body.byteStream().use { input ->
                            val output = ByteArrayOutputStream(); val buffer = ByteArray(65536)
                            while (true) { val count = input.read(buffer); if (count < 0) break; check(output.size() + count <= 8 * 1024 * 1024) { "发行列表过大" }; output.write(buffer, 0, count) }
                            output.toString("UTF-8")
                        }; JSONArray(text)
                    }
                    for (i in 0 until rows.length()) {
                        val item = rows.getJSONObject(i); val assets = item.optJSONArray("assets") ?: JSONArray()
                        releases += StableRelease(item.optString("tag_name"), item.optString("published_at"), item.optBoolean("draft"), item.optBoolean("prerelease"),
                            (0 until assets.length()).map { n -> assets.getJSONObject(n).let { ReleaseAsset(it.optString("name"), it.optString("browser_download_url"), it.optLong("size"), it.optString("digest"), it.optString("state")) } })
                    }
                    if (rows.length() < 100) break
                    check(page < 10) { "发行列表过长，无法完整判断版本" }
                }
                val update = StableReleasePolicy.select(releases, installed, client)
                complete(UpdateState(update = update, message = if (update == null) "当前已是最新版本" else "发现稳定版 ${update.version}"))
            } catch (e: Exception) { mutable.value = UpdateState(message = if (cancelled) "已取消检查" else failureMessage(e, "检查失败，请重试")) }
            finally { call = null }
        }
        }
    }
    private fun execute(request: Request): okhttp3.Response {
        check(!cancelled) { "已取消" }; val own = http.newCall(request); call = own
        if (cancelled) own.cancel()
        return own.execute()
    }
    private fun file(update: ClientUpdate) = File(directory, update.asset.name)
    @Synchronized private fun complete(value: UpdateState) {
        check(!cancelled && !closed) { "已取消" }
        if (value.update != null) protectedFiles[this] = file(value.update) else protectedFiles.remove(this)
        mutable.value = value
    }
    private fun failureMessage(error: Exception, fallback: String): String {
        android.util.Log.w("ClientUpdater", fallback, error)
        return when (error) {
            is java.net.UnknownHostException -> "无法连接 GitHub，请检查网络后重试"
            is java.net.SocketTimeoutException -> "连接超时，请重试"
            is javax.net.ssl.SSLException -> "安全连接失败，请检查系统时间和网络"
            else -> error.message?.takeIf { message -> message.any { it in '\u4e00'..'\u9fff' } } ?: fallback
        }
    }
    private fun verify(file: File, update: ClientUpdate) {
        check(file.length() == update.asset.size) { "安装包大小不匹配" }
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(65536); while (true) { check(!cancelled) { "已取消" }; val count = input.read(bytes); if (count < 0) break; hash.update(bytes, 0, count) } }
        check(hash.digest().joinToString("") { "%02x".format(it) }.equals(update.asset.digest.removePrefix("sha256:"), true)) { "安装包摘要校验失败" }
        verifyIdentity(file, update)
    }
    @Suppress("DEPRECATION") private fun verifyIdentity(file: File, update: ClientUpdate) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("无法读取安装包")
        val own = pm.getPackageInfo(context.packageName, flags)
        check(archive.packageName == own.packageName && archive.versionName == update.version) { "安装包不属于当前客户端或版本不符" }
        val newCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val oldCode = if (Build.VERSION.SDK_INT >= 28) own.longVersionCode else own.versionCode.toLong()
        check(newCode > oldCode) { "安装包版本未更新" }
        val installedSigners = if (Build.VERSION.SDK_INT >= 28) own.signingInfo?.apkContentsSigners else own.signatures
        val nextSigners = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory } else archive.signatures
        val multiple = Build.VERSION.SDK_INT >= 28 && (own.signingInfo?.hasMultipleSigners() == true || archive.signingInfo?.hasMultipleSigners() == true)
        check(!installedSigners.isNullOrEmpty() && !nextSigners.isNullOrEmpty() && installedSigners.all { signer -> nextSigners.any { it == signer } } && (!multiple && Build.VERSION.SDK_INT >= 28 || installedSigners.size == nextSigners.size)) { "安装包签名与本机不一致" }
    }
    @Synchronized fun download() {
        val update = mutable.value.update ?: return
        if (closed || mutable.value.busy) return
        cancelled = false; mutable.value = mutable.value.copy(busy = true, downloading = true, bytes = 0, ready = false, message = "正在下载并校验…")
        job = scope.launch { operations.withLock {
            val target = file(update); val partial = File(directory, target.name + ".part")
            val hadTarget = target.exists()
            try {
                check(directory.isDirectory && directory.canonicalFile == directory.absoluteFile) { "安装包暂存目录异常，请检查本机存储" }
                directory.listFiles()?.filter { TemporaryCachePolicy.knownUpdate(it.name) && it.name.endsWith(".part") }?.forEach { it.delete() }
                // Bound the download cache without deleting files held by another updater/installer.
                val old = directory.listFiles().orEmpty().filter { it.isFile && it.canonicalFile == it.absoluteFile && TemporaryCachePolicy.expiredUpdate(it.name, it.lastModified(), System.currentTimeMillis(), isProtected(it)) }
                old.forEach { it.delete() }
                val retainedBytes = directory.listFiles().orEmpty().sumOf { it.length() } - (if (target.exists()) target.length() else 0)
                check(update.asset.size <= 256L * 1024 * 1024 && retainedBytes + update.asset.size <= 256L * 1024 * 1024) { "安装包暂存已达 256 MB 上限，请清理过期缓存后重试" }
                if (target.exists()) { runCatching { verify(target, update) }.getOrElse { check(target.delete()) { "无法移除损坏的暂存安装包，请重试" } } }
                if (!target.exists()) {
                    execute(Request.Builder().url(update.asset.url).build()).use { response ->
                        check(response.isSuccessful) { "下载失败（HTTP ${response.code}）" }
                        response.body?.byteStream()?.use { input -> partial.outputStream().use { output ->
                            val buffer = ByteArray(65536); var total = 0L
                            while (true) { check(!cancelled) { "已取消" }; val count = input.read(buffer); if (count < 0) break; total += count; check(total <= update.asset.size) { "安装包超过声明大小" }; output.write(buffer, 0, count); mutable.value = mutable.value.copy(bytes = total) }
                            output.fd.sync()
                        } } ?: error("下载内容为空")
                    }
                    verify(partial, update); check(partial.renameTo(target)) { "无法保存暂存安装包" }
                }
                complete(mutable.value.copy(busy = false, downloading = false, ready = true, message = "校验通过，可交给系统安装器"))
            } catch (e: Exception) { partial.delete(); if (!hadTarget) target.delete(); mutable.value = mutable.value.copy(busy = false, downloading = false, ready = false, message = if (cancelled) "下载已取消" else failureMessage(e, "下载失败，请检查网络和存储空间后重试")) }
            finally { call = null }
        }
        }
    }
    @Synchronized fun cancel() { if (mutable.value.busy) { cancelled = true; call?.cancel() } }
    @Synchronized fun install() {
        val update = mutable.value.update ?: return
        if (closed || !mutable.value.ready || mutable.value.busy) return
        cancelled = false; mutable.value = mutable.value.copy(busy = true, message = "正在准备安装…")
        job = scope.launch { operations.withLock {
            var verified = false
            try {
                verify(file(update), update); verified = true
                file(update).setLastModified(System.currentTimeMillis()) // Keep the installer URI available for at least seven days.
                withContext(Dispatchers.Main) {
                    synchronized(this@ClientUpdater) {
                        check(!cancelled) { "已取消" }
                        val owner = activity?.get() as? LifecycleOwner
                        check(owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) { "请返回应用后再点安装" }
                        val permission = Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()
                        val intent = if (permission) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                            else Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(context, context.packageName + ".updates", file(update)), "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        complete(mutable.value.copy(busy = false, message = if (permission) "请在系统设置允许安装后返回，再点安装" else "已请求打开系统安装器；请以系统界面为准"))
                    }
                }
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(busy = false, ready = verified, message = if (cancelled) "安装准备已取消" else failureMessage(e, "安装器未响应，可重试"))
            }
        } }
    }
    @Synchronized fun close() {
        if (closed) return
        closed = true; cancel(); job?.cancel(); scope.cancel(); protectedFiles.remove(this)
        // A pooled TLS socket may write close_notify. Disposal runs on the UI thread,
        // so release network resources on IO even after cancelling the operation scope.
        scope.launch(NonCancellable + Dispatchers.IO) {
            try { http.connectionPool.evictAll() }
            catch (e: Exception) { android.util.Log.w("ClientUpdater", "Network cleanup failed", e) }
            finally { http.dispatcher.executorService.shutdown() }
        }
    }
}
