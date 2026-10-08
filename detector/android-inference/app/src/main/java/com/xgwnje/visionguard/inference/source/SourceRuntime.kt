package com.xgwnje.visionguard.inference.source

import android.graphics.BitmapFactory
import com.xgwnje.visionguard.inference.alert.AlertEvent
import com.xgwnje.visionguard.inference.alert.AlertGate
import com.xgwnje.visionguard.inference.detect.ArgbImage
import com.xgwnje.visionguard.inference.detect.Detection
import com.xgwnje.visionguard.inference.detect.YoloOutputParser
import com.xgwnje.visionguard.inference.model.ModelStore
import com.xgwnje.visionguard.inference.model.OnnxEngine
import com.xgwnje.visionguard.inference.net.RemoteFrameStore
import com.xgwnje.visionguard.inference.time.NtpSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 一路来源的独立推理运行时。
 *
 * 与 Windows `Services/MonitorService.cs` 相同的关键语义：
 * 采集 → 涂黑遮罩 → letterbox 预处理 → ONNX 推理 → 解析 → 报警判定；
 * 采样按目标帧率限速、忙时只取最新帧；只有成功处理的帧推进进度与 FPS 统计；
 * 故障只影响本路，报警冷却按来源独立计算。
 */
class SourceRuntime(
    val config: SourceConfig,
    private val models: ModelStore,
    private val remoteFrames: RemoteFrameStore,
    private val localFrame: () -> ArgbImage?,
    private val clockMs: () -> Long,
    private val onAlert: (AlertEvent, ArgbImage) -> Unit
) {
    private val mutableState = MutableStateFlow(SourceRuntimeState(statusText = "未就绪"))
    val state = mutableState.asStateFlow()

    private val gate = AlertGate()
    private val frameTimes = ArrayDeque<Long>()
    private var engine: OnnxEngine? = null
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    /** 远程来源最近一次处理过的帧序号；避免同一帧重复推理。 */
    private var lastRemoteSequence = -1L
    /** 本机相机最近一次处理过的帧对象，用于判断是否是新帧。 */
    private var lastLocalFrame: ArgbImage? = null
    private var lastProcessAtMs = 0L
    private var belowTargetSinceMs = 0L
    private var lastAlertAtMs: Long? = null

    val sourceId: String get() = config.sourceId
    val sourceName: String get() = config.sourceName
    val captureMode: SourceCaptureMode get() = config.mode
    val params get() = config.params
    val masks get() = config.masks

    /** 启动本路推理；返回 null 表示成功，否则返回可读失败原因。 */
    suspend fun start(): String? {
        if (job != null) return null
        val reason = validateForStart()
        if (reason != null) {
            update { it.copy(isRunning = false, isReady = false, error = reason, statusText = "未就绪") }
            return reason
        }

        val modelFile = models.file(config.params.modelKey)
        val created = withContext(Dispatchers.IO) {
            runCatching { OnnxEngine.create(modelFile) }
        }
        val loaded = created.getOrElse { error ->
            val message = "模型加载失败：${error.message ?: error::class.java.simpleName}"
            update { it.copy(isRunning = false, isReady = false, error = message, statusText = "未就绪") }
            return message
        }
        engine = loaded
        gate.reset()
        lastRemoteSequence = -1L
        lastLocalFrame = null
        belowTargetSinceMs = 0L

        val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = ownScope
        job = ownScope.launch { loop() }
        update {
            it.copy(
                isRunning = true,
                isReady = false,
                error = null,
                monitoringExpected = true,
                backend = loaded.backend,
                statusText = "检测中"
            )
        }
        return null
    }

    /** 停止本路推理；主动停止会清除运行意图。 */
    suspend fun stop() {
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        withContext(Dispatchers.IO) { engine?.close() }
        engine = null
        lastLocalFrame = null
        lastRemoteSequence = -1L
        frameTimes.clear()
        update {
            it.copy(
                isRunning = false,
                isReady = false,
                actualFps = 0.0,
                monitoringExpected = false,
                statusText = "已停止",
                performanceWarning = null
            )
        }
    }

    fun close() {
        job?.cancel()
        scope?.cancel()
        runCatching { engine?.close() }
        engine = null
        job = null
        scope = null
    }

    /** 心跳与界面使用的当前快照。 */
    fun heartbeatSource(): com.xgwnje.visionguard.inference.net.HeartbeatSource {
        val snapshot = mutableState.value
        return com.xgwnje.visionguard.inference.net.HeartbeatSource(
            sourceId = config.sourceId,
            sourceName = config.sourceName,
            isMonitoring = snapshot.isRunning,
            isReady = snapshot.isReady,
            modelKey = config.params.modelKey,
            actualFps = snapshot.actualFps,
            error = snapshot.error,
            activeBackend = snapshot.backend,
            cooldown = config.params.cooldownSeconds,
            confidence = config.params.confidence.toDouble(),
            targets = config.params.watchedClassesText(),
            targetSamplingRate = config.params.targetFps,
            monitoringExpected = snapshot.monitoringExpected,
            lastProgressAtIso = snapshot.lastProgressAtMs?.toIsoOrNull(),
            performanceWarning = snapshot.performanceWarning
        )
    }

    private fun validateForStart(): String? {
        if (!SourceConfig.isValidSourceId(config.sourceId)) return "来源身份不合法"
        SourceConfig.validateName(config.sourceName)?.let { return it }
        if (!models.isDownloaded(config.params.modelKey)) {
            return "模型 ${config.params.modelKey} 未下载，请在“全局设置 → 推理与模型”中下载后重试。"
        }
        com.xgwnje.visionguard.inference.detect.DetectionParams.validateConfidence(config.params.confidencePercent)?.let { return it }
        com.xgwnje.visionguard.inference.detect.DetectionParams.validateTargetFps(config.params.targetFps)?.let { return it }
        com.xgwnje.visionguard.inference.detect.DetectionParams.validateCooldown(config.params.cooldownSeconds)?.let { return it }
        return null
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val intervalMs = 1000L / config.params.targetFps.coerceIn(1, 5)
            val now = clockMs()
            if (now - lastProcessAtMs < intervalMs) {
                delay(5)
                continue
            }

            val acquisition = runCatching { acquire() }.getOrElse { error ->
                markFault("采集失败：${error.message ?: error::class.java.simpleName}")
                delay(500)
                return@getOrElse null
            } ?: run {
                // 没有新帧：本机相机可能还没出画、远程镜头可能已停流，状态要如实反映而不是继续显示“检测中”。
                val snapshot = mutableState.value
                val waiting = if (snapshot.processedFrames == 0L) {
                    if (config.mode == SourceCaptureMode.LocalCamera) "等待相机画面" else "等待镜头画面"
                } else {
                    "等待新画面"
                }
                if (snapshot.error != null || snapshot.statusText != waiting) {
                    update { it.copy(error = null, isReady = snapshot.processedFrames > 0, statusText = waiting) }
                }
                delay(20)
                continue
            }

            lastProcessAtMs = clockMs()
            process(acquisition, now)
        }
    }

    /** 取到一帧新画面；没有新画面返回 null。 */
    private fun acquire(): ArgbImage? = when (config.mode) {
        SourceCaptureMode.LocalCamera -> {
            val latest = localFrame() ?: return null
            if (latest === lastLocalFrame) return null
            lastLocalFrame = latest
            latest
        }

        SourceCaptureMode.RemoteStream -> {
            val frame = remoteFrames.readFresh(config.sourceId) ?: return null
            if (frame.sequence == lastRemoteSequence) return null
            val bitmap = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size) ?: return null
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.recycle()
            lastRemoteSequence = frame.sequence
            ArgbImage(width, height, pixels).rotate(frame.rotation)
        }
    }

    private fun process(image: ArgbImage, loopStartedAtMs: Long) {
        val active = engine ?: return
        try {
            val captureMs = clockMs() - loopStartedAtMs

            // 遮罩就地涂黑：推理、报警截图与本机预览共用同一张已涂黑的画面。
            image.applyMasks(config.masks)

            val preprocessStarted = clockMs()
            val preprocessed = image.toLetterboxTensor(active.modelSize)
            val preprocessMs = clockMs() - preprocessStarted

            val inferenceStarted = clockMs()
            val raw = active.run(preprocessed.tensor)
            val inferMs = clockMs() - inferenceStarted

            val parseStarted = clockMs()
            val detections = YoloOutputParser.parse(
                rawOutput = raw,
                transform = preprocessed.transform,
                confThreshold = config.params.confidence,
                watchedClasses = config.params.watchedClasses
            )
            val parseMs = clockMs() - parseStarted

            val finishedAt = clockMs()
            recordSuccess(finishedAt, detections, image, inferMs, active.backend)

            if (gate.shouldAlert(detections, NtpSync.utcNowMs(), config.params.cooldownSeconds)) {
                lastAlertAtMs = finishedAt
                update { it.copy(lastAlertAtMs = finishedAt) }
                val event = AlertEvent(
                    alertId = UUID.randomUUID().toString(),
                    sourceId = config.sourceId,
                    sourceName = config.sourceName,
                    detections = detections,
                    timingsMs = linkedMapOf(
                        "captureMs" to captureMs,
                        "preprocessMs" to preprocessMs,
                        "inferMs" to inferMs,
                        "parseMs" to parseMs,
                        "processMs" to (finishedAt - loopStartedAtMs)
                    ),
                    timestampMs = NtpSync.utcNowMs()
                )
                onAlert(event, image)
            }
        } catch (error: Exception) {
            markFault("处理失败：${error.message ?: error::class.java.simpleName}")
        }
    }

    private fun recordSuccess(nowMs: Long, detections: List<Detection>, image: ArgbImage, inferMs: Long, backend: String) {
        frameTimes.addLast(nowMs)
        while (frameTimes.isNotEmpty() && nowMs - frameTimes.first() > FPS_WINDOW_MS) frameTimes.removeFirst()
        val fps = if (frameTimes.size >= 2) {
            val span = frameTimes.last() - frameTimes.first()
            if (span <= 0L) 0.0 else (frameTimes.size - 1) * 1000.0 / span
        } else 0.0
        val fpsRounded = Math.round(fps * 100.0) / 100.0

        val target = config.params.targetFps.toDouble()
        val warning = if (fpsRounded > 0 && fpsRounded < target * PERFORMANCE_RATIO - COMPARISON_TOLERANCE) {
            if (belowTargetSinceMs == 0L) belowTargetSinceMs = nowMs
            if (nowMs - belowTargetSinceMs >= PERFORMANCE_SUSTAINED_MS) {
                "实际帧率 $fpsRounded 低于目标 $target 的 80%，已持续 ${PERFORMANCE_SUSTAINED_MS / 1000} 秒"
            } else null
        } else {
            belowTargetSinceMs = 0L
            null
        }

        update {
            it.copy(
                isRunning = true,
                isReady = true,
                error = null,
                actualFps = fpsRounded,
                backend = backend,
                inferenceMs = inferMs,
                lastProgressAtMs = NtpSync.utcNowMs(),
                performanceWarning = warning,
                processedFrames = it.processedFrames + 1,
                preview = image,
                detections = detections,
                statusText = "检测中"
            )
        }
    }

    private fun markFault(reason: String) {
        update {
            it.copy(
                isReady = false,
                error = reason,
                failedFrames = it.failedFrames + 1,
                statusText = "故障"
            )
        }
    }

    private inline fun update(transform: (SourceRuntimeState) -> SourceRuntimeState) {
        synchronized(mutableState) { mutableState.value = transform(mutableState.value) }
    }

    companion object {
        private const val FPS_WINDOW_MS = 10_000L
        private const val PERFORMANCE_RATIO = 0.8
        private const val COMPARISON_TOLERANCE = 0.0001
        private const val PERFORMANCE_SUSTAINED_MS = 30_000L
    }
}
