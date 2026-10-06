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
        // 本机采样开始时间；不同相机的 sensor timestamp 时钟域不能猜测。
        val sampledAt = SystemClock.elapsedRealtime()
        val started = SystemClock.elapsedRealtimeNanos()
        require(image.format == ImageFormat.YUV_420_888 && image.planes.size == 3)
        val width = image.width; val height = image.height
        val y = image.planes[0]; val u = image.planes[1]; val v = image.planes[2]
        val nv21 = YuvPlanes.toNv21(width, height,
            y.buffer, y.rowStride, y.pixelStride, u.buffer, u.rowStride, u.pixelStride, v.buffer, v.rowStride, v.pixelStride)
        val converted = SystemClock.elapsedRealtimeNanos()
        val output = ByteArrayOutputStream()
        check(YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), 55, output))
        var jpeg = output.toByteArray()
        val compressed = SystemClock.elapsedRealtimeNanos()
        val size = MediaPacket.boundedSize(width, height, longSide, shortSide)
        if (size.first != width || size.second != height) {
            val original = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: error("无法解码相机画面")
            val scaled = Bitmap.createScaledBitmap(original, size.first, size.second, true)
            output.reset(); check(scaled.compress(Bitmap.CompressFormat.JPEG, 55, output))
            jpeg = output.toByteArray()
            if (scaled !== original) scaled.recycle()
            original.recycle()
        }
        MediaDiagnostics.log { "event=encode captureWidth=$width captureHeight=$height outputWidth=${size.first} outputHeight=${size.second} rawBytes=${nv21.size} jpegBytes=${jpeg.size} quality=55 convertUs=${(converted-started)/1000} jpegUs=${(compressed-converted)/1000} resizeUs=${(SystemClock.elapsedRealtimeNanos()-compressed)/1000}" }
        return CameraFrame(jpeg, size.first, size.second, image.imageInfo.rotationDegrees, sampledAt)
    }
}
