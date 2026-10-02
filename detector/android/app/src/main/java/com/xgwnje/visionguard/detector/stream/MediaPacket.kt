package com.xgwnje.visionguard.detector.stream

import java.nio.ByteBuffer
import kotlin.math.min

object MediaPacket {
    const val MAX_JPEG_BYTES = 2 * 1024 * 1024
    const val MAX_HEADER_BYTES = 4096
    fun encode(header: ByteArray, jpeg: ByteArray): ByteArray {
        require(header.isNotEmpty() && header.size <= MAX_HEADER_BYTES)
        require(jpeg.isNotEmpty() && jpeg.size <= MAX_JPEG_BYTES)
        return ByteBuffer.allocate(4 + header.size + jpeg.size).putInt(header.size).put(header).put(jpeg).array()
    }
    fun boundedSize(width: Int, height: Int, longSide: Int, shortSide: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && longSide in 1..1280 && shortSide in 1..720)
        val ratio = min(1.0, min(longSide.toDouble() / maxOf(width, height), shortSide.toDouble() / minOf(width, height)))
        return (width * ratio).toInt().coerceAtLeast(1) to (height * ratio).toInt().coerceAtLeast(1)
    }
}

/** One transferable frame per media session. An acknowledgement cannot release another session's credit. */
class FrameCredit {
    private var session = ""
    private var pending: Long? = null
    private var next = 0L
    private var sentAt = 0L
    @Synchronized fun reset(sessionId: String) { session = sessionId; pending = null; next = 0; sentAt = 0 }
    @Synchronized fun available(): Boolean = session.isNotEmpty() && pending == null
    @Synchronized fun take(now: Long): Long? {
        if (!available()) return null
        next++; pending = next; sentAt = now
        return next
    }
    @Synchronized fun acknowledge(sessionId: String, sequence: Long): Boolean {
        if (sessionId != session || sequence != pending) return false
        pending = null; return true
    }
    @Synchronized fun stalled(now: Long): Boolean = pending != null && now - sentAt > 3000
}
