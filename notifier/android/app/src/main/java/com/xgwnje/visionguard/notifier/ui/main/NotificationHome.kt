package com.xgwnje.visionguard.notifier.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.notifier.AlertRecord
import com.xgwnje.visionguard.notifier.node.NodeState
import com.xgwnje.visionguard.notifier.node.formatAlarmTime
import com.xgwnje.visionguard.notifier.ui.*
import com.xgwnje.visionguard.notifier.ui.history.AlertRecordRow

@Composable
internal fun NotificationHome(node: NodeState, enabled: Boolean, canNotify: Boolean, records: List<AlertRecord>, timeZone: String?,
    onReconnect: () -> Unit, onPermission: () -> Unit, onHistory: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tablet = maxWidth >= 920.dp
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                NotifierSection("接警状态", action = {
                    if (!tablet) NotifierStatus(if (enabled) node.status else "未启用", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                    if (enabled && !node.connected) TextButton(onReconnect, Modifier.heightIn(min = 48.dp)) { Text("重新连接") }
                }) {
                    if (tablet) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        NotifierStatus(if (enabled) node.status else "未启用", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                        NotifierField("接收范围", node.scope, Modifier.weight(1f))
                    }
                    if (!tablet) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        NotifierField("本次收件", if (enabled) node.received.toString() else "—", Modifier.weight(.8f))
                        NotifierField("最后响应", if (enabled && node.lastResponse > 0) formatAlarmTime(node.lastResponse, "HH:mm:ss", timeZone) else "未报告", Modifier.weight(1f))
                        NotifierField("接收范围", node.scope, Modifier.weight(1.5f))
                    }
                    if (!canNotify) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("通知未授权", Modifier.weight(1f), color = MaterialTheme.colorScheme.tertiary)
                        OutlinedButton(onPermission) { Text("去授权") }
                    }
                }
            }
            if (tablet) item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf("本次收件" to if (enabled) node.received.toString() else "—",
                        "最后响应" to if (enabled && node.lastResponse > 0) formatAlarmTime(node.lastResponse, "HH:mm:ss", timeZone) else "未报告",
                        "通知权限" to if (canNotify) "已授权" else "未授权").forEach { (label, value) ->
                        Box(Modifier.weight(1f)) { NotifierPanel { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.titleLarge) } }
                    }
                }
            }
            item {
                NotifierSection("最近报警", action = { TextButton(onHistory) { Text("全部记录") } }) {
                    if (records.isEmpty()) Text("暂无报警记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else {
                        NotifierTableHeader()
                        records.take(if (tablet) 6 else 3).forEach { AlertRecordRow(it, timeZone, compactTime = true) }
                    }
                }
            }
        }
    }
}
