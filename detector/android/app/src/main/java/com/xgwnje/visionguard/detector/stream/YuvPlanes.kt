package com.xgwnje.visionguard.detector.stream

import java.nio.ByteBuffer

/** Read each plane in bulk without assuming shared/interleaved chroma buffers. */
internal object YuvPlanes {
    fun toNv21(width: Int, height: Int,
        y: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        u: ByteBuffer, uRowStride: Int, uPixelStride: Int,
        v: ByteBuffer, vRowStride: Int, vPixelStride: Int): ByteArray {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        val result = ByteArray(width * height * 3 / 2)
        copyPlane(y, yRowStride, yPixelStride, width, height, result, 0, 1)
        copyPlane(v, vRowStride, vPixelStride, width / 2, height / 2, result, width * height, 2)
        copyPlane(u, uRowStride, uPixelStride, width / 2, height / 2, result, width * height + 1, 2)
        return result
    }

    private fun copyPlane(source: ByteBuffer, rowStride: Int, pixelStride: Int,
        columns: Int, rows: Int, output: ByteArray, offset: Int, outputStride: Int) {
        require(pixelStride > 0 && rowStride >= (columns - 1) * pixelStride + 1)
        val buffer = source.duplicate()
        val start = buffer.position()
        val rowBytes = (columns - 1) * pixelStride + 1
        val row = if (pixelStride == 1 && outputStride == 1) null else ByteArray(rowBytes)
        for (r in 0 until rows) {
            buffer.position(start + r * rowStride)
            val destination = offset + r * columns * outputStride
            if (row == null) buffer.get(output, destination, columns)
            else {
                buffer.get(row, 0, rowBytes)
                for (c in 0 until columns) output[destination + c * outputStride] = row[c * pixelStride]
            }
        }
    }
}
