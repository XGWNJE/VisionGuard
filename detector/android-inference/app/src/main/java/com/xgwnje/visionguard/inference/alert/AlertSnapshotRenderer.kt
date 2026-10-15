package com.xgwnje.visionguard.inference.alert

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.xgwnje.visionguard.inference.detect.ArgbImage
import com.xgwnje.visionguard.inference.detect.CocoClassMap
import com.xgwnje.visionguard.inference.detect.Detection
import java.io.ByteArrayOutputStream

/**
 * 报警截图渲染：在已涂黑遮罩的推理帧上画检测框与标签，再编码成 JPEG。
 *
 * 与 Windows `Services/SnapshotRenderer.cs` 语义一致：截图使用推理帧本身，
 * 因此遮罩区域在通知截图里同样是黑色，不会泄露被遮挡的画面。
 * 发送前等比缩放到宽不超过 [MAX_SEND_WIDTH]，JPEG 质量固定 [JPEG_QUALITY]。
 */
object AlertSnapshotRenderer {

    const val MAX_SEND_WIDTH = 960
    const val JPEG_QUALITY = 65

    /** 渲染并编码；返回 JPEG 字节与最终宽高。 */
    fun renderAndEncode(frame: ArgbImage, detections: List<Detection>): RenderedSnapshot? {
        val bitmap = runCatching {
            Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null

        try {
            bitmap.setPixels(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            drawDetections(bitmap, detections)
            val scaled = scaleDown(bitmap)
            try {
                val bytes = ByteArrayOutputStream().use { output ->
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) return null
                    output.toByteArray()
                }
                return RenderedSnapshot(bytes, scaled.width, scaled.height)
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawDetections(bitmap: Bitmap, detections: List<Detection>) {
        if (detections.isEmpty()) return
        val canvas = Canvas(bitmap)
        val stroke = maxOf(2f, bitmap.width / 320f)
        val boxPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = Color.parseColor("#4FBCC1")
            isAntiAlias = true
        }
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = maxOf(12f, bitmap.width / 48f)
            isAntiAlias = true
            isFakeBoldText = true
        }
        val labelBackground = Paint().apply { color = Color.parseColor("#CC087F83") }

        for (detection in detections) {
            val box = detection.box
            val left = box.left.toInt().coerceIn(0, maxOf(0, bitmap.width - 1))
            val top = box.top.toInt().coerceIn(0, maxOf(0, bitmap.height - 1))
            val right = box.right.toInt().coerceIn(left + 1, bitmap.width)
            val bottom = box.bottom.toInt().coerceIn(top + 1, bitmap.height)
            canvas.drawRect(Rect(left, top, right, bottom), boxPaint)

            val text = "${CocoClassMap.displayName(detection.label)} ${(detection.confidence * 100).toInt()}%"
            val textWidth = textPaint.measureText(text)
            val textHeight = textPaint.textSize
            val barTop = (top - textHeight - stroke * 2).coerceAtLeast(0f)
            canvas.drawRect(left.toFloat(), barTop, left + textWidth + stroke * 2, barTop + textHeight + stroke * 2, labelBackground)
            canvas.drawText(text, left + stroke, barTop + textHeight, textPaint)
        }
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= MAX_SEND_WIDTH) return bitmap
        val height = (bitmap.height * (MAX_SEND_WIDTH.toDouble() / bitmap.width)).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, MAX_SEND_WIDTH, height, true)
    }

    data class RenderedSnapshot(val jpeg: ByteArray, val width: Int, val height: Int) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }
}
