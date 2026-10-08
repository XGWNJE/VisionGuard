package com.xgwnje.visionguard.inference.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.account.VisionGuardStatusColors
import com.xgwnje.visionguard.inference.detect.ArgbImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 界面尺寸令牌：间距只用 4/8/12/16/24/32，卡片刻度沿用共享规范。 */
object UiTokens {
    val ScreenPadding = 12.dp
    val CardPadding = 16.dp
    val Gap = 8.dp
    val GroupGap = 12.dp
}

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

// ── 通用组件 ──────────────────────────────────────────────────────────

/** 分区表面：1dp 描边、8dp 圆角、12–16dp 内距。 */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(UiTokens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(UiTokens.Gap),
            content = content
        )
    }
}

/** 分区标题：16sp/600，与“编辑”这类整行操作同一行。 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** 48dp 整行的轻量操作。 */
@Composable
fun RowAction(text: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp),
        colors = com.xgwnje.visionguard.account.VisionGuardControlColors.textButton(
            contentColor = if (danger) MaterialTheme.colorScheme.error else VisionGuardStatusColors.onSuccessContainer
        )
    ) { Text(text) }
}

/** 标签 + 值 + 可选操作的信息行；标签列固定宽度，值可换行。 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    labelWidth: Int = 72,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    action: (@Composable () -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(labelWidth.dp)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** 运行中 / 已停止指示灯；非交互元素，不占用 48dp 触点。 */
@Composable
fun StatusDot(running: Boolean) {
    Spacer(
        Modifier
            .size(8.dp)
            .background(if (running) VisionGuardStatusColors.success else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
    )
}

/** 截断到单行的辅助文字。 */
@Composable
fun SecondaryText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}
