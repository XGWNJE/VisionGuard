package com.xgwnje.visionguard.detector.stream

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class YuvPlanesTest {
    private val expected = byteArrayOf(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,31,21,32,22,33,23,34,24)

    @Test fun planarWithOffsetsAndRowPaddingPreservesPixelsAndBufferPositions() {
        val y = ByteBuffer.wrap(byteArrayOf(99,1,2,3,4,99,99,5,6,7,8,99,99,9,10,11,12,99,99,13,14,15,16)).apply { position(1) }
        val u = ByteBuffer.wrap(byteArrayOf(99,21,22,99,23,24)).apply { position(1) }
        val v = ByteBuffer.wrap(byteArrayOf(99,31,32,99,33,34)).apply { position(1) }
        assertArrayEquals(expected, YuvPlanes.toNv21(4,4,y,6,1,u,3,1,v,3,1))
        assertEquals(1, y.position()); assertEquals(1, u.position()); assertEquals(1, v.position())
    }

    @Test fun directInterleavedChromaSupportsMissingFinalRowPadding() {
        val y = ByteBuffer.allocateDirect(16).apply { put(expected,0,16); flip() }
        val shared = ByteBuffer.allocateDirect(8).apply { put(byteArrayOf(31,21,32,22,33,23,34,24)); flip() }
        val v = shared.duplicate().apply { limit(7) }
        val u = shared.duplicate().apply { position(1) }
        assertArrayEquals(expected, YuvPlanes.toNv21(4,4,y,4,1,u,4,2,v,4,2))
        assertEquals(0, v.position()); assertEquals(1, u.position())
    }

    @Test fun independentChromaBuffersAndStridedLumaDoNotAssumeNv21Layout() {
        val y = ByteBuffer.wrap(byteArrayOf(1,99,2,99,3,99,4,99,5,99,6,99,7,99,8,99,9,99,10,99,11,99,12,99,13,99,14,99,15,99,16))
        val u = ByteBuffer.wrap(byteArrayOf(21,99,22,99,99,23,99,24))
        val v = ByteBuffer.wrap(byteArrayOf(31,88,32,88,88,33,88,34))
        assertArrayEquals(expected, YuvPlanes.toNv21(4,4,y,8,2,u,5,2,v,5,2))
    }
}
