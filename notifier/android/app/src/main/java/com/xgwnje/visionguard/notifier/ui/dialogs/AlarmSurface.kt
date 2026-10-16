package com.xgwnje.visionguard.notifier.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.icons.LucideIcons
import com.xgwnje.visionguard.notifier.AlertQueueItem
import com.xgwnje.visionguard.notifier.node.alarmTimeStandardLabel
import com.xgwnje.visionguard.notifier.node.formatAlarmTime
import com.xgwnje.visionguard.notifier.ui.*
import java.util.Locale

/** Only the explicit action acknowledges the supplied immutable queue item. */
@Composable
internal fun AlarmSurface(item: AlertQueueItem, queuedCount: Int, confirming: Boolean, confirmationError: String?,
    onConfirm: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val timeZone = rememberAlarmTimeZone()
    val target = item.detectedObject?.takeIf { it.valid }
    val title = when (target?.label) { "person" -> "发现人员"; "car" -> "发现车辆"; null -> item.keyword; else -> "发现 ${target.displayName}" }
    Column(modifier.fillMaxSize().background(colors.background).safeDrawingPadding()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val tablet = maxHeight >= 560.dp
            val columns = maxWidth >= 640.dp && LocalDensity.current.fontScale <= 1.3f
            Column(Modifier.widthIn(max = 1088.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(if (tablet) 32.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(if (tablet) 24.dp else 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(LucideIcons.BellRing, null, Modifier.size(24.dp), tint = colors.error)
                    Text("当前报警", style = MaterialTheme.typography.titleMedium)
                }
                val details: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(title, Modifier.fillMaxWidth().semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
                        target?.let { NotifierStatus("置信度 ${String.format(Locale.ROOT, "%.1f%%", it.confidence * 100)}", colors.secondaryContainer, colors.onSecondaryContainer) }
                        if (tablet) NotifierSection("事件信息") { EventFields(item, timeZone) }
                        else EventFields(item, timeZone)
                    }
                }
                val summary: @Composable () -> Unit = {
                    NotifierSection("事件摘要") {
                        Text(item.snippet?.takeIf { it.isNotBlank() } ?: "未报告", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (columns) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1.2f)) { details() }
                    Box(Modifier.weight(1f).padding(top = if (tablet) 64.dp else 0.dp)) { summary() }
                } else { details(); summary() }
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
        Column(Modifier.widthIn(max = 1088.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NotifierMessage(confirmationError, true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("排队 ${queuedCount.coerceAtLeast(0)} 条", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Button({ onConfirm(item.id) }, Modifier.widthIn(min = 200.dp, max = 320.dp).heightIn(min = 56.dp),
                    enabled = !confirming, shape = MaterialTheme.shapes.small) { Text(if (confirming) "确认中…" else "确认当前报警") }
            }
        }
    }
}

@Composable
private fun EventFields(item: AlertQueueItem, timeZone: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("事件" to item.keyword, "来源" to (item.sourceApp?.takeIf { it.isNotBlank() } ?: "未报告"),
            "报警时间" to formatAlarmTime(item.firstTriggeredAt, "yyyy-MM-dd HH:mm:ss", timeZone), "时间标准" to alarmTimeStandardLabel(timeZone)).forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, Modifier.width(80.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
