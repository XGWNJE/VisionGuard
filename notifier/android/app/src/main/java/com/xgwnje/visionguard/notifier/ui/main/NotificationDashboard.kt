package com.xgwnje.visionguard.notifier.ui.main

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.xgwnje.visionguard.notifier.*
import com.xgwnje.visionguard.notifier.node.*
import com.xgwnje.visionguard.notifier.ui.NotifierDialog
import com.xgwnje.visionguard.notifier.ui.rememberAlarmTimeZone
import com.xgwnje.visionguard.notifier.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

@Composable
fun NotificationDashboard(viewModel: SettingsViewModel, onHistory: () -> Unit, onLibrary: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var canPostNotifications by remember(context) { mutableStateOf(PermissionUtils.canPostNotifications(context)) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) canPostNotifications = PermissionUtils.canPostNotifications(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val reportError: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }
    val settings = remember { NotificationNodeSettings(context) }
    val alarms = remember { SharedPreferencesHelper(context) }
    val node by NotificationNodeService.state.collectAsState()
    val timeZone = rememberAlarmTimeZone()
    var enabled by remember { mutableStateOf(settings.enabled) }
    val connection = remember { settings.read() }
    var loopDialog by remember { mutableStateOf(false) }
    var ringtoneDialog by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    var ringtoneError by remember { mutableStateOf<String?>(null) }
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
    Box(Modifier.fillMaxSize()) {
        NotificationHome(node, enabled, canPostNotifications, ringtone, loops, records, timeZone,
            onEnabled = { value ->
                if (value && !NotificationNodeSettings.valid(connection)) reportError("请重新登录")
                else runCatching {
                    settings.enabled = value
                    if (value) NotificationNodeService.start(context) else context.stopService(Intent(context, NotificationNodeService::class.java))
                    enabled = value
                }.onFailure {
                    runCatching { settings.enabled = false }
                    enabled = false
                    reportError("连接启动失败，请检查通知权限")
                }
            },
            onReconnect = {
                runCatching { context.stopService(Intent(context, NotificationNodeService::class.java)); NotificationNodeService.start(context) }
                    .onFailure { reportError("重新连接失败，请重试") }
            },
            onPermission = { (context as? Activity)?.let(PermissionUtils::openAppDetailsSettings) },
            onRingtone = { viewModel.loadRingtoneLibrary(); ringtoneError = null; ringtoneDialog = true },
            onLoops = { loopDialog = true }, onLibrary = onLibrary, onHistory = onHistory,
            onDetails = { detailsOpen = true })
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)) { data ->
            Snackbar(data, shape = MaterialTheme.shapes.small, containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
    NotificationHelp(detailsOpen) { detailsOpen = false }
    if (loopDialog) NotifierDialog(
        title = "循环次数",
        onDismiss = { loopDialog = false },
        confirmButton = { TextButton({ loopDialog = false }, Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("关闭") } }
    ) {
        Text("达到设定次数后自动停止报警。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        (1..10).forEach { count ->
            SelectionOption("$count 次", loops == count) { viewModel.onDefaultLoopCountSelected(count); loopDialog = false }
        }
    }
    if (ringtoneDialog) NotifierDialog(
        title = "默认铃声",
        onDismiss = { ringtoneDialog = false },
        confirmButton = { TextButton({ ringtoneDialog = false }, Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("关闭") } }
    ) {
        val selected = viewModel.getDefaultRingtoneValue()
        ringtoneError?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error) }
        SelectionOption("静音", selected == RingtoneLibrary.SILENT_VALUE) { viewModel.onRingtoneValueSelected(RingtoneLibrary.SILENT_VALUE); ringtoneDialog = false }
        SelectionOption("系统默认闹钟铃声", selected == null) { viewModel.onRingtoneValueSelected(null); ringtoneDialog = false }
        OutlinedButton(
            onClick = {
                runCatching {
                    picker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择报警铃声")
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, selected?.takeIf { it.startsWith("content://") }?.let(Uri::parse))
                    })
                    ringtoneDialog = false
                }.onFailure { ringtoneError = "系统铃声选择不可用，请使用铃声库" }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.small
        ) { Text("选择系统铃声") }
        Text("内置铃声", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RingtoneLibrary.PRESETS.forEach { preset ->
            val value = RingtoneLibrary.presetValue(context, preset)
            SelectionOption(preset.displayName, selected == value) { viewModel.onRingtoneValueSelected(value); ringtoneDialog = false }
        }
        if (viewModel.ringtoneLibrary.isNotEmpty()) Text("铃声库", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        viewModel.ringtoneLibrary.forEach { (fileName, label) ->
            val value = java.io.File(RingtoneLibrary.libraryDir(context), fileName).absolutePath
            SelectionOption(label, selected == value) { viewModel.onRingtoneValueSelected(value); ringtoneDialog = false }
        }
    }
}

@Composable
private fun SelectionOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
            .selectable(selected, onClick = onClick, role = Role.RadioButton).heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RadioButton(selected, onClick = null)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
    }
}
