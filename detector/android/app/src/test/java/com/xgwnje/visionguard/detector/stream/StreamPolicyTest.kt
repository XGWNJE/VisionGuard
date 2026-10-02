package com.xgwnje.visionguard.detector.stream

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class StreamPolicyTest {
    @Test fun leavingForegroundRequiresManualRestart() {
        val policy = ForegroundStreamPolicy()
        assertFalse(policy.start())
        policy.resumed(); assertTrue(policy.start())
        policy.leftForeground(); assertFalse(policy.streaming)
        assertFalse(policy.start())
        policy.resumed(); assertFalse(policy.streaming)
        assertTrue(policy.start())
        policy.stop(); assertFalse(policy.streaming)
    }
    @Test fun oneOutstandingFrameCannotBeReleasedByWrongSessionOrSequence() {
        val credit = FrameCredit()
        credit.reset("session-a")
        assertEquals(1L, credit.take(100))
        assertNull(credit.take(200))
        assertFalse(credit.acknowledge("session-b", 1))
        assertFalse(credit.acknowledge("session-a", 2))
        assertFalse(credit.available())
        assertTrue(credit.acknowledge("session-a", 1))
        assertEquals(2L, credit.take(500))
        assertTrue(credit.stalled(3501))
        credit.reset("session-b")
        assertFalse(credit.acknowledge("session-a", 2))
        assertTrue(credit.available())
    }
    @Test fun binaryHeaderUsesBigEndianLengthAndPreservesJpeg() {
        val packet = MediaPacket.encode("{}".toByteArray(), byteArrayOf(-1, -40, -1, -39))
        assertEquals(2, ByteBuffer.wrap(packet).int)
        assertArrayEquals("{}".toByteArray(), packet.copyOfRange(4, 6))
        assertArrayEquals(byteArrayOf(-1, -40, -1, -39), packet.copyOfRange(6, 10))
        assertTrue(runCatching { MediaPacket.encode(ByteArray(4097), byteArrayOf(1)) }.isFailure)
        assertTrue(runCatching { MediaPacket.encode(byteArrayOf(1), ByteArray(MediaPacket.MAX_JPEG_BYTES + 1)) }.isFailure)
    }
    @Test fun largerCameraOutputIsReducedTo720PForBothOrientations() {
        assertEquals(1280 to 720, MediaPacket.boundedSize(1920, 1080, 1280, 720))
        assertEquals(720 to 1280, MediaPacket.boundedSize(1080, 1920, 1280, 720))
        assertEquals(640 to 480, MediaPacket.boundedSize(640, 480, 1280, 720))
        assertEquals(640 to 480, MediaPacket.boundedSize(1920, 1440, 640, 480))
    }
}
