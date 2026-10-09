package com.xgwnje.visionguard.inference.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.inference.detect.CocoClassMap
import com.xgwnje.visionguard.inference.detect.Detection
import com.xgwnje.visionguard.inference.detect.MaskRegion

/**
 * 把画面等比放进可用区域（letterbox），不裁切也不拉伸。
 * 画面比例与容器比例不一致时，多出的区域保持容器底色。
 */
fun fitRect(sourceWidth: Int, sourceHeight: Int, containerWidth: Float, containerHeight: Float): Rect? {
    if (sourceWidth <= 0 || sourceHeight <= 0 || containerWidth <= 0f || containerHeight <= 0f) return null
    val scale = minOf(containerWidth / sourceWidth, containerHeight / sourceHeight)
    val width = sourceWidth * scale
    val height = sourceHeight * scale
    val left = (containerWidth - width) / 2f
    val top = (containerHeight - height) / 2f
    return Rect(left, top, left + width, top + height)
}

/**
 * 画面预览：等比 letterbox 显示最后一帧，叠加遮罩与检测框。
 *
 * [compact] 用于来源卡片缩略图，只画框不画标签，避免小卡片里文字糊成一团。
 */
@Composable
fun FrameCanvas(
    image: ImageBitmap?,
    detections: List<Detection>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    maskRegions: List<MaskRegion> = emptyList()
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodySmall
    val boxColor = MaterialTheme.colorScheme.primary
    val labelBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
    val labelText = MaterialTheme.colorScheme.onSurface
    val maskFill = Color.Black.copy(alpha = 0.62f)
    val maskEdge = MaterialTheme.colorScheme.tertiary

    Canvas(modifier.background(FrameBackdrop)) {
        val frame = image ?: return@Canvas
        val rect = fitRect(frame.width, frame.height, size.width, size.height) ?: return@Canvas
        drawImage(
            image = frame,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(frame.width, frame.height),
            dstOffset = IntOffset(rect.left.toInt(), rect.top.toInt()),
            dstSize = IntSize(rect.width.toInt().coerceAtLeast(1), rect.height.toInt().coerceAtLeast(1))
        )

        val scaleX = rect.width / frame.width
        val scaleY = rect.height / frame.height

        // 遮罩：涂黑区域不参与识别，也不会出现在报警截图里。
        for (mask in maskRegions) {
            val left = rect.left + mask.x * rect.width
            val top = rect.top + mask.y * rect.height
            val width = (mask.width * rect.width).coerceAtLeast(1f)
            val height = (mask.height * rect.height).coerceAtLeast(1f)
            drawRect(maskFill, Offset(left, top), Size(width, height))
            drawRect(maskEdge, Offset(left, top), Size(width, height), style = Stroke(width = 1.dp.toPx()))
        }

        for (detection in detections) {
            val left = rect.left + detection.box.left * scaleX
            val top = rect.top + detection.box.top * scaleY
            val width = detection.box.width * scaleX
            val height = detection.box.height * scaleY
            if (width <= 0f || height <= 0f) continue
            drawRect(
                boxColor,
                Offset(left, top),
                Size(width, height),
                style = Stroke(width = if (compact) 1.dp.toPx() else 2.dp.toPx())
            )
            if (compact) continue
            drawDetectionLabel(
                measurer = measurer,
                style = labelStyle,
                text = "${CocoClassMap.displayName(detection.label)} ${(detection.confidence * 100).toInt()}%",
                textColor = labelText,
                background = labelBackground,
                frameRect = rect,
                boxLeft = left,
                boxTop = top
            )
        }
    }
}

/** 标签贴着检测框上沿，并限制在画面矩形内，长文字不溢出。 */
private fun DrawScope.drawDetectionLabel(
    measurer: TextMeasurer,
    style: TextStyle,
    text: String,
    textColor: Color,
    background: Color,
    frameRect: Rect,
    boxLeft: Float,
    boxTop: Float
) {
    val padding = 4.dp.toPx()
    val measured = measurer.measure(text, style)
    val labelWidth = (measured.size.width + padding * 2).coerceAtMost(frameRect.width)
    val labelHeight = measured.size.height + padding
    val left = boxLeft.coerceIn(frameRect.left, (frameRect.right - labelWidth).coerceAtLeast(frameRect.left))
    val top = (boxTop - labelHeight).coerceAtLeast(frameRect.top)
    drawRect(background, Offset(left, top), Size(labelWidth, labelHeight))
    drawText(
        textLayoutResult = measured,
        color = textColor,
        topLeft = Offset(left + padding, top + (labelHeight - measured.size.height) / 2f)
    )
}

/** 来源卡片缩略图：只画画面、遮罩与检测框。 */
@Composable
fun ThumbnailCanvas(
    image: ImageBitmap?,
    detections: List<Detection>,
    modifier: Modifier = Modifier,
    maskRegions: List<MaskRegion> = emptyList()
) = FrameCanvas(image = image, detections = detections, modifier = modifier, compact = true, maskRegions = maskRegions)

/** 空画面占位。 */
@Composable
fun EmptyFramePlaceholder(modifier: Modifier = Modifier) {
    Box(modifier.background(FrameBackdrop), contentAlignment = Alignment.Center) {
        Text("暂无画面", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
    }
}

/** 画面容器底色，避免空态与留边闪烁。 */
val FrameBackdrop: Color = Color.Black
