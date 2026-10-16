package com.xgwnje.visionguard.notifier.ui.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.notifier.AlertEndType
import com.xgwnje.visionguard.notifier.AlertRecord
import com.xgwnje.visionguard.notifier.node.formatAlarmTime
import com.xgwnje.visionguard.notifier.ui.*
import com.xgwnje.visionguard.notifier.ui.settings.SettingsViewModel

@Composable
fun AlertHistoryScreen(viewModel: SettingsViewModel) {
    val version by viewModel.alertHistoryVersion
    val records = remember(version) { viewModel.getAlertHistory() }
    val timeZone = rememberAlarmTimeZone()
    var clear by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${records.size} 条记录", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({ clear = true }, enabled = records.isNotEmpty()) { Text("清空", color = MaterialTheme.colorScheme.error) }
            }
        }
        if (records.isEmpty()) item { NotifierPanel { Text("暂无报警记录", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        else {
            item { NotifierTableHeader() }
            items(records, key = { it.id }) { AlertRecordRow(it, timeZone) }
        }
    }
    if (clear) NotifierDialog("清空报警记录", { clear = false }, focusOnDismiss = true,
        confirmButton = { TextButton({ runCatching { viewModel.clearAlertHistory() }.onSuccess { clear = false }.onFailure { error = "清空失败，请重试" } }) { Text("清空", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ clear = false }) { Text("取消") } }) {
        Text("清空全部 ${records.size} 条报警记录？")
        NotifierMessage(error, true)
    }
}

@Composable
internal fun AlertRecordRow(record: AlertRecord, timeZone: String?, compactTime: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(record.keyword, Modifier.weight(1.1f), style = MaterialTheme.typography.bodyMedium)
        Text(record.sourceApp ?: "未报告", Modifier.weight(1.5f), style = MaterialTheme.typography.bodyMedium)
        Text(formatAlarmTime(record.timestamp, if (compactTime) "MM-dd HH:mm:ss" else "yyyy-MM-dd HH:mm:ss", timeZone), Modifier.weight(1.4f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f)) {
            val failed = record.endType == AlertEndType.ERROR
            NotifierStatus(when (record.endType) { AlertEndType.MANUAL -> "手动确认"; AlertEndType.AUTO -> "自动结束"; AlertEndType.ERROR -> "播放失败" },
                if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
