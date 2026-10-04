package com.xgwnje.visionguard.notifier.ui.main

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import com.xgwnje.visionguard.account.VisionGuardControlColors
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.xgwnje.visionguard.account.VisionGuardStatusColors
import com.xgwnje.visionguard.notifier.*
import com.xgwnje.visionguard.notifier.node.*
import com.xgwnje.visionguard.notifier.ui.NotifierDialog
import com.xgwnje.visionguard.notifier.ui.NotifierEmptyState
import com.xgwnje.visionguard.notifier.ui.NotifierPanel
import com.xgwnje.visionguard.notifier.ui.NotifierStatus
import com.xgwnje.visionguard.notifier.ui.rememberAlarmTimeZone
import com.xgwnje.visionguard.notifier.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

// Exact error messages emitted by NotificationNodeService; retry/timeout states remain warnings.
private val ConnectionErrorStatuses = setOf(
    "收到无效消息",
    "登录已失效，请重新登录",
    "此身份已在另一台设备连接",
    "报警已保存，请打开 VisionGuard 恢复播放"
)

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
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("接警面板", style = MaterialTheme.typography.headlineMedium)
            Text("接收 VisionGuard 系统报警 · v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NotifierPanel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("接收报警", style = MaterialTheme.typography.titleMedium)
                        Text(connection.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { value ->
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
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "接收 VisionGuard 报警" }
                    )
                }
                val pending = enabled && !node.connected
                val healthy = enabled && node.connected && node.status == "已连接"
                val failed = enabled && node.status in ConnectionErrorStatuses
                NotifierStatus(
                    label = if (enabled) node.status else "未启用",
                    container = when { healthy -> VisionGuardStatusColors.successContainer; failed -> MaterialTheme.colorScheme.errorContainer; enabled -> VisionGuardStatusColors.warningContainer; else -> MaterialTheme.colorScheme.surfaceVariant },
                    content = when { healthy -> VisionGuardStatusColors.onSuccessContainer; failed -> MaterialTheme.colorScheme.onErrorContainer; enabled -> VisionGuardStatusColors.onWarningContainer; else -> MaterialTheme.colorScheme.onSurfaceVariant }
                )
                if (!canPostNotifications) {
                    NotifierStatus("系统通知未授权", VisionGuardStatusColors.warningContainer, VisionGuardStatusColors.onWarningContainer)
                    Text("请在系统设置中允许通知，以显示后台报警通知。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(
                        onClick = { (context as? Activity)?.let(PermissionUtils::openAppDetailsSettings) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.small
                    ) { Text("前往系统设置") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Field("服务地址", connection.endpoint.ifBlank { "尚未配置" })
                Field("接收范围", if (enabled) node.scope else "由控制台分配")
                Field("告警时间标准", alarmTimeStandardLabel(timeZone))
                if (pending) OutlinedButton(
                    onClick = {
                        runCatching { context.stopService(Intent(context, NotificationNodeService::class.java)); NotificationNodeService.start(context) }
                            .onFailure { reportError("重新连接失败，请重试") }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.small
                ) { Text("重新连接") }
            }
            NotifierPanel {
                Text("声音策略", style = MaterialTheme.typography.titleMedium)
                SettingAction("默认铃声", ringtone) { viewModel.loadRingtoneLibrary(); ringtoneError = null; ringtoneDialog = true }
                SettingAction("循环次数", "$loops 次") { loopDialog = true }
                OutlinedButton(onLibrary, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("管理铃声库") }
                TextButton({ viewModel.onRingtoneValueSelected(null) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("恢复系统默认铃声") }
                Text("确认后停止；达到次数自动结束。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            NotifierPanel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("最近报警", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onHistory, Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("查看全部") }
                }
                if (records.isEmpty()) NotifierEmptyState("暂无报警记录", "报警结束后，可在这里查看来源、时间和结束方式。")
                records.take(4).forEach { record ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(record.keyword, style = MaterialTheme.typography.titleSmall)
                    Text(record.sourceApp ?: "VG 节点", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatAlarmTime(record.timestamp, "yyyy-MM-dd HH:mm:ss", timeZone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton({ (context as? Activity)?.let(PermissionUtils::openAppDetailsSettings) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small,
                colors = VisionGuardControlColors.textButton(contentColor = VisionGuardStatusColors.onSuccessContainer)) { Text("系统通知与后台运行设置") }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)) { data ->
            Snackbar(data, shape = MaterialTheme.shapes.small, containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
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
private fun Field(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SettingAction(label: String, value: String, onClick: () -> Unit) {
    TextButton(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small, contentPadding = PaddingValues(12.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
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
