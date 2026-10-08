package com.xgwnje.visionguard.inference.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xgwnje.visionguard.account.AccountSession
import com.xgwnje.visionguard.account.AccountStore
import com.xgwnje.visionguard.account.TemporaryCache
import com.xgwnje.visionguard.inference.AppConstants
import com.xgwnje.visionguard.inference.alert.AlertEvent
import com.xgwnje.visionguard.inference.alert.AlertOutbox
import com.xgwnje.visionguard.inference.alert.AlertPayload
import com.xgwnje.visionguard.inference.alert.AlertSnapshotRenderer
import com.xgwnje.visionguard.inference.alert.AlertSnapshotStore
import com.xgwnje.visionguard.inference.capture.LocalCameraSource
import com.xgwnje.visionguard.inference.capture.LocalCameraStatus
import com.xgwnje.visionguard.inference.detect.ArgbImage
import com.xgwnje.visionguard.inference.detect.DetectionParams
import com.xgwnje.visionguard.inference.detect.MaskRegion
import com.xgwnje.visionguard.inference.model.ModelKeys
import com.xgwnje.visionguard.inference.model.ModelStore
import com.xgwnje.visionguard.inference.net.ControlClient
import com.xgwnje.visionguard.inference.net.HeartbeatPayload
import com.xgwnje.visionguard.inference.net.MediaClient
import com.xgwnje.visionguard.inference.net.RemoteFrameStore
import com.xgwnje.visionguard.inference.net.RemoteStream
import com.xgwnje.visionguard.inference.net.arr
import com.xgwnje.visionguard.inference.net.boolean
import com.xgwnje.visionguard.inference.net.int
import com.xgwnje.visionguard.inference.net.string
import com.xgwnje.visionguard.inference.source.SettingsStore
import com.xgwnje.visionguard.inference.source.SourceCaptureMode
import com.xgwnje.visionguard.inference.source.SourceConfig
import com.xgwnje.visionguard.inference.source.SourceRuntime
import com.xgwnje.visionguard.inference.source.SourceView
import com.xgwnje.visionguard.inference.time.NtpSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** 模型资源视图。 */
data class ModelState(val key: String, val displayName: String, val downloaded: Boolean)

/** 下载进度视图。 */
data class ModelDownloadState(val received: Long, val total: Long) {
    val percent: Int get() = if (total <= 0L) 0 else ((received * 100) / total).toInt().coerceIn(0, 100)
}

/** 推理端对外状态，界面只读这一份快照。 */
data class RuntimeUiState(
    val sources: List<SourceView> = emptyList(),
    val selectedSourceId: String? = null,
    val models: List<ModelState> = emptyList(),
    val downloads: Map<String, ModelDownloadState> = emptyMap(),
    val outboxSize: Int = 0,
    val recoveryWarning: String? = null,
    val alertCount: Long = 0,
    val lastAlertAtMs: Long? = null,
    val cameraStatus: LocalCameraStatus = LocalCameraStatus(),
    val runningSourceCount: Int = 0,
    val message: String? = null
)

/**
 * 推理端总协调器：持有来源运行时、控制通道、媒体订阅、模型与报警发件箱。
 *
 * 与 Windows `ViewModels/MultiSourceViewModel.cs` 的职责对应：来源生命周期、逐来源配置、
 * 心跳与远控路由、报警投递；按来源独立运行、故障只影响本路。
 * 作为应用级单例存在，前台服务与界面共享同一实例。
 */
class InferenceRuntime private constructor(private val context: Context) {

    val account: AccountStore = AccountStore.get(context)
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val mutableState = MutableStateFlow(RuntimeUiState())
    val state = mutableState.asStateFlow()

    private val models = ModelStore(File(context.filesDir, "models"), AppConstants.SERVER_URL)
    private val remoteFrames = RemoteFrameStore(SystemClock::elapsedRealtime)
    private val camera = LocalCameraSource()

    private val runtimes = LinkedHashMap<String, SourceRuntime>()
    private val collectors = HashMap<String, Job>()
    private var settings: SettingsStore = SettingsStore.open(context, null)
    private var outbox = AlertOutbox(File(context.filesDir, "alert-outbox.json"), NtpSync::utcNowMs)
    private var snapshots = AlertSnapshotStore(File(context.filesDir, "alert-snapshots"))
    private var scopeKey: String? = null

    private val control: ControlClient = ControlClient(
        account = account,
        heartbeatPayload = ::buildHeartbeat,
        onCommand = ::handleCommand,
        onSetConfig = ::handleSetConfig,
        onFlushAlerts = ::flushAlerts,
        onScreenshotRequested = ::sendScreenshot,
        onAlertAck = ::handleAlertAck,
        onDeviceUpdated = { device ->
            // 共享账号仓库使用 org.json，这里把 Gson 消息转成同一表示后再落盘改名。
            scope.launch { runCatching { account.updateDevice(org.json.JSONObject(device.toString())) } }
        }
    )
    private val media: MediaClient = MediaClient(
        account = account,
        store = remoteFrames,
        onStreamsChanged = ::handleStreamsChanged,
        onFrameReceived = { }
    )

    val controlState = control.state
    val mediaState = media.state

    init {
        scope.launch {
            account.session.collect { session ->
                if (session == null) teardownSession() else onSession(session)
            }
        }
        scope.launch {
            camera.status.collect { status ->
                updateState { it.copy(cameraStatus = status) }
            }
        }
        updateModels()
        scope.launch { runCatching { withContext(Dispatchers.IO) { NtpSync.syncOnce() } } }
    }

    // ── 会话与配置分区 ────────────────────────────────────────────────

    private suspend fun onSession(session: AccountSession) {
        if (scopeKey == session.scope) return
        teardownSession()
        scopeKey = session.scope
        settings = SettingsStore.open(context, session)
        val directory = File(context.filesDir, "alerts/${digest(session.scope)}")
        directory.mkdirs()
        outbox = AlertOutbox(File(directory, "alert-outbox.json"), NtpSync::utcNowMs)
        snapshots = AlertSnapshotStore(File(directory, "snapshots"))
        rebuildRuntimes()
        media.setWanted(settings.sources().any { it.mode == SourceCaptureMode.RemoteStream })
        resumeExpectedSources()
        updateState { it.copy(outboxSize = outbox.size(), recoveryWarning = outbox.recoveryWarning) }
    }

    private suspend fun teardownSession() {
        // 退出登录只停止推理，不抹掉运行意图：同一账号重新登录后仍按原意图恢复来源。
        stopAll(clearIntent = false)
        for (runtime in runtimes.values) runtime.close()
        runtimes.clear()
        collectors.values.forEach { it.cancel() }
        collectors.clear()
        media.setWanted(false)
        scopeKey = null
        updateState {
            it.copy(sources = emptyList(), selectedSourceId = null, runningSourceCount = 0, outboxSize = 0, recoveryWarning = null)
        }
    }

    /** 服务进程被系统重建后，按上次的运行意图恢复来源。 */
    private suspend fun resumeExpectedSources() {
        for (source in settings.sources()) {
            if (settings.isMonitoringExpected(source.sourceId)) startSource(source.sourceId)
        }
    }

    // ── 来源管理 ─────────────────────────────────────────────────────

    private fun rebuildRuntimes() {
        val configured = settings.sources()
        val configuredIds = configured.map { it.sourceId }.toSet()

        val stale = runtimes.keys.filter { !configuredIds.contains(it) }
        for (sourceId in stale) {
            collectors.remove(sourceId)?.cancel()
            runtimes.remove(sourceId)?.close()
        }
        for (source in configured) {
            val existing = runtimes[source.sourceId]
            if (existing != null && existing.config == source) continue
            if (existing != null) {
                collectors.remove(source.sourceId)?.cancel()
                existing.close()
                runtimes.remove(source.sourceId)
            }
            val runtime = SourceRuntime(
                config = source,
                models = models,
                remoteFrames = remoteFrames,
                localFrame = { camera.latestFrame() },
                clockMs = { SystemClock.elapsedRealtime() },
                onAlert = ::onAlert
            )
            runtimes[source.sourceId] = runtime
            collectors[source.sourceId] = scope.launch {
                runtime.state.collect { publishSources() }
            }
        }
        if (settings.sources().none { it.mode == SourceCaptureMode.LocalCamera }) camera.stop()
        publishSources()
    }

    private fun publishSources() {
        val views = runtimes.values.map { runtime ->
            SourceView(
                sourceId = runtime.sourceId,
                sourceName = runtime.sourceName,
                mode = runtime.captureMode,
                params = runtime.params,
                masks = runtime.masks,
                cameraLensFacing = runtime.config.cameraLensFacing,
                remotePublisherName = runtime.config.remotePublisherName,
                state = runtime.state.value
            )
        }
        val running = views.count { it.state.isRunning }
        updateState {
            val selected = it.selectedSourceId?.takeIf { id -> views.any { view -> view.sourceId == id } }
                ?: views.firstOrNull()?.sourceId
            it.copy(sources = views, selectedSourceId = selected, runningSourceCount = running)
        }
    }

    fun selectSource(sourceId: String) = updateState { it.copy(selectedSourceId = sourceId) }

    /** 新增本机相机来源；已存在时返回提示。 */
    fun addLocalCameraSource(): String? {
        if (settings.hasLocalCameraSource()) return "本机相机来源已存在"
        if (!settings.canAddSource(control.state.value.maxSources)) return "已达到服务端来源上限"
        settings.upsertSource(
            SourceConfig.forLocalCamera(
                params = DetectionParams(modelKey = defaultModelKey()),
                masks = emptyList(),
                lensFacing = SourceConfig.LENS_BACK
            )
        )
        rebuildRuntimes()
        return null
    }

    private fun defaultModelKey(): String =
        models.downloadedKeys().firstOrNull() ?: ModelKeys.defaultKey

    /** 添加一路仅用于远程镜头推理的来源槽位（服务端会按绑定把流指过来）。 */
    fun addRemotePlaceholderSource(): String? {
        if (!settings.canAddSource(control.state.value.maxSources)) return "已达到服务端来源上限"
        var index = 1
        while (settings.sources().any { it.sourceId == "signal-$index" }) index++
        val sourceId = "signal-$index"
        settings.upsertSource(
            SourceConfig(
                sourceId = sourceId,
                sourceName = "远程镜头 $index",
                mode = SourceCaptureMode.RemoteStream,
                params = DetectionParams(modelKey = defaultModelKey())
            )
        )
        rebuildRuntimes()
        media.setWanted(true)
        return null
    }

    suspend fun removeSource(sourceId: String): String? {
        val runtime = runtimes[sourceId] ?: return "来源不存在"
        if (runtime.state.value.isRunning) stopSource(sourceId)
        settings.removeSource(sourceId)
        collectors.remove(sourceId)?.cancel()
        runtimes.remove(sourceId)?.close()
        publishSources()
        media.setWanted(settings.sources().any { it.mode == SourceCaptureMode.RemoteStream })
        return null
    }

    fun renameSource(sourceId: String, name: String): String? {
        val error = SourceConfig.validateName(name) ?: run {
            settings.renameSource(sourceId, name)
            rebuildRuntimes()
            null
        }
        return error
    }

    fun updateParams(sourceId: String, params: DetectionParams): String? {
        val runtime = runtimes[sourceId] ?: return "来源不存在"
        if (runtime.state.value.isRunning) return "请先停止该来源再修改配置。"
        settings.updateParams(sourceId, params)
        rebuildRuntimes()
        return null
    }

    fun updateMasks(sourceId: String, masks: List<MaskRegion>): String? {
        val runtime = runtimes[sourceId] ?: return "来源不存在"
        if (runtime.state.value.isRunning) return "请先停止该来源再修改遮罩。"
        settings.updateMasks(sourceId, masks)
        rebuildRuntimes()
        return null
    }

    fun setCameraLensFacing(sourceId: String, lensFacing: Int): String? {
        val runtime = runtimes[sourceId] ?: return "来源不存在"
        if (runtime.state.value.isRunning) return "请先停止该来源再切换镜头。"
        settings.updateCameraLensFacing(sourceId, lensFacing)
        rebuildRuntimes()
        return null
    }

    // ── 启停 ────────────────────────────────────────────────────────

    suspend fun startSource(sourceId: String): String? {
        val runtime = runtimes[sourceId] ?: return "来源不存在"
        if (runtime.captureMode == SourceCaptureMode.LocalCamera) {
            if (!hasCameraPermission()) return "请先授予相机权限"
            camera.start(context, runtime.config.cameraLensFacing)?.let { return it }
        }
        val error = runtime.start()
        if (error == null) settings.setMonitoringExpected(sourceId, true)
        publishSources()
        return error
    }

    suspend fun stopSource(sourceId: String) = stopSource(sourceId, clearIntent = true)

    private suspend fun stopSource(sourceId: String, clearIntent: Boolean) {
        val runtime = runtimes[sourceId] ?: return
        runtime.stop()
        if (clearIntent) settings.setMonitoringExpected(sourceId, false)
        if (settings.sources().none { it.mode == SourceCaptureMode.LocalCamera && runtimes[it.sourceId]?.state?.value?.isRunning == true }) {
            camera.stop()
        }
        publishSources()
    }

    suspend fun toggleSource(sourceId: String): String? =
        if (runtimes[sourceId]?.state?.value?.isRunning == true) {
            stopSource(sourceId)
            null
        } else {
            startSource(sourceId)
        }

    suspend fun startAll(): String? {
        var firstError: String? = null
        for (sourceId in runtimes.keys.toList()) {
            val error = startSource(sourceId)
            if (error != null && firstError == null) firstError = error
        }
        return firstError
    }

    suspend fun stopAll() = stopAll(clearIntent = true)

    private suspend fun stopAll(clearIntent: Boolean) {
        for (sourceId in runtimes.keys.toList()) stopSource(sourceId, clearIntent)
        camera.stop()
    }

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // ── 远程流 ──────────────────────────────────────────────────────

    private fun handleStreamsChanged(streams: List<RemoteStream>) {
        scope.launch {
            val known = streams.map { it.sourceId }.toSet()
            val removed = settings.removeRemoteSourcesNotIn(known)
            var changed = removed.isNotEmpty()
            for (stream in streams) {
                if (settings.sources().any { it.sourceId == stream.sourceId }) continue
                if (!settings.canAddSource(control.state.value.maxSources)) break
                settings.upsertSource(
                    SourceConfig.forRemoteStream(
                        streamSourceId = stream.sourceId,
                        streamSourceName = stream.sourceName.ifBlank { stream.publisherName },
                        publisherDeviceId = stream.publisherDeviceId,
                        publisherName = stream.publisherName,
                        params = DetectionParams(modelKey = defaultModelKey()),
                        masks = emptyList()
                    )
                )
                changed = true
            }
            if (changed) rebuildRuntimes()
        }
    }

    // ── 心跳 / 远控 ─────────────────────────────────────────────────

    private fun buildHeartbeat(): JsonObject {
        val session = account.session.value
        return HeartbeatPayload.build(
            gson = gson,
            deviceId = session?.deviceId ?: "",
            deviceName = session?.deviceName ?: AppConstants.APP_TITLE,
            sources = runtimes.values.map { it.heartbeatSource() },
            modelOptions = models.downloadedKeys(),
            modelLabels = HeartbeatPayload.standardModelLabels(ModelKeys.keys),
            components = mapOf("detectorApp" to if (mutableState.value.runningSourceCount > 0) "running" else "idle")
        )
    }

    private fun handleCommand(
        command: String,
        targetSourceId: String?,
        respond: (Boolean, String, JsonObject?) -> Unit
    ) {
        scope.launch {
            when (command) {
                "pause" -> if (targetSourceId == null) {
                    stopAll()
                    respond(true, "已暂停全部来源", null)
                } else {
                    if (!runtimes.containsKey(targetSourceId)) respond(false, "来源不存在", null)
                    else {
                        stopSource(targetSourceId)
                        respond(true, "已暂停该来源", null)
                    }
                }

                "resume" -> if (targetSourceId == null) {
                    val error = startAll()
                    respond(error == null, error ?: "已恢复全部来源", null)
                } else {
                    if (!runtimes.containsKey(targetSourceId)) respond(false, "来源不存在", null)
                    else {
                        val error = startSource(targetSourceId)
                        respond(error == null, error ?: "已恢复该来源", null)
                    }
                }

                "stop-alarm" -> respond(false, "当前无报警", null)

                "cache-inspect", "cache-clean" -> {
                    val report = runCatching {
                        TemporaryCache.maintain(context, command == "cache-clean") { mutableState.value.runningSourceCount > 0 }
                    }.getOrNull()
                    val json = report?.let { runCatching { JsonParser.parseString(it.toString()).asJsonObject }.getOrNull() }
                    respond(
                        report != null,
                        if (report == null) "缓存操作失败，请重试" else if (command == "cache-clean") "缓存清理完成" else "缓存盘点完成",
                        json
                    )
                }

                else -> respond(false, "设备不支持该命令", null)
            }
        }
    }

    private fun handleSetConfig(
        key: String,
        value: String,
        targetSourceId: String?,
        respond: (Boolean, String) -> Unit
    ) {
        scope.launch {
            val sourceId = targetSourceId ?: runtimes.keys.firstOrNull()
            if (sourceId == null) {
                respond(false, "来源不存在")
                return@launch
            }
            val runtime = runtimes[sourceId]
            if (runtime == null) {
                respond(false, "来源不存在")
                return@launch
            }
            if (runtime.state.value.isRunning) {
                respond(false, "请先停止该来源再修改配置。")
                return@launch
            }
            val current = settings.sources().firstOrNull { it.sourceId == sourceId }
            if (current == null) {
                respond(false, "来源不存在")
                return@launch
            }
            runCatching { applyConfig(current, key, value) }.fold(
                onSuccess = { updated ->
                    settings.upsertSource(updated)
                    rebuildRuntimes()
                    respond(true, "配置已保存，重新启动来源后生效")
                },
                onFailure = { error -> respond(false, error.message ?: "配置不合法") }
            )
        }
    }

    /** 远控改参的取值校验与 Windows 端一致：冷改动、越界拒绝并给出可读原因。 */
    private fun applyConfig(current: SourceConfig, key: String, value: String): SourceConfig {
        val params = current.params
        return when (key) {
            "confidence" -> {
                val percent = parseConfidencePercent(value)
                    ?: throw IllegalArgumentException("置信度需为 0.10–0.95，且换算成整数百分比")
                DetectionParams.validateConfidence(percent)?.let { throw IllegalArgumentException(it) }
                current.copy(params = params.copy(confidencePercent = percent))
            }

            "cooldown" -> {
                val seconds = value.trim().toIntOrNull() ?: throw IllegalArgumentException("报警冷却必须是整数秒")
                DetectionParams.validateCooldown(seconds)?.let { throw IllegalArgumentException(it) }
                current.copy(params = params.copy(cooldownSeconds = seconds))
            }

            "targetSamplingRate" -> {
                val fps = value.trim().toIntOrNull() ?: throw IllegalArgumentException("采样频率必须是整数帧/秒")
                DetectionParams.validateTargetFps(fps)?.let { throw IllegalArgumentException(it) }
                current.copy(params = params.copy(targetFps = fps))
            }

            "targets" -> {
                val watched = DetectionParams.parseWatchedClasses(value)
                    ?: throw IllegalArgumentException("检测目标必须是已知的 COCO 类名，且不超过 4096 字符")
                current.copy(params = params.copy(watchedClasses = watched))
            }

            "modelKey" -> {
                val key2 = value.trim()
                if (!ModelKeys.isSupported(key2)) throw IllegalArgumentException("本端不支持模型 $key2")
                if (!models.isDownloaded(key2)) throw IllegalArgumentException("模型 $key2 尚未下载")
                current.copy(params = params.copy(modelKey = key2))
            }

            else -> throw IllegalArgumentException("不支持的配置项 $key")
        }
    }

    private fun parseConfidencePercent(raw: String): Int? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.contains('.')) {
            val decimal = text.toDoubleOrNull() ?: return null
            if (decimal < 0.10 || decimal > 0.95) return null
            val percent = Math.round(decimal * 100).toInt()
            return if (Math.abs(decimal * 100 - percent) < 1e-6) percent else null
        }
        val percent = text.toIntOrNull() ?: return null
        return if (percent in DetectionParams.MIN_CONFIDENCE_PERCENT..DetectionParams.MAX_CONFIDENCE_PERCENT) percent else null
    }

    // ── 报警 ────────────────────────────────────────────────────────

    private fun onAlert(event: AlertEvent, frame: ArgbImage) {
        scope.launch(Dispatchers.Default) {
            val rendered = AlertSnapshotRenderer.renderAndEncode(frame, event.detections)
            if (rendered != null) snapshots.save(event.alertId, rendered.jpeg)

            val session = account.session.value ?: return@launch
            val payload = AlertPayload.build(gson, event, session.deviceId, session.deviceName, NtpSync.utcNowIso())
            outbox.enqueue(event.alertId, payload, event.expiresAtMs)
            updateState {
                it.copy(
                    alertCount = it.alertCount + 1,
                    lastAlertAtMs = event.timestampMs,
                    outboxSize = outbox.size(),
                    recoveryWarning = outbox.recoveryWarning
                )
            }
            flushAlerts()
            snapshots.maintain(NtpSync.utcNowMs())
        }
    }

    private fun flushAlerts() {
        if (!control.state.value.sourcesRegistered) return
        for (entry in outbox.snapshot()) {
            if (!control.sendAlert(entry.payloadJson)) break
        }
    }

    private fun handleAlertAck(alertId: String, accepted: Boolean, duplicate: Boolean, reason: String) {
        if (accepted) {
            if (outbox.acknowledge(alertId)) sendScreenshot(alertId)
        } else if (reason == "alert-id-conflict" || reason == "invalid-or-expired-event") {
            outbox.acknowledge(alertId)
        }
        updateState { it.copy(outboxSize = outbox.size()) }
    }

    private fun sendScreenshot(alertId: String) {
        val jpeg = snapshots.read(alertId) ?: return
        // 发送宽度/高度取截图文件真实尺寸，避免回执里出现与实际不符的 0×0。
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        control.sendScreenshot(alertId, jpeg, bounds.outWidth.coerceAtLeast(0), bounds.outHeight.coerceAtLeast(0))
    }

    // ── 模型 ────────────────────────────────────────────────────────

    private fun updateModels() {
        updateState {
            it.copy(models = ModelKeys.keys.map { key -> ModelState(key, ModelKeys.displayName(key), models.isDownloaded(key)) })
        }
    }

    suspend fun downloadModel(key: String): String? {
        if (!ModelKeys.isSupported(key)) return "本端不支持模型 $key"
        updateState { it.copy(downloads = it.downloads + (key to ModelDownloadState(0, 0))) }
        val error = models.download(key) { received, total ->
            updateState { it.copy(downloads = it.downloads + (key to ModelDownloadState(received, total))) }
        }
        updateState { it.copy(downloads = it.downloads - key, message = error ?: "模型 ${ModelKeys.displayName(key)} 已下载") }
        updateModels()
        return error
    }

    suspend fun deleteModel(key: String): String? {
        if (runtimes.values.any { it.state.value.isRunning && it.params.modelKey == key }) {
            return "该模型正在被运行中的来源使用，请先停止来源"
        }
        models.delete(key)
        updateModels()
        return null
    }

    fun modelStore(): ModelStore = models

    // ── 生命周期 ────────────────────────────────────────────────────

    fun clearMessage() = updateState { it.copy(message = null) }

    fun setMessage(message: String?) = updateState { it.copy(message = message) }

    /** 应用级关闭（退出登录时也会走会话回收，不必显式调用）。 */
    fun shutdown() {
        for (collector in collectors.values) collector.cancel()
        collectors.clear()
        for (runtime in runtimes.values) runtime.close()
        runtimes.clear()
        camera.stop()
        control.close()
        media.close()
    }

    private inline fun updateState(transform: (RuntimeUiState) -> RuntimeUiState) {
        synchronized(mutableState) { mutableState.value = transform(mutableState.value) }
    }

    companion object {
        @Volatile
        private var instance: InferenceRuntime? = null

        fun get(context: Context): InferenceRuntime =
            instance ?: synchronized(this) {
                instance ?: InferenceRuntime(context.applicationContext).also { instance = it }
            }

        private fun digest(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .take(12).joinToString("") { "%02x".format(it) }
    }
}
