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
    @Test fun boundedWindowMakesProgressWithoutWaitingForEveryReceipt() {
        val credit = FrameWindow(frameLimit = 3, byteLimit = 100)
        credit.reset("session-a")
        assertEquals(1L, credit.take(100, 40))
        assertEquals(2L, credit.take(200, 40))
        assertNull(credit.take(300, 30))
        assertEquals(3L, credit.take(300, 20))
        assertNull(credit.take(400, 1))
        assertFalse(credit.acknowledge("session-b", 1))
        assertFalse(credit.acknowledge("session-a", 4))
        assertFalse(credit.available())
        assertTrue(credit.acknowledge("session-a", 2))
        assertFalse(credit.acknowledge("session-a", 1))
        assertEquals(4L, credit.take(500, 40))
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
    @Test fun repeatedBindingOfSameSessionPreservesSequenceAndInflightCredit() {
        val credit = FrameWindow(frameLimit = 1); credit.reset("same-session")
        assertEquals(1L, credit.take(100))
        credit.reset("same-session")
        assertFalse(credit.available()); assertTrue(credit.stalled(3200))
        assertTrue(credit.acknowledge("same-session", 1))
        credit.reset("same-session")
        assertEquals(2L, credit.take(3300))
        credit.reset("replacement")
        assertFalse(credit.acknowledge("same-session", 2))
        assertEquals(1L, credit.take(3400))
    }
    @Test fun cadenceAbsorbsCameraJitterWithoutCatchupBurstsAndChangesRateImmediately() {
        val pacer = FramePacer()
        val sampled = mutableListOf<Long>()
        for (now in 0L..60_000L step 47) if (pacer.due(now, 5)) { sampled.add(now); pacer.sampled(now) }
        assertEquals(300, sampled.size)
        assertTrue(sampled.zipWithNext().all { (a, b) -> b - a in 188..235 })
        assertTrue(pacer.due(60_000, 1)); pacer.sampled(60_000)
        assertFalse(pacer.due(60_500, 1)); assertTrue(pacer.due(61_000, 1))
        pacer.sampled(65_000); assertFalse(pacer.due(65_001, 1))
        assertTrue(pacer.due(65_001, 3)); pacer.sampled(65_001)
        assertFalse(pacer.due(65_100, 3))
    }
    @Test fun mediaWithoutFramesStillRequiresResponsesAndNewConnectionsGetTheirOwnDeadline() {
        val health = MediaLiveness(); health.opened(1000)
        assertFalse(health.expired(12_999)); assertTrue(health.expired(13_000))
        health.opened(50_000); assertFalse(health.expired(50_001))
        health.responded(50_002); assertFalse(health.expired(62_001)); assertTrue(health.expired(62_002))
        health.responded(70_000); assertFalse(health.expired(70_001))
    }
}
