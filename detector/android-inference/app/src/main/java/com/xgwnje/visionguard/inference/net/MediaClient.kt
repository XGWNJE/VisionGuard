package com.xgwnje.visionguard.inference.net

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.xgwnje.visionguard.account.AccountSession
import com.xgwnje.visionguard.account.AccountStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/** 媒体订阅通道状态。 */
data class MediaState(
    val connected: Boolean = false,
    val authenticated: Boolean = false,
    val statusText: String = "媒体未连接",
    val streams: List<RemoteStream> = emptyList(),
    val receivedFrames: Long = 0,
    val rejectedFrames: Long = 0
)

/**
 * 远程镜头订阅端（`/media/ws`，`direction=subscribe`）。
 *
 * 与 Windows `Services/RemoteMediaService.cs` 一致：只在存在远程来源时才连接；认证后由服务端下发
 * `media-ready` 与 `stream-list`，客户端用 `targetDeviceId == 本机` 把 `streamId` 映射到来源身份；
 * 收到帧后按累计序号回 `frame-received`；12 秒无响应、媒体心跳失败或断线即重连，退避 2 秒。
 */
class MediaClient(
    private val account: AccountStore,
    private val store: RemoteFrameStore,
    private val onStreamsChanged: (List<RemoteStream>) -> Unit,
    private val onFrameReceived: (sourceId: String) -> Unit
) {
    private val gson = Gson()
    private val mutableState = MutableStateFlow(MediaState())
    val state = mutableState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var generation = 0
    private var credential = ""
    @Volatile private var wanted = false
    @Volatile private var closed = false
    private var lastResponseAt = 0L
    private var lastHeartbeatAt = 0L
    private var nextRetryAt = 0L
    private var streamToSource = mapOf<String, String>()

    init {
        scope.launch {
            account.session.collect { value ->
                if (value == null) close()
                else if (credential != value.endpoint + "|" + value.token) {
                    credential = value.endpoint + "|" + value.token
                    drop()
                    if (wanted) connect(value)
                }
            }
        }
        scope.launch {
            while (isActive && !closed) {
                val current = account.session.value
                val now = SystemClock.elapsedRealtime()
                if (wanted && current != null && socket == null && now >= nextRetryAt) connect(current)
                if (socket != null) {
                    if (now - lastResponseAt > STALL_TIMEOUT_MS) {
                        drop()
                        update { it.copy(statusText = "媒体无响应，正在重连") }
                    } else if (now - lastHeartbeatAt >= HEARTBEAT_INTERVAL_MS) {
                        lastHeartbeatAt = now
                        if (socket?.send(JsonObject().apply { addProperty("type", "media-heartbeat") }.let(gson::toJson)) != true) drop()
                    }
                }
                delay(500)
            }
        }
    }

    /** 有远程来源时开始订阅；没有时停止连接。 */
    fun setWanted(value: Boolean) {
        if (closed) return
        wanted = value
        val current = account.session.value
        if (!value) {
            drop()
            update { it.copy(connected = false, authenticated = false, statusText = "媒体未连接", streams = emptyList()) }
        } else if (socket == null && current != null && SystemClock.elapsedRealtime() >= nextRetryAt) {
            connect(current)
        }
    }

    private fun connect(value: AccountSession) {
        if (closed || !wanted) return
        val own = ++generation
        socket?.cancel()
        lastResponseAt = SystemClock.elapsedRealtime()
        lastHeartbeatAt = 0L
        socket = http.newWebSocket(Request.Builder().url(value.mediaUrl).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                handler.post {
                    if (own != generation || closed || !wanted) ws.cancel()
                    else {
                        val request = JsonObject().apply {
                            addProperty("type", "media-auth")
                            addProperty("token", value.token)
                            addProperty("direction", "subscribe")
                        }
                        if (!ws.send(gson.toJson(request))) drop()
                    }
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val receivedAt = SystemClock.elapsedRealtime()
                handler.post {
                    if (own != generation || closed || !wanted || text.length > 64_000) return@post
                    lastResponseAt = receivedAt
                    runCatching { handleText(value, text) }
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                val receivedAt = SystemClock.elapsedRealtime()
                handler.post {
                    if (own != generation || closed || !wanted) return@post
                    lastResponseAt = receivedAt
                    handleFrame(ws, bytes.toByteArray())
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                handler.post { if (own == generation && !closed) { drop(); nextRetryAt = SystemClock.elapsedRealtime() + RETRY_DELAY_MS } }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                handler.post { if (own == generation && !closed) drop() }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                handler.post { if (own == generation && !closed) { ws.close(code, reason); drop() } }
            }
        })
    }

    private fun handleText(value: AccountSession, text: String) {
        val body = gson.fromJson(text, JsonObject::class.java) ?: return
        when (body.string("type")) {
            "media-ready" -> {
                update { it.copy(connected = true, authenticated = true, statusText = "已连接媒体通道") }
            }

            "stream-list" -> {
                val own = value.deviceId
                val streams = body.arr("streams")?.mapNotNull { element ->
                    val item = element.asJsonObject
                    if (item.string("targetDeviceId") != own) return@mapNotNull null
                    val streamId = item.string("streamId") ?: return@mapNotNull null
                    val sourceId = item.string("sourceId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    RemoteStream(
                        streamId = streamId,
                        publisherDeviceId = item.string("publisherDeviceId") ?: "",
                        publisherName = item.string("publisherName") ?: "",
                        targetDeviceId = own,
                        sourceId = sourceId,
                        sourceName = item.string("sourceName") ?: "",
                        isStreaming = item.boolean("isStreaming"),
                        stopReason = item.string("stopReason")
                    )
                } ?: emptyList()

                streamToSource = streams.associate { it.streamId to it.sourceId }
                val live = streams.map { it.streamId }.toSet()
                store.retainStreams(live)
                update { it.copy(streams = streams) }
                // 只有服务端真实下发流列表时才回调：断线或停止订阅不代表来源被移除。
                onStreamsChanged(streams)
            }

            "media-rate" -> update { it.copy(statusText = "已连接媒体通道") }

            "media-error" -> {
                drop()
                update { it.copy(statusText = "媒体请求失败，正在重连") }
            }
        }
    }

    private fun handleFrame(ws: WebSocket, packet: ByteArray) {
        val frame = RemoteFrameCodec.decode(packet)
        if (frame == null) {
            update { it.copy(rejectedFrames = it.rejectedFrames + 1) }
            return
        }
        val sourceId = streamToSource[frame.streamId] ?: return
        val accepted = store.offer(sourceId, frame)
        if (!accepted) {
            update { it.copy(rejectedFrames = it.rejectedFrames + 1) }
            return
        }
        val receipt = JsonObject().apply {
            addProperty("type", "frame-received")
            addProperty("streamId", frame.streamId)
            addProperty("sessionId", frame.sessionId)
            addProperty("sequence", frame.sequence)
        }
        ws.send(gson.toJson(receipt))
        update { it.copy(receivedFrames = it.receivedFrames + 1) }
        onFrameReceived(sourceId)
    }

    private fun drop() {
        ++generation
        socket?.cancel()
        socket = null
        streamToSource = emptyMap()
        store.clear()
        update { it.copy(connected = false, authenticated = false, statusText = "媒体连接中断，正在重连", streams = emptyList()) }
    }

    fun close() {
        if (closed) return
        closed = true
        wanted = false
        ++generation
        socket?.cancel()
        socket = null
        scope.cancel()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
        update { it.copy(connected = false, authenticated = false, statusText = "媒体未连接") }
    }

    private inline fun update(transform: (MediaState) -> MediaState) {
        synchronized(mutableState) { mutableState.value = transform(mutableState.value) }
    }

    companion object {
        const val HEARTBEAT_INTERVAL_MS = 3_000L
        const val STALL_TIMEOUT_MS = 12_000L
        const val RETRY_DELAY_MS = 2_000L
    }
}
