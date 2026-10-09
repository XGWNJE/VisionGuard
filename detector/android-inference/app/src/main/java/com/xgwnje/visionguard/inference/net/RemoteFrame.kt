package com.xgwnje.visionguard.inference.net

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.nio.ByteBuffer

/**
 * 远程镜头的一帧：4 字节大端 JSON 头长度 + UTF-8 头 + JPEG。
 *
 * 校验口径与 Windows `Capture/RemoteFrameStore.cs` 一致：头最长 4096 字节、JPEG 最长 2 MiB、
 * 必须是大端长度前缀的合法 JPEG、最长边不超过 1280 且短边不超过 720、方向只能是 0/90/180/270、
 * 头部声明的宽高必须与 JPEG 实际尺寸一致（拒绝对不上号的帧，避免把错位画面当真实画面推理）。
 */
data class RemoteFrame(
    val streamId: String,
    val sessionId: String,
    val sequence: Long,
    val publisherCapturedAtMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val jpeg: ByteArray
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

object RemoteFrameCodec {

    const val MAX_HEADER_BYTES = 4096
    const val MAX_JPEG_BYTES = 2 * 1024 * 1024
    const val MAX_LONG_EDGE = 1280
    const val MAX_SHORT_EDGE = 720

    private val gson = Gson()

    /** 解析失败返回 null；调用方按“丢弃这一帧”处理，不断开整个媒体连接。 */
    fun decode(packet: ByteArray): RemoteFrame? {
        if (packet.size < 5) return null
        val headerLength = ByteBuffer.wrap(packet, 0, 4).int
        if (headerLength <= 0 || headerLength > MAX_HEADER_BYTES) return null
        if (packet.size < 4 + headerLength) return null
        val jpegLength = packet.size - 4 - headerLength
        if (jpegLength <= 0 || jpegLength > MAX_JPEG_BYTES) return null

        val header = runCatching {
            gson.fromJson(String(packet, 4, headerLength, Charsets.UTF_8), JsonObject::class.java)
        }.getOrNull() ?: return null

        val streamId = header.string("streamId")?.takeIf { it.isNotBlank() } ?: return null
        val sessionId = header.string("sessionId")?.takeIf { it.isNotBlank() } ?: return null
        val sequence = header.long("sequence") ?: return null
        if (sequence <= 0) return null
        val width = header.int("width") ?: return null
        val height = header.int("height") ?: return null
        val rotation = header.int("rotation") ?: 0
        if (rotation !in setOf(0, 90, 180, 270)) return null
        if (maxOf(width, height) > MAX_LONG_EDGE || minOf(width, height) > MAX_SHORT_EDGE) return null

        val jpeg = packet.copyOfRange(4 + headerLength, packet.size)
        if (jpeg.size < 2 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return null
        val actual = JpegInfo.dimensions(jpeg) ?: return null
        if (actual.first != width || actual.second != height) return null

        return RemoteFrame(
            streamId = streamId,
            sessionId = sessionId,
            sequence = sequence,
            publisherCapturedAtMs = header.long("capturedAt") ?: 0L,
            width = width,
            height = height,
            rotation = rotation,
            jpeg = jpeg
        )
    }
}

/** 只读 JPEG 头拿到真实像素尺寸；不需要解码整张图。 */
object JpegInfo {

    fun dimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        var index = 2
        while (index + 9 < bytes.size) {
            if (bytes[index] != 0xFF.toByte()) {
                index++
                continue
            }
            val marker = bytes[index + 1].toInt() and 0xFF
            if (marker == 0xFF) {
                index++
                continue
            }
            // 无长度字段的标记：SOI/EOI/RSTn/TEM
            if (marker == 0xD8 || marker == 0xD9 || marker in 0xD0..0xD7 || marker == 0x01) {
                index += 2
                continue
            }
            if (index + 3 >= bytes.size) return null
            val segmentLength = ((bytes[index + 2].toInt() and 0xFF) shl 8) or (bytes[index + 3].toInt() and 0xFF)
            if (segmentLength < 2) return null
            val isStartOfFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isStartOfFrame) {
                if (index + 8 >= bytes.size) return null
                val height = ((bytes[index + 5].toInt() and 0xFF) shl 8) or (bytes[index + 6].toInt() and 0xFF)
                val width = ((bytes[index + 7].toInt() and 0xFF) shl 8) or (bytes[index + 8].toInt() and 0xFF)
                return if (width > 0 && height > 0) width to height else null
            }
            index += 2 + segmentLength
        }
        return null
    }
}

/**
 * 每路来源只保留最新一帧的缓存。
 *
 * 与 Windows `Capture/RemoteFrameStore.cs` 语义一致：序号必须递增（重复或倒退直接丢弃），
 * 写入与读取都按本地单调时钟判定帧龄，超过 [maxAgeMs] 的帧视为不可用而不是拿旧画面凑合。
 */
class RemoteFrameStore(
    private val nowMs: () -> Long,
    private val maxAgeMs: Long = MAX_FRAME_AGE_MS
) {
    private class Entry(val frame: RemoteFrame, val receivedAtMs: Long)

    private val entries = HashMap<String, Entry>()
    private val lastSequences = HashMap<String, Long>()

    /** 写入一帧；序号不递增时返回 false（调用方仍应回执，避免发布端一直等不到确认）。 */
    @Synchronized
    fun offer(sourceId: String, frame: RemoteFrame): Boolean {
        val previous = lastSequences[frame.streamId]
        if (previous != null && frame.sequence <= previous) return false
        lastSequences[frame.streamId] = frame.sequence
        entries[sourceId] = Entry(frame, nowMs())
        return true
    }

    /** 读取仍然新鲜的帧；不存在或已过期返回 null，并顺带丢弃过期项。 */
    @Synchronized
    fun readFresh(sourceId: String): RemoteFrame? {
        val entry = entries[sourceId] ?: return null
        val age = nowMs() - entry.receivedAtMs
        if (age > maxAgeMs) {
            entries.remove(sourceId)
            return null
        }
        return entry.frame
    }

    /** 调用方处理完之后读取的帧龄，用于诊断与超时判定。 */
    @Synchronized
    fun ageMs(sourceId: String): Long? = entries[sourceId]?.let { nowMs() - it.receivedAtMs }

    @Synchronized
    fun clear() {
        entries.clear()
        lastSequences.clear()
    }

    /** 某条流停流或解绑时清理，避免把旧画面当当前画面。 */
    @Synchronized
    fun clearStream(streamId: String) {
        lastSequences.remove(streamId)
        val keys = entries.filterValues { it.frame.streamId == streamId }.keys
        keys.forEach { entries.remove(it) }
    }

    /** 服务端下发的流列表变化时调用：只保留仍然存在的流，其余缓存与序号一起清掉。 */
    @Synchronized
    fun retainStreams(streamIds: Set<String>) {
        val stale = lastSequences.keys.filter { !streamIds.contains(it) }
        for (streamId in stale) clearStream(streamId)
    }

    companion object {
        const val MAX_FRAME_AGE_MS = 3_000L
    }
}
