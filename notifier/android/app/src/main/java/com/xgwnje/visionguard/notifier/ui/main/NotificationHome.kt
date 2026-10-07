package com.xgwnje.visionguard.notifier.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.account.VisionGuardControlColors
import com.xgwnje.visionguard.account.VisionGuardStatusColors
import com.xgwnje.visionguard.icons.LucideIcons
import com.xgwnje.visionguard.notifier.AlertEndType
import com.xgwnje.visionguard.notifier.AlertRecord
import com.xgwnje.visionguard.notifier.node.formatAlarmTime
import com.xgwnje.visionguard.notifier.node.NodeState

private val ConnectionErrorStatuses = setOf(
    "收到无效消息", "登录已失效，请重新登录", "此身份已在另一台设备连接", "报警已保存，请打开 VisionGuard 恢复播放"
)

/** The two regions scroll independently; the reception action stays visible. */
@Composable
internal fun NotificationHome(
    node: NodeState, enabled: Boolean, canNotify: Boolean, ringtone: String, loops: Int,
    records: List<AlertRecord>, timeZone: String?, onEnabled: (Boolean) -> Unit,
    onReconnect: () -> Unit, onPermission: () -> Unit, onRingtone: () -> Unit,
    onLoops: () -> Unit, onLibrary: () -> Unit, onHistory: () -> Unit, onDetails: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize().navigationBarsPadding().padding(12.dp)) {
        val landscape = maxWidth > maxHeight
        val largeText = LocalDensity.current.fontScale > 1.3f
        val tablet = minOf(maxWidth, maxHeight) >= 600.dp
        val controls: @Composable (Modifier, Boolean) -> Unit = { modifier, bounded ->
            ReceptionPanel(node, enabled, canNotify, ringtone, loops, timeZone, modifier, bounded,
                onEnabled, onReconnect, onPermission, onRingtone, onLoops, onLibrary, onDetails)
        }
        if (largeText || !landscape && maxHeight < 440.dp) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                controls(Modifier.fillMaxWidth(), false)
                RecentAlerts(records, timeZone, onHistory, Modifier.fillMaxWidth(), false)
            }
        } else if (landscape) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                controls(Modifier.weight(1f).fillMaxHeight(), true)
                RecentAlerts(records, timeZone, onHistory, Modifier.weight(if (tablet) 2f else 1f).fillMaxHeight(), true)
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                controls(Modifier.weight(1f).fillMaxWidth(), true)
                RecentAlerts(records, timeZone, onHistory, Modifier.weight(if (tablet) 2f else 1f).fillMaxWidth(), true)
            }
        }
    }
}

@Composable
private fun ReceptionPanel(
    node: NodeState, enabled: Boolean, canNotify: Boolean, ringtone: String, loops: Int,
    timeZone: String?, modifier: Modifier, bounded: Boolean, onEnabled: (Boolean) -> Unit,
    onReconnect: () -> Unit, onPermission: () -> Unit, onRingtone: () -> Unit,
    onLoops: () -> Unit, onLibrary: () -> Unit, onDetails: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier) {
        val compact = bounded && maxHeight < 320.dp
        OutlinedCard(Modifier.fillMaxWidth().then(if (bounded) Modifier.fillMaxHeight() else Modifier)
            .semantics { contentDescription = "接警控制区" },
            colors = CardDefaults.outlinedCardColors(containerColor = colors.surface),
            border = BorderStroke(1.dp, colors.outlineVariant), shape = MaterialTheme.shapes.medium) {
            val statusColor = when {
                !enabled -> colors.onSurfaceVariant
                node.status in ConnectionErrorStatuses -> colors.error
                node.connected && node.status == "已连接" -> VisionGuardStatusColors.onSuccessContainer
                else -> VisionGuardStatusColors.warning
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("接收报警", style = MaterialTheme.typography.titleMedium)
                Text(if (enabled) node.status else "未启用", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium, color = statusColor,
                    textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (enabled && !node.connected) OutlinedIconButton(onReconnect, Modifier.size(48.dp),
                    shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, colors.outlineVariant)) {
                    Icon(LucideIcons.RefreshCw, "重新连接", Modifier.size(20.dp))
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            val content: @Composable () -> Unit = {
                if (!canNotify) TextButton(onPermission, Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.small,
                    colors = VisionGuardControlColors.textButton(contentColor = VisionGuardStatusColors.warning)) {
                    Text("通知未授权 · 去设置", Modifier.weight(1f))
                    Icon(LucideIcons.ChevronRight, null, Modifier.size(16.dp))
                }
                if (!compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ReceptionStatistic("本次收件", if (enabled) "${node.received}" else "—", Modifier.weight(1f))
                    ReceptionStatistic("服务响应", if (enabled && node.lastResponse > 0) formatAlarmTime(node.lastResponse, "HH:mm:ss", timeZone) else "—", Modifier.weight(1f))
                }
                ReceptionSetting("接收范围", if (enabled) node.scope else "由控制台分配", onDetails)
                ReceptionSetting("铃声", ringtone, onRingtone)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onLoops, Modifier.weight(1f).heightIn(min = 48.dp), shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) { Text("循环 $loops 次", style = MaterialTheme.typography.bodyMedium) }
                    OutlinedButton(onLibrary, Modifier.weight(1f).heightIn(min = 48.dp), shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) { Text("铃声库", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            if (bounded) Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 8.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) { content() }
            } else Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
            Button({ onEnabled(!enabled) }, Modifier.fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = if (compact) 4.dp else 8.dp, bottom = if (compact) 8.dp else 12.dp)
                .heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.small,
                colors = if (enabled) VisionGuardControlColors.button(containerColor = colors.error, contentColor = colors.onError) else VisionGuardControlColors.button()) {
                Text(if (enabled) "停止接警" else "开启接警")
            }
        }
    }
}

@Composable
private fun ReceptionStatistic(label: String, value: String, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
    }
}

@Composable
private fun ReceptionSetting(label: String, value: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedButton(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, colors.outlineVariant),
        colors = VisionGuardControlColors.outlinedButton(contentColor = colors.onSurface),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, Modifier.width((64 * LocalDensity.current.fontScale).dp),
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Text(value, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(LucideIcons.ChevronRight, null, Modifier.size(16.dp))
    }
}

@Composable
private fun RecentAlerts(records: List<AlertRecord>, timeZone: String?, onHistory: () -> Unit, modifier: Modifier, bounded: Boolean) {
    val colors = MaterialTheme.colorScheme
    OutlinedCard(modifier.semantics { contentDescription = "最近报警区" },
        colors = CardDefaults.outlinedCardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outlineVariant), shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("最近报警", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onHistory, Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) { Text("全部记录") }
        }
        HorizontalDivider(color = colors.outlineVariant)
        if (records.isEmpty()) Box(if (bounded) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(LucideIcons.Bell, null, Modifier.size(24.dp), tint = colors.onSurfaceVariant)
                Text("暂无报警记录", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        } else if (bounded) LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            items(records.take(6)) { record -> RecentAlert(record, timeZone) }
        } else Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) { records.take(4).forEach { RecentAlert(it, timeZone) } }
    }
}

@Composable
private fun RecentAlert(record: AlertRecord, timeZone: String?) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(record.keyword, style = MaterialTheme.typography.bodyLarge)
        Text(record.sourceApp ?: "VG 节点", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(formatAlarmTime(record.timestamp, "MM-dd HH:mm:ss", timeZone), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text(when (record.endType) { AlertEndType.MANUAL -> "手动结束"; AlertEndType.AUTO -> "自动结束"; AlertEndType.ERROR -> "异常结束" },
                style = MaterialTheme.typography.bodySmall, color = if (record.endType == AlertEndType.ERROR) colors.error else colors.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = colors.outlineVariant)
}
