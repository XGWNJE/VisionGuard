package com.xgwnje.visionguard.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

private data class ManagedAccount(val id: String, val username: String, val isAdmin: Boolean, val enabled: Boolean)

@Composable
internal fun AccountManagement(store: AccountStore, session: AccountSession, onClose: () -> Unit) {
    var accounts by remember { mutableStateOf<List<ManagedAccount>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var administrator by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ManagedAccount?>(null) }
    var resetPassword by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    suspend fun refresh() {
        val list = store.request("/api/admin/accounts").getJSONArray("accounts")
        accounts = (0 until list.length()).map { index -> list.getJSONObject(index).let {
            ManagedAccount(it.getString("accountId"), it.getString("username"), it.getBoolean("isAdmin"), it.getBoolean("enabled"))
        } }
    }
    fun submit(action: suspend () -> Unit) {
        busy = true; error = null; message = null
        scope.launch {
            try { action(); refresh() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message?.take(160) ?: "账号操作失败" }
            finally { busy = false; loading = false }
        }
    }
    LaunchedEffect(session.token) {
        try { refresh() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message?.take(160) ?: "无法读取账号" }
        finally { loading = false }
    }
    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 760.dp).fillMaxWidth(), shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), tonalElevation = 0.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("账号管理", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClose, enabled = !busy, colors = VisionGuardControlColors.textButton(), shape = MaterialTheme.shapes.small) { Text("关闭") }
                    }
                    Text("禁用、重置密码或变更权限会撤销该账号的会话。至少保留一个启用的管理员。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text("创建账号", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(username, { username = it }, label = { Text("账号") }, supportingText = { Text("3–64 位字母、数字、点、下划线或短横线，以字母或数字开头") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, colors = VisionGuardControlColors.outlinedField())
                    OutlinedTextField(password, { password = it }, label = { Text("初始密码") }, supportingText = { Text("8–256 个字符") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, colors = VisionGuardControlColors.outlinedField())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(administrator, { administrator = it }, enabled = !busy, colors = VisionGuardControlColors.checkbox()); Text("管理员权限")
                    }
                    Button({ submit {
                        store.request("/api/admin/accounts", "POST", JSONObject().put("username", username.trim()).put("password", password).put("isAdmin", administrator))
                        username = ""; password = ""; administrator = false; message = "账号已创建"
                    } }, enabled = !busy && username.trim().matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")) && password.length in 8..256, colors = VisionGuardControlColors.button(), shape = MaterialTheme.shapes.small) { Text("创建账号") }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("账号列表", style = MaterialTheme.typography.titleMedium)
                    if (loading) Text("正在读取账号…")
                    if (!loading && accounts.isEmpty()) TextButton({ submit { } }, enabled = !busy, colors = VisionGuardControlColors.textButton(), shape = MaterialTheme.shapes.small) { Text("重新读取") }
                    accounts.forEach { account ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(account.username + if (account.id == session.accountId) "（当前账号）" else "")
                                Text("${if (account.isAdmin) "管理员" else "普通账号"} · ${if (account.enabled) "已启用" else "已禁用"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton({ editing = account; resetPassword = ""; error = null; message = null }, enabled = !busy, colors = VisionGuardControlColors.textButton(), shape = MaterialTheme.shapes.small) { Text("管理") }
                        }
                    }
                    editing?.let { account ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text("管理 ${account.username}", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(account.enabled, { editing = account.copy(enabled = it) }, enabled = !busy, colors = VisionGuardControlColors.checkbox()); Text("启用账号") }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(account.isAdmin, { editing = account.copy(isAdmin = it) }, enabled = !busy, colors = VisionGuardControlColors.checkbox()); Text("管理员权限") }
                        OutlinedTextField(resetPassword, { resetPassword = it }, label = { Text("重置密码") }, supportingText = { Text("留空保留现有密码；新密码须为 8–256 个字符") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, colors = VisionGuardControlColors.outlinedField())
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button({ submit {
                                val body = JSONObject().put("isAdmin", account.isAdmin).put("enabled", account.enabled)
                                if (resetPassword.isNotEmpty()) body.put("password", resetPassword)
                                store.request("/api/admin/accounts/${account.id}", "PATCH", body)
                                editing = null; resetPassword = ""; message = "账号已更新"
                            } }, enabled = !busy && (resetPassword.isEmpty() || resetPassword.length in 8..256), colors = VisionGuardControlColors.button(), shape = MaterialTheme.shapes.small) { Text(if (busy) "保存中…" else "保存账号") }
                            TextButton({ editing = null; resetPassword = "" }, enabled = !busy, colors = VisionGuardControlColors.textButton(), shape = MaterialTheme.shapes.small) { Text("取消") }
                        }
                    }
                }
            }
        }
    }
}
