package com.xgwnje.visionguard.notifier.ui.main

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xgwnje.visionguard.notifier.*
import com.xgwnje.visionguard.notifier.node.*
import com.xgwnje.visionguard.notifier.ui.rememberAlarmTimeZone
import com.xgwnje.visionguard.notifier.ui.settings.SettingsViewModel

@Composable
fun NotificationDashboard(viewModel: SettingsViewModel, onHistory: () -> Unit, onLibrary: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { NotificationNodeSettings(context) }
    val alarms = remember { SharedPreferencesHelper(context) }
    val node by NotificationNodeService.state.collectAsState()
    val timeZone = rememberAlarmTimeZone()
    var enabled by remember { mutableStateOf(settings.enabled) }
    var connection by remember { mutableStateOf(settings.read()) }
    var editing by remember { mutableStateOf(false) }
    var loopDialog by remember { mutableStateOf(false) }
    var ringtoneDialog by remember { mutableStateOf(false) }
    val loops by viewModel.defaultLoopCount
    val ringtone by viewModel.selectedRingtoneName
    val historyVersion by viewModel.alertHistoryVersion
    val records = remember(historyVersion) { alarms.getAlertHistory() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            viewModel.onRingtoneValueSelected(uri?.toString() ?: RingtoneLibrary.SILENT_VALUE)
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("VisionGuard 通知节点", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("接收 VG 系统报警 · v${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Panel {
            Row {
                Column(Modifier.weight(1f)) { Text(connection.name, fontWeight = FontWeight.SemiBold); Text(if (enabled) node.status else "未启用") }
                Switch(enabled, { value ->
                    if (value && !NotificationNodeSettings.valid(connection)) editing = true
                    else runCatching {
                        settings.enabled = value
                        if (value) NotificationNodeService.start(context) else context.stopService(Intent(context, NotificationNodeService::class.java))
                        enabled = value
                    }.onFailure {
                        runCatching { settings.enabled = false }
                        enabled = false
                        Toast.makeText(context, "连接启动失败，请检查通知权限", Toast.LENGTH_LONG).show()
                    }
                })
            }
            Field("服务地址", connection.endpoint.ifBlank { "尚未配置" })
            Field("通道", connection.channel.ifBlank { "未配置" })
            Field("接收范围", if (enabled) node.scope else "由控制台分配")
            Field("告警时间标准", alarmTimeStandardLabel(timeZone))
            Row {
                TextButton({ editing = true }) { Text("连接设置") }
                if (enabled && !node.connected) TextButton({
                    runCatching { context.stopService(Intent(context, NotificationNodeService::class.java)); NotificationNodeService.start(context) }
                        .onFailure { Toast.makeText(context, "重新连接失败，请重试", Toast.LENGTH_LONG).show() }
                }) { Text("重新连接") }
            }
        }
        Panel {
            Text("声音策略", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            TextButton({ viewModel.loadRingtoneLibrary(); ringtoneDialog = true }) { Text("默认铃声：$ringtone") }
            TextButton({ loopDialog = true }) { Text("循环次数：$loops 次") }
            TextButton(onLibrary) { Text("铃声库") }
            TextButton({ viewModel.onRingtoneValueSelected(null) }) { Text("恢复系统默认铃声") }
            Text("确认后停止；达到次数自动结束", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Panel {
            Row { Text("最近报警", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); TextButton(onHistory) { Text("查看全部") } }
            if (records.isEmpty()) Text("暂无报警记录")
            records.take(4).forEach { record ->
                HorizontalDivider()
                Text(record.keyword)
                Text(record.sourceApp ?: "VG 节点", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatAlarmTime(record.timestamp, "yyyy-MM-dd HH:mm:ss", timeZone))
            }
        }
        TextButton({ (context as? Activity)?.let(PermissionUtils::openAppDetailsSettings) }) { Text("系统通知与后台运行设置") }
    }
    if (loopDialog) AlertDialog(onDismissRequest = { loopDialog = false }, title = { Text("循环次数") }, text = {
        Column { (1..10).forEach { count -> TextButton({ viewModel.onDefaultLoopCountSelected(count); loopDialog = false }) { Text("$count 次") } } }
    }, confirmButton = { TextButton({ loopDialog = false }) { Text("关闭") } })
    if (ringtoneDialog) AlertDialog(onDismissRequest = { ringtoneDialog = false }, title = { Text("默认铃声") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            TextButton({ viewModel.onRingtoneValueSelected(RingtoneLibrary.SILENT_VALUE); ringtoneDialog = false }) { Text("静音") }
            TextButton({ viewModel.onRingtoneValueSelected(null); ringtoneDialog = false }) { Text("系统默认闹钟铃声") }
            TextButton({
                runCatching { picker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择报警铃声")
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    val selected = viewModel.getDefaultRingtoneValue()?.takeIf { it.startsWith("content://") }?.let(Uri::parse)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, selected)
                }); ringtoneDialog = false }.onFailure { Toast.makeText(context, "系统铃声选择不可用，请使用铃声库", Toast.LENGTH_LONG).show() }
            }) { Text("选择系统铃声") }
            RingtoneLibrary.PRESETS.forEach { preset ->
                TextButton({ viewModel.onRingtoneValueSelected(RingtoneLibrary.presetValue(context, preset)); ringtoneDialog = false }) { Text(preset.displayName) }
            }
            viewModel.ringtoneLibrary.forEach { (fileName, label) ->
                TextButton({ viewModel.onRingtoneValueSelected(java.io.File(RingtoneLibrary.libraryDir(context), fileName).absolutePath); ringtoneDialog = false }) { Text(label) }
            }
        }
    }, confirmButton = { TextButton({ ringtoneDialog = false }) { Text("关闭") } })
    if (editing) ConnectionDialog(connection, { editing = false }) { value ->
        if (settings.save(value)) {
            context.stopService(Intent(context, NotificationNodeService::class.java))
            enabled = false; connection = value; editing = false
        } else Toast.makeText(context, "连接配置无效或保存失败", Toast.LENGTH_LONG).show()
    }
}

@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
@Composable private fun Field(label: String, value: String) {
    Row { Text(label, Modifier.width(110.dp), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, Modifier.weight(1f)) }
}
@Composable private fun ConnectionDialog(value: NodeConnection, onDismiss: () -> Unit, onSave: (NodeConnection) -> Unit) {
    var draft by remember { mutableStateOf(value) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("连接设置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("使用已登记的 VG 通知节点身份。保存后重新启用连接。")
            OutlinedTextField(draft.endpoint, { draft = draft.copy(endpoint = it.trim()) }, label = { Text("服务地址 wss://…/ws") }, singleLine = true)
            OutlinedTextField(draft.channel, { draft = draft.copy(channel = it.trim()) }, label = { Text("通道") }, singleLine = true)
            OutlinedTextField(draft.deviceId, { draft = draft.copy(deviceId = it.trim()) }, label = { Text("节点 ID") }, singleLine = true)
            OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text("节点名称") }, singleLine = true)
            OutlinedTextField(draft.apiKey, { draft = draft.copy(apiKey = it.trim()) }, label = { Text("节点凭据") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
        }
    }, confirmButton = { TextButton({ onSave(draft) }, enabled = NotificationNodeSettings.valid(draft)) { Text("保存") } }, dismissButton = { TextButton(onDismiss) { Text("取消") } })
}
