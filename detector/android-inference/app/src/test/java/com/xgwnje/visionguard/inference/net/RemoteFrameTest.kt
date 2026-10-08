package com.xgwnje.visionguard.inference.net

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** 远程帧解析与缓存：越界、错尺寸、序号倒退都必须被拒绝。 */
class RemoteFrameTest {

    private val gson = Gson()

    /** 构造一张只有头部结构的最小 JPEG，供尺寸校验使用。 */
    private fun fakeJpeg(width: Int, height: Int): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        output.write(byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08))
        output.write(byteArrayOf((height shr 8).toByte(), height.toByte(), (width shr 8).toByte(), width.toByte()))
        output.write(ByteArray(9) { 0x00 })
        output.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return output.toByteArray()
    }

    private fun packet(
        streamId: String = "stream-1",
        sessionId: String = "session-1",
        sequence: Long = 1,
        width: Int = 640,
        height: Int = 480,
        rotation: Int = 0,
        jpeg: ByteArray = fakeJpeg(width, height),
        headerOverride: JsonObject? = null
    ): ByteArray {
        val header = headerOverride ?: JsonObject().apply {
            addProperty("streamId", streamId)
            addProperty("sessionId", sessionId)
            addProperty("sequence", sequence)
            addProperty("capturedAt", 1234L)
            addProperty("width", width)
            addProperty("height", height)
            addProperty("rotation", rotation)
        }
        val headerBytes = gson.toJson(header).toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + headerBytes.size + jpeg.size)
        buffer.putInt(headerBytes.size)
        buffer.put(headerBytes)
        buffer.put(jpeg)
        return buffer.array()
    }

    @Test
    fun `合法帧解析出头部字段与图像`() {
        val frame = RemoteFrameCodec.decode(packet())
        assertNotNull(frame)
        assertEquals("stream-1", frame!!.streamId)
        assertEquals("session-1", frame.sessionId)
        assertEquals(1L, frame.sequence)
        assertEquals(640, frame.width)
        assertEquals(480, frame.height)
        assertEquals(0, frame.rotation)
        assertEquals(1234L, frame.publisherCapturedAtMs)
    }

    @Test
    fun `头部声明的宽高必须与图像一致`() {
        assertNull(RemoteFrameCodec.decode(packet(width = 640, height = 480, jpeg = fakeJpeg(320, 240))))
    }

    @Test
    fun `分辨率与方向超出契约时拒绝`() {
        assertNull(RemoteFrameCodec.decode(packet(width = 1920, height = 1080)))
        assertNull(RemoteFrameCodec.decode(packet(rotation = 45)))
    }

    @Test
    fun `头部过长或缺少必需字段时拒绝`() {
        assertNull(RemoteFrameCodec.decode(packet(headerOverride = JsonObject().apply { addProperty("streamId", "s") })))
        val huge = JsonObject().apply {
            addProperty("streamId", "s")
            addProperty("padding", "x".repeat(RemoteFrameCodec.MAX_HEADER_BYTES))
        }
        assertNull(RemoteFrameCodec.decode(packet(headerOverride = huge)))
    }

    @Test
    fun `不是 JPEG 的负载被拒绝`() {
        assertNull(RemoteFrameCodec.decode(packet(jpeg = ByteArray(32) { 0x11 })))
        assertNull(RemoteFrameCodec.decode(ByteArray(3)))
    }

    @Test
    fun `序号必须递增否则丢弃`() {
        var now = 1_000L
        val store = RemoteFrameStore({ now })
        val first = RemoteFrameCodec.decode(packet(sequence = 2, jpeg = fakeJpeg(640, 480)))!!
        assertTrue(store.offer("camera", first))
        val same = RemoteFrameCodec.decode(packet(sequence = 2, jpeg = fakeJpeg(640, 480)))!!
        assertFalse(store.offer("camera", same))
        val older = RemoteFrameCodec.decode(packet(sequence = 1, jpeg = fakeJpeg(640, 480)))!!
        assertFalse(store.offer("camera", older))
        val newer = RemoteFrameCodec.decode(packet(sequence = 3, jpeg = fakeJpeg(640, 480)))!!
        assertTrue(store.offer("camera", newer))
        now += 10
        assertEquals(3L, store.readFresh("camera")!!.sequence)
    }

    @Test
    fun `超过最大帧龄的缓存不再可用`() {
        var now = 1_000L
        val store = RemoteFrameStore({ now })
        val frame = RemoteFrameCodec.decode(packet())!!
        store.offer("camera", frame)
        assertNotNull(store.readFresh("camera"))
        now += RemoteFrameStore.MAX_FRAME_AGE_MS + 1
        assertNull(store.readFresh("camera"))
    }

    @Test
    fun `服务端移除流后缓存与序号一起清理`() {
        val store = RemoteFrameStore({ 1_000L })
        store.offer("camera", RemoteFrameCodec.decode(packet(streamId = "stream-1"))!!)
        store.offer("signal-1", RemoteFrameCodec.decode(packet(streamId = "stream-2"))!!)
        store.retainStreams(setOf("stream-2"))
        assertNull(store.readFresh("camera"))
        assertNotNull(store.readFresh("signal-1"))
    }
}
