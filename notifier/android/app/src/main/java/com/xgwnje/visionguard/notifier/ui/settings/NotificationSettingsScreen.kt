package com.xgwnje.visionguard.notifier.ui.settings

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.account.*
import com.xgwnje.visionguard.notifier.PermissionUtils
import com.xgwnje.visionguard.notifier.node.NodeState
import com.xgwnje.visionguard.notifier.node.alarmTimeStandardLabel
import com.xgwnje.visionguard.notifier.node.formatAlarmTime
import com.xgwnje.visionguard.notifier.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class NotifierSettingsDraft(name: String) {
    var deviceName by mutableStateOf(name)
    var savedName by mutableStateOf(name)
    var nameRejected by mutableStateOf(false)
    var currentPassword by mutableStateOf("")
    var newPassword by mutableStateOf("")
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var error by mutableStateOf(false)
}

@Composable
internal fun NotificationSettingsScreen(account: AccountStore, session: AccountSession, appearance: AppearancePreference,
    updater: ClientUpdater, draft: NotifierSettingsDraft, scope: CoroutineScope, node: NodeState, canNotify: Boolean, beforeLogout: () -> Unit) {
    val context = LocalContext.current
    val timeZone = rememberAlarmTimeZone()
    LaunchedEffect(session.deviceName) {
        if (draft.deviceName == draft.savedName) draft.deviceName = session.deviceName
        draft.savedName = session.deviceName
    }
    val perform: (suspend () -> Unit) -> Unit = { action ->
        draft.busy = true; draft.message = null
        scope.launch {
            try { action(); draft.message = "已保存"; draft.error = false }
            catch (e: Exception) { draft.message = e.message?.take(160) ?: "操作失败，请重试"; draft.error = true }
            finally { draft.busy = false }
        }
    }
    NotifierLongPage(listOf(
        NotifierSectionSpec("账号与安全") {
            NotifierSection("账号与安全") {
                NotifierField("当前账号", session.username)
                OutlinedTextField(draft.currentPassword, {
                    if (it.length <= 256) { draft.currentPassword = it; draft.message = null }
                    else { draft.message = "密码最多 256 个字符"; draft.error = true }
                },
                    Modifier.fillMaxWidth(), label = { Text("当前密码") }, enabled = !draft.busy, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = MaterialTheme.shapes.small)
                OutlinedTextField(draft.newPassword, {
                    if (it.length <= 256) { draft.newPassword = it; draft.message = null }
                    else { draft.message = "密码最多 256 个字符"; draft.error = true }
                },
                    Modifier.fillMaxWidth(), label = { Text("新密码") }, enabled = !draft.busy, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = MaterialTheme.shapes.small)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button({ perform {
                        account.changePassword(draft.currentPassword, draft.newPassword)
                        draft.currentPassword = ""; draft.newPassword = ""
                        beforeLogout(); account.clear()
                    } }, enabled = !draft.busy && draft.currentPassword.isNotBlank() && draft.newPassword.length >= 8) { Text("修改密码") }
                    OutlinedButton({ perform { beforeLogout(); account.logout() } }, enabled = !draft.busy) { Text("退出登录") }
                }
                Text("改密后所有设备需要重新登录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                NotifierMessage(draft.message, draft.error)
            }
        },
        NotifierSectionSpec("本机与外观") {
            NotifierSection("本机与外观") {
                OutlinedTextField(draft.deviceName, {
                    draft.nameRejected = !DisplayNamePolicy.acceptsDraft(it)
                    if (!draft.nameRejected) draft.deviceName = it
                }, Modifier.fillMaxWidth(), label = { Text("本机名称") }, enabled = !draft.busy, singleLine = true,
                    isError = draft.nameRejected || DisplayNamePolicy.error(draft.deviceName) != null,
                    shape = MaterialTheme.shapes.small)
                if (draft.nameRejected) NotifierMessage(DisplayNamePolicy.HINT, true)
                Button({ perform { account.renameDevice(draft.deviceName); draft.savedName = draft.deviceName } },
                    enabled = !draft.busy && !draft.nameRejected && DisplayNamePolicy.error(draft.deviceName) == null && draft.deviceName != session.deviceName) { Text(if (draft.busy) "保存中…" else "保存名称") }
                NotifierMessage(draft.message, draft.error)
                Text("外观", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Appearance.entries.forEach { value ->
                        FilterChip(appearance.mode == value, { appearance.select(value) }, label = { Text(value.title) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                NotifierMessage(appearance.error.takeIf { it.isNotBlank() }, true)
            }
        },
        NotifierSectionSpec("连接与维护") {
            NotifierSection("连接与维护") {
                NotifierField("连接状态", node.status)
                NotifierField("服务地址", session.endpoint)
                NotifierField("接收范围", node.scope)
                NotifierField("最后响应", if (node.lastResponse > 0) formatAlarmTime(node.lastResponse, "yyyy-MM-dd HH:mm:ss", timeZone) else "未报告")
                NotifierField("时间标准", alarmTimeStandardLabel(timeZone))
                NotifierField("通知权限", if (canNotify) "已授权" else "未授权")
                OutlinedButton({ (context as? Activity)?.let(PermissionUtils::openAppDetailsSettings) }) { Text("系统通知与后台运行设置") }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ClientUpdateButton("android-notifier", updater)
            }
        }
    ))
}
