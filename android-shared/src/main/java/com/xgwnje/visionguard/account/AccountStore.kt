package com.xgwnje.visionguard.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xgwnje.visionguard.icons.LucideIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AccountSession(val endpoint: String, val token: String, val expiresAt: String,
    val accountId: String, val username: String, val deviceId: String, val deviceName: String,
    val component: String, val isAdmin: Boolean = false) {
    val webSocketUrl: String get() = endpoint.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws"
    val mediaUrl: String get() = endpoint.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/media/ws"
    val scope: String get() = "$endpoint|$accountId|$deviceId"
}

private class AccountHttpException(val status: Int, message: String) : IllegalStateException(message)

/** The account UI is shared by Compose 1.6 and 1.10 clients. */
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION")
private fun Modifier.accountAutofill(type: AutofillType, onFill: (String) -> Unit): Modifier = composed {
    val autofill = LocalAutofill.current
    val tree = LocalAutofillTree.current
    val latestOnFill by rememberUpdatedState(onFill)
    val node = remember(type) { AutofillNode(autofillTypes = listOf(type), onFill = { latestOnFill(it) }) }
    DisposableEffect(node, tree, autofill) {
        tree += node
        onDispose { autofill?.cancelAutofillForNode(node); tree.children.remove(node.id) }
    }
    onGloballyPositioned { node.boundingBox = it.boundsInWindow() }.onFocusChanged {
        if (it.isFocused) autofill?.requestAutofillForNode(node) else autofill?.cancelAutofillForNode(node)
    }
}

/** Saved sessions and opt-in login fields use Android Keystore; app backups exclude both. */
class AccountStore private constructor(private val prefs: android.content.SharedPreferences, val allowsTestEndpoint: Boolean, private val stableIdentity: () -> String) {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val lock = Mutex()
    private val mutableSession = MutableStateFlow(readSaved())
    val session = mutableSession.asStateFlow()
    val savedEndpoint: String get() = if (allowsTestEndpoint) prefs.getString("endpoint", DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT else DEFAULT_ENDPOINT
    private fun deviceKey(endpoint: String, username: String, component: String) = "device:$endpoint:${username.trim().lowercase(Locale.ROOT)}:$component"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(text: String, associatedData: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun decrypt(encrypted: String, associatedData: String): String {
        val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
        require(bytes.size > 12 && bytes.size < 16_384)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
    private fun rememberedKey(endpoint: String, component: String): String {
        val base = if (allowsTestEndpoint) normalizeEndpoint(endpoint) else DEFAULT_ENDPOINT
        require(component == "android-camera" || component == "android-notifier")
        return "remembered:$base:$component"
    }
    @Synchronized fun rememberedLogin(endpoint: String, component: String): RememberedLogin? = runCatching {
        val name = rememberedKey(endpoint, component)
        val encrypted = prefs.getString(name, null) ?: return null
        require(encrypted.length <= 24_000)
        val value = JSONObject(decrypt(encrypted, name))
        RememberedLogin(value.getString("username"), value.getString("password"))
    }.getOrNull()
    @Synchronized fun rememberLogin(endpoint: String, component: String, value: RememberedLogin?) {
        val name = rememberedKey(endpoint, component)
        val edit = prefs.edit()
        if (value == null) edit.remove(name)
        else edit.putString(name, encrypt(JSONObject().put("username", value.username).put("password", value.password).toString(), name))
        check(edit.commit()) { "无法保存或清除记住的账号密码" }
    }
    private fun readSaved(): AccountSession? = runCatching {
        if (!allowsTestEndpoint && prefs.getString("endpoint", DEFAULT_ENDPOINT) != DEFAULT_ENDPOINT) return null
        val encrypted = prefs.getString("encrypted", null) ?: return null
        val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        parse(JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)), savedEndpoint)
    }.getOrNull()
    private fun parse(body: JSONObject, endpoint: String): AccountSession {
        val account = body.getJSONObject("account")
        val device = body.getJSONObject("device")
        return AccountSession(endpoint, body.getString("token"), body.getString("expiresAt"),
            account.getString("accountId"), account.getString("username"),
            device.getString("deviceId"), device.getString("deviceName"), device.getString("component"), account.optBoolean("isAdmin"))
    }
    @Synchronized private fun save(body: JSONObject, endpoint: String): AccountSession {
        val value = parse(body, endpoint)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(body.toString().toByteArray(Charsets.UTF_8))
        val idKey = deviceKey(endpoint, value.username, value.component)
        check(prefs.edit().putString("endpoint", endpoint).putString("encrypted", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .putString(idKey, value.deviceId).putString(idKey + ":name", value.deviceName).commit())
        mutableSession.value = value
        return value
    }
    suspend fun login(endpoint: String, username: String, password: String, component: String, rememberCredentials: Boolean = false): AccountSession =
        lock.withLock { withContext(Dispatchers.IO) {
            val base = if (allowsTestEndpoint) normalizeEndpoint(endpoint) else DEFAULT_ENDPOINT
            val user = username.trim().lowercase(Locale.ROOT)
            require(user.isNotBlank() && password.isNotBlank()) { "请输入账号和密码" }
            val credentials = RememberedLogin(user, password)
            val idKey = deviceKey(base, user, component)
            val existingId = prefs.getString(idKey, null)
            val identity = stableIdentity()
            val body = JSONObject().put("username", user).put("password", password).put("component", component)
                .put("deviceIdentity", identity).put("deviceModel", DeviceIdentityPolicy.model(android.os.Build.MODEL))
            if (existingId != null) body.put("deviceId", existingId)
            val response = try { call(base, "/api/account/login", "POST", body) }
                catch (e: AccountHttpException) {
                    if (e.status != 403 || existingId == null) throw e
                    check(prefs.edit().remove(idKey).commit())
                    body.remove("deviceId")
                    call(base, "/api/account/login", "POST", body)
                }
            rememberLogin(base, component, if (rememberCredentials) credentials else null)
            save(response, base)
        } }
    suspend fun ensureSession(): AccountSession? = lock.withLock { withContext(Dispatchers.IO) {
        val current = mutableSession.value ?: return@withContext null
        if (runCatching { Instant.parse(current.expiresAt).toEpochMilli() > System.currentTimeMillis() + 60_000 }.getOrDefault(false)) return@withContext current
        runCatching { save(call(current.endpoint, "/api/account/refresh", "POST", JSONObject(), current.token), current.endpoint) }
            .getOrElse { mutableSession.value }
    } }
    suspend fun request(path: String, method: String = "GET", body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val current = ensureSession() ?: error("登录已失效，请重新登录")
        call(current.endpoint, path, method, body, current.token)
    }
    suspend fun logout() = lock.withLock { withContext(Dispatchers.IO) {
        mutableSession.value?.let { runCatching { call(it.endpoint, "/api/account/logout", "POST", JSONObject(), it.token) } }
        clear()
    } }
    suspend fun changePassword(currentPassword: String, newPassword: String) {
        val current = mutableSession.value
        request("/api/account/password", "POST", JSONObject().put("currentPassword", currentPassword).put("newPassword", newPassword))
        if (current != null) withContext(Dispatchers.IO) { rememberLogin(current.endpoint, current.component, null) }
    }
    suspend fun refreshIdentity() = lock.withLock { withContext(Dispatchers.IO) {
        val current = mutableSession.value ?: return@withContext
        val body = call(current.endpoint, "/api/account/session", "GET", null, current.token).put("token", current.token)
        saveIfCurrent(body, current)
    } }
    suspend fun renameDevice(name: String) = lock.withLock { withContext(Dispatchers.IO) {
        val current = mutableSession.value ?: error("登录已失效，请重新登录")
        val trimmed = DisplayNamePolicy.normalize(name)
        call(current.endpoint, "/api/devices/${current.deviceId}", "PATCH", JSONObject().put("deviceName", trimmed), current.token)
        val body = call(current.endpoint, "/api/account/session", "GET", null, current.token).put("token", current.token)
        saveIfCurrent(body, current)
    } }
    @Synchronized private fun saveIfCurrent(body: JSONObject, expected: AccountSession) {
        if (mutableSession.value?.token == expected.token && mutableSession.value?.scope == expected.scope) save(body, expected.endpoint)
    }
    suspend fun updateDevice(device: JSONObject) = lock.withLock { withContext(Dispatchers.IO) {
        val current = mutableSession.value ?: return@withContext
        if (device.optString("deviceId") != current.deviceId) return@withContext
        val name = device.optString("deviceName").takeIf { it.isNotBlank() } ?: return@withContext
        if (name == current.deviceName) return@withContext
        save(JSONObject().put("token", current.token).put("expiresAt", current.expiresAt)
            .put("account", JSONObject().put("accountId", current.accountId).put("username", current.username).put("isAdmin", current.isAdmin))
            .put("device", JSONObject().put("deviceId", current.deviceId).put("deviceName", name).put("component", current.component)), current.endpoint)
    } }
    @Synchronized fun clear() { check(prefs.edit().remove("encrypted").commit()); mutableSession.value = null }
    @Synchronized private fun clearIfCurrent(endpoint: String, token: String) {
        val current = mutableSession.value ?: return
        if (current.endpoint == endpoint && current.token == token) clear()
    }
    private fun call(endpoint: String, path: String, method: String, body: JSONObject?, token: String? = null): JSONObject {
        require(path.startsWith("/api/") && !path.contains(".."))
        val builder = Request.Builder().url(endpoint + path)
        if (token != null) builder.header("Authorization", "Bearer $token")
        builder.method(method, if (method == "GET") null else (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
        return http.newCall(builder.build()).execute().use { response ->
            if (response.code == 401 && token != null && path != "/api/account/password") clearIfCurrent(endpoint, token)
            if (!response.isSuccessful) throw AccountHttpException(response.code, when (response.code) {
                401 -> if (path == "/api/account/password") "当前密码不正确或登录已失效" else "账号、密码错误或登录已失效"
                429 -> "请求过于频繁，请稍后重试"
                403 -> "没有权限执行此操作"
                409 -> "账号已存在，或必须保留至少一个启用的管理员"
                else -> "服务暂时不可用（${response.code}）"
            })
            val result = JSONObject(response.body?.string() ?: error("服务返回空响应"))
            check(result.optBoolean("ok", true)) { "服务拒绝了请求" }
            result
        }
    }
    companion object {
        fun current(): AccountSession? = instance?.session?.value
        const val DEFAULT_ENDPOINT = "https://visionguard.xgwnje.cn"
        fun cacheKey(context: Context): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest((get(context).session.value?.scope ?: "signed-out").toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }
        private const val KEY_ALIAS = "visionguard-account-session-v1"
        @Volatile private var instance: AccountStore? = null
        fun get(context: Context): AccountStore = instance ?: synchronized(this) { instance ?: AccountStore(context.applicationContext.getSharedPreferences("account_session", Context.MODE_PRIVATE), context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            val app = context.applicationContext
            DeviceIdentityPolicy.identity(android.provider.Settings.Secure.getString(app.contentResolver, android.provider.Settings.Secure.ANDROID_ID), app.packageName)
        }.also { instance = it } }
        fun normalizeEndpoint(value: String): String {
            val base = value.trim().trimEnd('/')
            val uri = URI(base)
            require(uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.isNullOrEmpty()) { "服务地址不能带路径、账号或查询参数" }
            val host = uri.host ?: error("服务地址无效")
            val octets = host.split('.').mapNotNull { it.toIntOrNull() }
            val local = host in setOf("localhost", "127.0.0.1", "10.0.2.2", "[::1]", "::1") ||
                (host.split('.').size == 4 && octets.size == 4 && octets.all { it in 0..255 } && (octets[0] == 10 || octets[0] == 192 && octets[1] == 168 || octets[0] == 172 && octets[1] in 16..31))
            require(uri.scheme == "https" || uri.scheme == "http" && local) { "公网服务必须使用 HTTPS；HTTP 仅用于局域网测试" }
            return base
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION")
@Composable
fun AccountLogin(store: AccountStore, title: String, component: String, beforeLogin: suspend () -> Unit = {}) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf(store.savedEndpoint) }
    var advanced by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var rememberCredentials by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store, endpoint, component) {
        restoring = true
        val saved = withContext(Dispatchers.IO) { store.rememberedLogin(endpoint, component) }
        username = saved?.username ?: ""; password = saved?.password ?: ""; rememberCredentials = saved != null
        restoring = false
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp).wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 480.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text("登录同一账号，自动关联这套系统中的设备。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(username, { if (it.length <= 64) username = it else message = "账号最多 64 个字符" }, label = { Text("账号") }, singleLine = true, modifier = Modifier.fillMaxWidth().accountAutofill(AutofillType.Username) { if (!busy && !restoring && it.length <= 64) username = it }, enabled = !busy && !restoring, shape = MaterialTheme.shapes.small, colors = VisionGuardControlColors.outlinedField(focusedLabelColor = VisionGuardStatusColors.onSuccessContainer), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
        OutlinedTextField(password, { if (it.length <= 256) password = it else message = "密码最多 256 个字符" }, label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth().accountAutofill(AutofillType.Password) { if (!busy && !restoring && it.length <= 256) password = it }, enabled = !busy && !restoring, shape = MaterialTheme.shapes.small, colors = VisionGuardControlColors.outlinedField(focusedLabelColor = VisionGuardStatusColors.onSuccessContainer), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next))
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(rememberCredentials, enabled = !busy && !restoring, role = Role.Checkbox) { checked ->
            if (checked) rememberCredentials = true else {
                busy = true; message = null
                scope.launchAccount {
                    try { withContext(Dispatchers.IO) { store.rememberLogin(endpoint, component, null) }; rememberCredentials = false }
                    catch (_: Exception) { message = "无法清除已记住的账号密码，请检查本机存储" }
                    finally { busy = false }
                }
            }
        }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(rememberCredentials, onCheckedChange = null, enabled = !busy && !restoring, colors = VisionGuardControlColors.checkbox())
            Spacer(Modifier.width(8.dp)); Text("记住账号密码")
        }
        if (store.allowsTestEndpoint) {
            TextButton({ advanced = !advanced }, enabled = !busy, shape = MaterialTheme.shapes.small, colors = VisionGuardControlColors.textButton(contentColor = VisionGuardStatusColors.onSuccessContainer)) { Text(if (advanced) "收起测试设置" else "隔离测试设置") }
            if (advanced) OutlinedTextField(endpoint, { endpoint = it }, label = { Text("隔离服务地址") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy, shape = MaterialTheme.shapes.small, colors = VisionGuardControlColors.outlinedField(focusedLabelColor = VisionGuardStatusColors.onSuccessContainer), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            busy = true; message = null
            scope.launchAccount {
                try { beforeLogin(); store.login(endpoint, username, password, component, rememberCredentials); password = "" }
                catch (e: Exception) { message = e.message?.take(160) ?: "无法登录，请检查网络" }
                finally { busy = false }
            }
        }, enabled = !busy && !restoring && username.isNotBlank() && password.isNotBlank(), colors = VisionGuardControlColors.button(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
            Text(if (busy) "正在登录…" else "登录")
        }
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchAccount(block: suspend () -> Unit) = launch { block() }

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION")
@Composable
fun AccountHeader(store: AccountStore, session: AccountSession, actions: @Composable () -> Unit = {}, compact: Boolean = false, beforeLogout: suspend () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deviceName by remember(session.deviceName) { mutableStateOf(session.deviceName) }
    LaunchedEffect(menuOpen, session.token) {
        if (menuOpen) runCatching { store.refreshIdentity() }
    }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = if (compact) 12.dp else 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 12.dp)) {
            Text(session.deviceName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
            Box {
                if (compact) IconButton({ menuOpen = true }, Modifier.size(48.dp)) {
                    Icon(LucideIcons.UserRound, "账号", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                } else TextButton({ menuOpen = true }, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small, colors = VisionGuardControlColors.textButton(contentColor = VisionGuardStatusColors.onSuccessContainer)) { Text("账号") }
            }
        }
    }
    if (menuOpen) AlertDialog(
        onDismissRequest = { menuOpen = false }, shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("账号", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(session.username, style = MaterialTheme.typography.titleSmall)
                Text(session.deviceName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TextButton({ menuOpen = false; error = null; renaming = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small, colors = VisionGuardControlColors.textButton(contentColor = VisionGuardStatusColors.onSuccessContainer)) { Text("本机名称") }
                TextButton({ menuOpen = false; oldPassword = ""; newPassword = ""; error = null; editing = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small, colors = VisionGuardControlColors.textButton(contentColor = VisionGuardStatusColors.onSuccessContainer)) { Text("修改密码") }
                TextButton({ menuOpen = false; scope.launchAccount { beforeLogout(); store.logout() } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small, colors = VisionGuardControlColors.textButton(contentColor = VisionGuardStatusColors.onSuccessContainer)) { Text("退出") }
            }
        },
        confirmButton = { TextButton({ menuOpen = false }, shape = MaterialTheme.shapes.small, colors = VisionGuardControlColors.textButton(contentColor = VisionGuardStatusColors.onSuccessContainer)) { Text("关闭") } }
    )
    if (renaming) AlertDialog(
        onDismissRequest = { if (!busy) renaming = false }, shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("本机名称", style = MaterialTheme.typography.titleMedium) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(deviceName, { if (DisplayNamePolicy.acceptsDraft(it)) { deviceName = it; error = null } else error = DisplayNamePolicy.HINT }, label = { Text("设备名称") }, supportingText = { Text("最多 ${DisplayNamePolicy.MAX_LENGTH} 个字符") }, isError = DisplayNamePolicy.error(deviceName) != null, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, colors = VisionGuardControlColors.outlinedField())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton({ busy = true; error = null; scope.launchAccount {
            try { store.renameDevice(deviceName); renaming = false }
            catch (e: Exception) { error = e.message?.take(160) ?: "名称保存失败" }
            finally { busy = false }
        } }, enabled = !busy && error == null && DisplayNamePolicy.error(deviceName) == null, shape = MaterialTheme.shapes.small, colors = VisionGuardControlColors.textButton()) { Text(if (busy) "保存中…" else "保存") } },
        dismissButton = { TextButton({ renaming = false; deviceName = session.deviceName }, enabled = !busy, shape = MaterialTheme.shapes.small, colors = VisionGuardControlColors.textButton()) { Text("取消") } }
    )
    if (editing) Dialog(
        onDismissRequest = { if (!busy) editing = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                tonalElevation = 0.dp
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("修改账号密码", style = MaterialTheme.typography.titleMedium)
                    Text("修改后所有设备需要重新登录。")
                    OutlinedTextField(oldPassword, { oldPassword = it }, label = { Text("当前密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().accountAutofill(AutofillType.Password) { if (!busy) oldPassword = it }, shape = MaterialTheme.shapes.small, colors = VisionGuardControlColors.outlinedField(focusedLabelColor = VisionGuardStatusColors.onSuccessContainer), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next))
                    OutlinedTextField(newPassword, { newPassword = it }, label = { Text("新密码") }, supportingText = { Text("至少 8 个字符") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().accountAutofill(AutofillType.NewPassword) { if (!busy) newPassword = it }, shape = MaterialTheme.shapes.small, colors = VisionGuardControlColors.outlinedField(focusedLabelColor = VisionGuardStatusColors.onSuccessContainer), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done))
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton({ editing = false }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small, colors = VisionGuardControlColors.textButton()) { Text("取消") }
                        TextButton({
                            busy = true; error = null
                            scope.launchAccount {
                                try { store.changePassword(oldPassword, newPassword); beforeLogout(); store.clear(); editing = false; oldPassword = ""; newPassword = "" }
                                catch (e: Exception) { error = e.message }
                                finally { busy = false }
                            }
                        }, enabled = !busy && oldPassword.isNotBlank() && newPassword.length >= 8, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small, colors = VisionGuardControlColors.textButton()) { Text(if (busy) "保存中…" else "保存") }
                    }
                }
            }
        }
    }
}
