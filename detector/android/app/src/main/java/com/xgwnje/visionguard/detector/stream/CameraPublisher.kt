package com.xgwnje.visionguard.detector.stream

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.xgwnje.visionguard.account.AccountSession
import com.xgwnje.visionguard.account.AccountStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class StreamTarget(val deviceId: String, val deviceName: String)
data class CameraStream(val streamId: String, val targetDeviceId: String? = null, val sourceId: String? = null, val sourceName: String = "", val isStreaming: Boolean = false)
data class PublisherState(val connected: Boolean = false, val status: String = "正在连接", val stream: CameraStream? = null,
    val targets: List<StreamTarget> = emptyList(), val targetsLoading: Boolean = true, val targetsLoadFailed: Boolean = false,
    val sentFrames: Long = 0, val acknowledgedFrames: Long = 0, val droppedFrames: Long = 0,
    val relayDroppedFrames: Long = 0, val sampledOutFrames: Long = 0)

class CameraPublisher(private val account: AccountStore) {
    private val mutableState = MutableStateFlow(PublisherState())
    val state = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    private val credit = FrameCredit()
    private var control: WebSocket? = null
    private var media: WebSocket? = null
    private var controlGeneration = 0
    @Volatile private var mediaGeneration = 0
    private val mediaHealth = MediaLiveness()
    private var lastMediaHeartbeat = 0L
    private var mediaAuthenticated = false
    private var authenticated = false
    private var lastControlResponse = 0L
    private var controlOpenedAt = 0L
    private var controlCredential = ""
    private var nextRetryAt = 0L
    @Volatile private var wanted = false
    private var closed = false
    @Volatile private var mediaSession = ""
    @Volatile private var readyStream = ""
    @Volatile private var ready = false
    private var previousSendAt = 0L
    init {
        scope.launch {
            account.session.collect { value ->
                if (value == null) { stop("user"); close() }
                else if (controlCredential != value.endpoint + "|" + value.token) { connectControl(value); if (wanted) connectMedia(value) }
            }
        }
        scope.launch {
            while (isActive && !closed) {
                account.ensureSession()
                val current = account.session.value
                val now = SystemClock.elapsedRealtime()
                if (current != null && control == null && now >= nextRetryAt) connectControl(current)
                if (control != null && ((!authenticated && now - controlOpenedAt > 12_000) || authenticated && now - lastControlResponse > 45_000)) dropControl()
                if (authenticated) {
                    control?.send(JSONObject().put("type", "heartbeat").put("deviceId", current?.deviceId)
                        .put("deviceName", current?.deviceName).put("isMonitoring", false).put("isReady", true)
                        .put("cooldown", 5).put("confidence", 0.45).put("targets", "").put("targetSamplingRate", 5)
                        .put("modelKey", "").put("modelOptions", JSONArray()).put("capabilities", JSONArray(listOf("video-publish")))
                        .put("canSwitchModelWhileMonitoring", false).put("hasPendingConfigChanges", false)
                        .put("components", JSONObject().put("cameraApp", "running")).put("sources", JSONArray()).toString())
                    if (wanted && media == null && current != null && now >= nextRetryAt) connectMedia(current)
                }
                if (credit.stalled(now)) { dropMedia(); nextRetryAt = now + 1000; update { it.copy(status = "画面发送超时，正在重连") } }
                if (wanted && media != null) {
                    if (mediaHealth.expired(now)) { dropMedia(); nextRetryAt = now + 1000; update { it.copy(status = "媒体无响应，正在重连") } }
                    else if (mediaAuthenticated && now - lastMediaHeartbeat >= 3000) {
                        lastMediaHeartbeat = now
                        if (media?.send(JSONObject().put("type", "media-heartbeat").toString()) != true) dropMedia()
                    }
                }
                delay(1000)
            }
        }
    }
    private fun connectControl(value: AccountSession) {
        if (closed) return
        controlCredential = value.endpoint + "|" + value.token
        dropMedia()
        val own = ++controlGeneration
        control?.cancel(); authenticated = false
        controlOpenedAt = SystemClock.elapsedRealtime()
        control = http.newWebSocket(Request.Builder().url(value.webSocketUrl).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) { handler.post {
                if (own != controlGeneration || closed) ws.cancel()
                else ws.send(JSONObject().put("type", "auth").put("token", value.token).toString())
            } }
            override fun onMessage(ws: WebSocket, text: String) { handler.post {
                if (own != controlGeneration || closed || text.length > 1_000_000) return@post
                runCatching {
                    val body = JSONObject(text)
                    when (body.optString("type")) {
                        "auth-result" -> {
                            if (!body.optBoolean("success")) { account.clear(); return@runCatching }
                            authenticated = true
                            update { it.copy(connected = true, status = if (wanted) "等待画面通道" else "已连接") }
                            refreshTargets()
                        }
                        "stream-list" -> updateStreams(body.optJSONArray("streams") ?: JSONArray())
                        "device-updated" -> body.optJSONObject("device")?.let { device -> scope.launch { account.updateDevice(device) } }
                        "kicked" -> account.clear()
                    }
                    lastControlResponse = SystemClock.elapsedRealtime()
                }
            } }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { handler.post { if (own == controlGeneration && !closed) dropControl() } }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { handler.post { if (own == controlGeneration && !closed) dropControl() } }
        })
    }
    fun refreshTargets() { scope.launch {
        update { it.copy(targetsLoading = true, targetsLoadFailed = false) }
        runCatching {
            val body = account.request("/api/devices")
            val devices = body.optJSONArray("devices") ?: JSONArray()
            val targets = buildList {
                for (i in 0 until devices.length()) {
                    val item = devices.getJSONObject(i)
                    if (item.optString("component") == "windows-inference" || item.optString("platform") == "windows" && item.optString("nodeType") == "visual")
                        add(StreamTarget(item.getString("deviceId"), item.getString("deviceName")))
                }
            }
            updateStreams(account.request("/api/streams").optJSONArray("streams") ?: JSONArray())
            update { it.copy(targets = targets, targetsLoading = false) }
        }.onFailure { update { it.copy(targetsLoading = false, targetsLoadFailed = true) } }
    } }
    fun bind(targetId: String) { scope.launch {
        runCatching {
            val value = account.session.value ?: error("请先登录")
            account.request("/api/streams/bind", "POST", JSONObject().put("publisherDeviceId", value.deviceId).put("targetDeviceId", targetId))
            refreshTargets()
            if (wanted && media == null) connectMedia(value)
        }.onFailure { failure ->
            update { state -> state.copy(status = "选择视觉节点失败：${failure.message?.takeIf { it.isNotBlank() } ?: "请稍后重试"}") }
        }
    } }
    private fun updateStreams(streams: JSONArray) {
        val own = account.session.value?.deviceId ?: return
        for (i in 0 until streams.length()) {
            val item = streams.getJSONObject(i)
            if (item.optString("publisherDeviceId") != own) continue
            val stream = CameraStream(item.getString("streamId"), item.optString("targetDeviceId").takeIf { it.isNotBlank() },
                item.optString("sourceId").takeIf { it.isNotBlank() }, item.optString("sourceName"), item.optBoolean("isStreaming"))
            update { it.copy(stream = stream) }
        }
    }
    fun start() { wanted = true; account.session.value?.let(::connectMedia) }
    fun stop(reason: String) {
        synchronized(credit) {
            wanted = false; ready = false; credit.reset("")
            media?.send(JSONObject().put("type", "stream-stop").put("reason", reason).toString())
            media?.close(1000, reason)
            media = null; ++mediaGeneration
        }
        update { it.copy(status = if (reason == "background" || reason == "locked") "离开前台，推流已停止" else "推流已停止") }
    }
    private fun connectMedia(value: AccountSession) {
        if (!wanted || closed || !authenticated || media != null) return
        val own = ++mediaGeneration
        synchronized(credit) { media?.cancel(); ready = false; credit.reset("") }
        mediaHealth.opened(SystemClock.elapsedRealtime()); mediaAuthenticated = false; lastMediaHeartbeat = 0
        media = http.newWebSocket(Request.Builder().url(value.mediaUrl).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) { handler.post {
                if (own != mediaGeneration || closed || !wanted) ws.cancel()
                else if (!ws.send(JSONObject().put("type", "media-auth").put("token", value.token).put("direction", "publish").toString())) dropMedia()
            } }
            override fun onMessage(ws: WebSocket, text: String) { val receivedAt = SystemClock.elapsedRealtime(); handler.post {
                if (own != mediaGeneration || closed || !wanted || text.length > 16_384) return@post
                runCatching {
                    val body = JSONObject(text)
                    when (body.optString("type")) {
                        "media-ready" -> {
                            synchronized(credit) {
                                mediaSession = body.getString("sessionId"); readyStream = body.getString("streamId")
                                credit.reset(mediaSession); ready = true
                            }
                            mediaAuthenticated = true; mediaHealth.responded(SystemClock.elapsedRealtime())
                            update { it.copy(status = "正在推流") }
                        }
                        "frame-ack" -> {
                            val age = credit.acknowledgementAge(body.optString("sessionId"), body.optLong("sequence"), SystemClock.elapsedRealtime())
                            if (body.optString("streamId") == readyStream && credit.acknowledge(body.optString("sessionId"), body.optLong("sequence"))) {
                                MediaDiagnostics.log { "event=publisherAck sequence=${body.optLong("sequence")} roundTripMs=$age mainDispatchMs=${SystemClock.elapsedRealtime()-receivedAt} socketQueueBytes=${ws.queueSize()}" }
                                mediaHealth.responded(SystemClock.elapsedRealtime())
                                update { it.copy(acknowledgedFrames = it.acknowledgedFrames + 1,
                                    relayDroppedFrames = body.optJSONObject("stats")?.optLong("dropped") ?: it.relayDroppedFrames) }
                            }
                        }
                        "media-heartbeat-ack" -> if (mediaAuthenticated) mediaHealth.responded(SystemClock.elapsedRealtime())
                        "media-error" -> { dropMedia(); update { it.copy(status = "媒体请求失败，正在重连") } }
                    }
                }
            } }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { handler.post {
                if (own == mediaGeneration && !closed) { dropMedia(); nextRetryAt = SystemClock.elapsedRealtime() + 1000; update { it.copy(status = "视频连接中断，正在重连") } }
            } }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { handler.post { if (own == mediaGeneration && !closed) dropMedia() } }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { handler.post { if (own == mediaGeneration && !closed) { ws.close(code, reason); dropMedia() } } }
        })
    }
    fun canPublish(): Boolean = synchronized(credit) { wanted && ready && credit.available() }
    fun dropped() { update { it.copy(droppedFrames = it.droppedFrames + 1) } }
    fun sampledOut() { update { it.copy(sampledOutFrames = it.sampledOutFrames + 1) } }
    fun publish(frame: CameraFrame): Boolean = synchronized(credit) {
        if (!canPublish() || SystemClock.elapsedRealtime() - frame.capturedAt !in 0..2500) { dropped(); return@synchronized false }
        val sequence = credit.take(SystemClock.elapsedRealtime()) ?: return@synchronized false
        val header = JSONObject().put("streamId", readyStream).put("sessionId", mediaSession).put("sequence", sequence)
            .put("capturedAt", frame.capturedAt).put("width", frame.width).put("height", frame.height).put("rotation", frame.rotation)
        val packet = MediaPacket.encode(header.toString().toByteArray(Charsets.UTF_8), frame.jpeg)
        val accepted = runCatching { media?.send(packet.toByteString()) == true }.getOrDefault(false)
        val sentAt = SystemClock.elapsedRealtime()
        MediaDiagnostics.log { "event=send sequence=$sequence jpegBytes=${frame.jpeg.size} packetBytes=${packet.size} encodeAgeMs=${sentAt-frame.capturedAt} gapMs=${if(previousSendAt==0L) 0 else sentAt-previousSendAt} socketQueueBytes=${media?.queueSize() ?: 0}" }
        previousSendAt = sentAt
        if (accepted) update { it.copy(sentFrames = it.sentFrames + 1) } else { dropped(); dropMedia() }
        accepted
    }
    private fun dropControl() {
        ++controlGeneration; control?.cancel(); control = null; authenticated = false
        dropMedia(); nextRetryAt = SystemClock.elapsedRealtime() + 1000
        update { it.copy(connected = false, status = "连接中断，正在重连") }
    }
    private fun dropMedia() = synchronized(credit) { ++mediaGeneration; ready = false; mediaAuthenticated = false; credit.reset(""); media?.cancel(); media = null }
    private inline fun update(transform: (PublisherState) -> PublisherState) { synchronized(mutableState) { mutableState.value = transform(mutableState.value) } }
    fun close() {
        if (closed) return
        closed = true; wanted = false; ++controlGeneration; dropMedia(); control?.cancel(); control = null
        scope.cancel(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
    }
}
