package com.xgwnje.visionguard.detector.stream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

data class CameraFrame(val jpeg: ByteArray, val width: Int, val height: Int, val rotation: Int, val capturedAt: Long)

object CameraFrameCodec {
    fun encode(image: ImageProxy, longSide: Int, shortSide: Int): CameraFrame {
        require(image.format == ImageFormat.YUV_420_888 && image.planes.size == 3)
        val width = image.width; val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)
        val y = image.planes[0]; val u = image.planes[1]; val v = image.planes[2]
        val yBuffer = y.buffer.duplicate(); val uBuffer = u.buffer.duplicate(); val vBuffer = v.buffer.duplicate()
        val yOffset = yBuffer.position(); val uOffset = uBuffer.position(); val vOffset = vBuffer.position()
        for (row in 0 until height) for (column in 0 until width)
            nv21[row * width + column] = yBuffer.get(yOffset + row * y.rowStride + column * y.pixelStride)
        var offset = width * height
        for (row in 0 until height / 2) for (column in 0 until width / 2) {
            nv21[offset++] = vBuffer.get(vOffset + row * v.rowStride + column * v.pixelStride)
            nv21[offset++] = uBuffer.get(uOffset + row * u.rowStride + column * u.pixelStride)
        }
        val output = ByteArrayOutputStream()
        check(YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), 55, output))
        var jpeg = output.toByteArray()
        val size = MediaPacket.boundedSize(width, height, longSide, shortSide)
        if (size.first != width || size.second != height) {
            val original = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: error("无法解码相机画面")
            val scaled = Bitmap.createScaledBitmap(original, size.first, size.second, true)
            output.reset(); check(scaled.compress(Bitmap.CompressFormat.JPEG, 55, output))
            jpeg = output.toByteArray()
            if (scaled !== original) scaled.recycle()
            original.recycle()
        }
        val age = ((SystemClock.elapsedRealtimeNanos() - image.imageInfo.timestamp) / 1_000_000).takeIf { it in 0..2000 } ?: 0
        return CameraFrame(jpeg, size.first, size.second, image.imageInfo.rotationDegrees, System.currentTimeMillis() - age)
    }
}
