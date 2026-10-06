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

/** Receipts report cumulative progress; up to eight frames / 2 MiB can travel concurrently. */
class FrameWindow(private val frameLimit: Int = 8, private val byteLimit: Int = MediaPacket.MAX_JPEG_BYTES + 4100) {
    private var session = ""
    private data class Pending(val sentAt: Long, val bytes: Int)
    private val pending = linkedMapOf<Long, Pending>()
    private var next = 0L
    private var acknowledged = 0L
    private var bytes = 0
    @Synchronized fun reset(sessionId: String) {
        if (sessionId.isNotEmpty() && sessionId == session) return
        session = sessionId; pending.clear(); next = 0; acknowledged = 0; bytes = 0
    }
    @Synchronized fun available(frameBytes: Int = 1): Boolean =
        session.isNotEmpty() && pending.size < frameLimit && frameBytes > 0 && frameBytes <= byteLimit - bytes
    @Synchronized fun take(now: Long, frameBytes: Int = 1): Long? {
        if (!available(frameBytes)) return null
        next++; pending[next] = Pending(now, frameBytes); bytes += frameBytes
        return next
    }
    @Synchronized fun acknowledge(sessionId: String, sequence: Long): Boolean {
        if (sessionId != session || sequence <= acknowledged || sequence > next) return false
        val iterator = pending.iterator()
        while (iterator.hasNext()) { val entry = iterator.next(); if (entry.key <= sequence) { bytes -= entry.value.bytes; iterator.remove() } }
        acknowledged = sequence; return true
    }
    @Synchronized fun acknowledgementAge(sessionId: String, sequence: Long, now: Long): Long? =
        if (sessionId == session) pending[sequence]?.let { (now - it.sentAt).coerceAtLeast(0) } else null
    @Synchronized fun stalled(now: Long): Boolean = pending.values.firstOrNull()?.let { now - it.sentAt > 3000 } ?: false
}

/** Keep a fixed cadence without accumulating callback delay or catching up in bursts. */
class FramePacer {
    private var rate = 0
    private var nextAt = 0.0
    fun reset() { rate = 0; nextAt = 0.0 }
    fun due(now: Long, framesPerSecond: Int): Boolean {
        require(framesPerSecond in 1..5)
        if (rate != framesPerSecond) { rate = framesPerSecond; nextAt = now.toDouble() }
        return now >= nextAt
    }
    fun sampled(now: Long) {
        val interval = 1000.0 / rate
        nextAt += interval
        if (nextAt <= now) nextAt = now + interval
    }
}

/** Media needs responses even while no frame is in flight or while waiting to become active. */
class MediaLiveness {
    private var openedAt = 0L
    private var lastResponse = 0L
    private var ready = false
    fun opened(now: Long) { openedAt = now; lastResponse = now; ready = false }
    fun responded(now: Long) { ready = true; lastResponse = now }
    fun expired(now: Long): Boolean = now - (if (ready) lastResponse else openedAt) >= 12_000
}
