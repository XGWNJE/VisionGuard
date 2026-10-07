package com.xgwnje.visionguard.notifier.ui.main

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.icons.LucideIcons
import com.xgwnje.visionguard.notifier.PermissionUtils
import com.xgwnje.visionguard.notifier.node.alarmTimeStandardLabel
import com.xgwnje.visionguard.notifier.node.NotificationNodeService
import com.xgwnje.visionguard.notifier.node.NotificationNodeSettings
import com.xgwnje.visionguard.notifier.node.formatAlarmTime
import com.xgwnje.visionguard.notifier.ui.NotifierDialog
import com.xgwnje.visionguard.notifier.ui.rememberAlarmTimeZone

@Composable
internal fun NotificationHelpButton() {
    var open by remember { mutableStateOf(false) }
    IconButton({ open = true }, Modifier.size(48.dp)) {
        Icon(LucideIcons.CircleHelp, "接警帮助", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    NotificationHelp(open) { open = false }
}

@Composable
internal fun NotificationHelp(open: Boolean, onClose: () -> Unit) {
    if (!open) return
    val context = LocalContext.current
    val node by NotificationNodeService.state.collectAsState()
    val connection = remember(context) { NotificationNodeSettings(context).read() }
    val timeZone = rememberAlarmTimeZone()
    NotifierDialog("接警帮助", onClose,
        confirmButton = { TextButton(onClose, Modifier.heightIn(min = 48.dp)) { Text("关闭") } }) {
        HelpSection("接收范围", node.scope)
        HelpSection("接警与声音", "在控制台分配接收范围，开启接警后保持服务连接。接警开关控制后续接收；正在播放的报警仍须确认停止，或达到设定次数自动结束。")
        HelpSection("收件与记录", "本次收件是当前连接服务的保存计数，不代表声音已播放。最近报警只列已结束的记录，历史不会重新播放，过期事件不重新通知。")
        HelpSection("连接详情", "当前状态：${node.status}\n服务地址：${connection.endpoint.ifBlank { "尚未配置" }}\n告警时间标准：${alarmTimeStandardLabel(timeZone)}")
        HelpSection("连接统计", "本次收件：${node.received}\n服务响应：${if (node.lastResponse > 0) formatAlarmTime(node.lastResponse, "HH:mm:ss", timeZone) else "尚未收到响应"}")
        HelpSection("后台与权限", "允许系统通知及后台运行。不同系统的电源管理可能影响接收；是否收到并播放须通过实际报警检查。")
        OutlinedButton({ (context as? Activity)?.let(PermissionUtils::openAppDetailsSettings) }, Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.small) { Text("系统通知与后台运行设置") }
    }
}

@Composable
private fun HelpSection(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
