package com.xgwnje.visionguard.notifier.ui.main

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.xgwnje.visionguard.account.*
import com.xgwnje.visionguard.icons.LucideIcons
import com.xgwnje.visionguard.notifier.PermissionUtils
import com.xgwnje.visionguard.notifier.node.NotificationNodeService
import com.xgwnje.visionguard.notifier.node.NotificationNodeSettings
import com.xgwnje.visionguard.notifier.ui.*
import com.xgwnje.visionguard.notifier.ui.history.AlertHistoryScreen
import com.xgwnje.visionguard.notifier.ui.settings.*

private enum class NotifierPage(val title: String, val icon: ImageVector) {
    HOME("接警工作台", LucideIcons.Bell), HISTORY("报警记录", LucideIcons.Activity),
    AUDIO("声音与音频", LucideIcons.Radio), SETTINGS("设置", LucideIcons.Settings)
}

@Composable
fun NotificationDashboard(account: AccountStore, session: AccountSession, appearance: AppearancePreference,
    updater: ClientUpdater, viewModel: SettingsViewModel, beforeLogout: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val settings = remember { NotificationNodeSettings(context) }
    var enabled by remember { mutableStateOf(settings.enabled) }
    var canNotify by remember { mutableStateOf(PermissionUtils.canPostNotifications(context)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canNotify = PermissionUtils.canPostNotifications(context)
                enabled = settings.enabled
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val node by NotificationNodeService.state.collectAsState()
    val historyVersion by viewModel.alertHistoryVersion
    val records = remember(historyVersion) { viewModel.getAlertHistory() }
    val timeZone = rememberAlarmTimeZone()
    var currentPage by rememberSaveable { mutableStateOf(NotifierPage.HOME.name) }
    val page = NotifierPage.valueOf(currentPage)
    val holder = rememberSaveableStateHolder()
    val draft = remember { NotifierSettingsDraft(session.deviceName) }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val onEnabled: () -> Unit = {
        val next = !enabled
        runCatching {
            check(!next || NotificationNodeSettings.valid(settings.read())) { "请重新登录" }
            settings.enabled = next
            if (next) NotificationNodeService.start(context) else context.stopService(Intent(context, NotificationNodeService::class.java))
            enabled = next
        }.onFailure {
            runCatching { settings.enabled = false }
            enabled = false; error = it.message ?: "接警服务启动失败"
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tablet = maxWidth >= 1100.dp
        val compact = maxHeight < 520.dp
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(if (tablet) 208.dp else 176.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)).padding(12.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("VisionGuard", Modifier.padding(top = 8.dp, start = 8.dp), style = MaterialTheme.typography.titleMedium)
                    if (!compact) Text("通知节点", Modifier.padding(start = 8.dp, bottom = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    if (!compact) Text("工作区", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    NotifierPage.entries.forEach { item ->
                        if (item == NotifierPage.SETTINGS) {
                            HorizontalDivider(Modifier.padding(vertical = if (compact) 2.dp else 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            if (!compact) Text("管理", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton({
                            if (page != item) { viewModel.stopPreview(); viewModel.cancelRecording(); currentPage = item.name }
                        }, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.textButtonColors(containerColor = if (page == item) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                                contentColor = MaterialTheme.colorScheme.onSurface)) {
                            Icon(item.icon, null, Modifier.size(18.dp))
                            Text(item.title, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (!compact) Text(session.deviceName, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                Text(session.username, Modifier.padding(top = if (compact) 8.dp else 0.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(page.title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                    if (page == NotifierPage.HOME) {
                        if (enabled) OutlinedButton(onEnabled, shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("停止接警") }
                        else Button(onEnabled, shape = MaterialTheme.shapes.small) { Text("开启接警") }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                error?.let { message ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                        TextButton({ error = null }) { Text("关闭") }
                    }
                }
                Box(Modifier.weight(1f)) {
                    holder.SaveableStateProvider(currentPage) {
                        when (page) {
                            NotifierPage.HOME -> NotificationHome(node, enabled, canNotify, records, timeZone,
                                onReconnect = { runCatching { context.stopService(Intent(context, NotificationNodeService::class.java)); NotificationNodeService.start(context) }.onFailure { error = "重新连接失败，请重试" } },
                                onPermission = { (context as? Activity)?.let(PermissionUtils::openAppDetailsSettings) },
                                onHistory = { currentPage = NotifierPage.HISTORY.name })
                            NotifierPage.HISTORY -> AlertHistoryScreen(viewModel)
                            NotifierPage.AUDIO -> RingtoneLibraryScreen(viewModel)
                            NotifierPage.SETTINGS -> NotificationSettingsScreen(account, session, appearance, updater, draft, scope, node, canNotify, beforeLogout)
                        }
                    }
                }
            }
        }
    }
}
