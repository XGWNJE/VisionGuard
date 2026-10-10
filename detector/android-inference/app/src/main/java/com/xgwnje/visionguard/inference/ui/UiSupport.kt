package com.xgwnje.visionguard.inference.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.xgwnje.visionguard.inference.detect.ArgbImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── 画面与位图 ────────────────────────────────────────────────────────

/** ARGB 帧转位图；空帧或尺寸非法返回 null，失败不抛出。 */
fun argbToImageBitmap(frame: ArgbImage?, maxWidth: Int = 0): ImageBitmap? {
    if (frame == null || frame.width <= 0 || frame.height <= 0) return null
    return runCatching {
        val scaled = if (maxWidth > 0 && frame.width > maxWidth) {
            val width = maxWidth.coerceAtLeast(1)
            val height = (frame.height.toLong() * width / frame.width).toInt().coerceAtLeast(1)
            ArgbImage(width, height, scalePixels(frame, width, height))
        } else {
            frame
        }
        Bitmap.createBitmap(scaled.pixels, scaled.width, scaled.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }.getOrNull()
}

/** 最近邻缩放，仅用于缩略图，不参与推理。 */
private fun scalePixels(frame: ArgbImage, width: Int, height: Int): IntArray {
    val output = IntArray(width * height)
    for (row in 0 until height) {
        val sourceRow = (row.toLong() * frame.height / height).toInt().coerceIn(0, frame.height - 1)
        for (col in 0 until width) {
            val sourceCol = (col.toLong() * frame.width / width).toInt().coerceIn(0, frame.width - 1)
            output[row * width + col] = frame.pixels[sourceRow * frame.width + sourceCol]
        }
    }
    return output
}

/** 在后台线程把帧转成位图，避免预览刷新阻塞主线程。 */
@Composable
fun rememberFrameBitmap(frame: ArgbImage?, maxWidth: Int = 0): State<ImageBitmap?> {
    val result = remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(frame, maxWidth) {
        result.value = if (frame == null) null else withContext(Dispatchers.Default) { argbToImageBitmap(frame, maxWidth) }
    }
    return result
}

/** 每秒推进一次的时间戳，用于“几秒前”这类新鲜度提示。 */
@Composable
fun rememberSecondTick(): Long {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now.longValue = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return now.longValue
}

private val timeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

/** 本机单调时钟（已过毫秒）→ 可读时刻；程序刚启动时回退为“刚刚”。 */
fun formatElapsedMoment(elapsedMs: Long?, nowWallMs: Long, nowElapsedMs: Long): String {
    if (elapsedMs == null || elapsedMs <= 0L) return "无记录"
    val wall = nowWallMs - (nowElapsedMs - elapsedMs)
    return timeFormatter.format(Instant.ofEpochMilli(wall).atZone(ZoneId.systemDefault()))
}

/** NTP 校正时间戳（UTC 毫秒）→ 可读时刻。 */
fun formatWallMoment(epochMs: Long?): String {
    if (epochMs == null || epochMs <= 0L) return "无记录"
    return timeFormatter.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}

/** 相对新鲜度描述，用于明确区分实时帧与最后一帧。 */
fun relativeAge(elapsedMs: Long?, nowElapsedMs: Long): String {
    if (elapsedMs == null || elapsedMs <= 0L) return "尚未产生画面"
    val seconds = ((nowElapsedMs - elapsedMs) / 1000).coerceAtLeast(0)
    return when {
        seconds < 1 -> "刚刚"
        seconds < 60 -> "${seconds} 秒前"
        seconds < 3_600 -> "${seconds / 60} 分钟前"
        else -> "${seconds / 3_600} 小时前"
    }
}

