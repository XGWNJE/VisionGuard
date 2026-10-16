package com.xgwnje.visionguard.notifier

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.xgwnje.visionguard.account.*
import com.xgwnje.visionguard.notifier.node.NotificationNodeService
import com.xgwnje.visionguard.notifier.node.NotificationNodeSettings
import com.xgwnje.visionguard.notifier.ui.dialogs.AlarmDialog
import com.xgwnje.visionguard.notifier.ui.main.NotificationDashboard
import com.xgwnje.visionguard.notifier.ui.settings.SettingsViewModel
import com.xgwnje.visionguard.notifier.ui.theme.NotificationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private var alarms: SharedPreferencesHelper? = null
    private var alertQueue by mutableStateOf<List<AlertQueueItem>>(emptyList())
    private val queueListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "alert_queue") runOnUiThread { syncAlarm() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 && !PermissionUtils.canPostNotifications(this))
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), PermissionUtils.REQUEST_CODE_POST_NOTIFICATIONS)
        val account = AccountStore.get(this)
        PreviewSignIn.configure(this, getString(R.string.preview_endpoint), getString(R.string.preview_username), getString(R.string.preview_password), getString(R.string.preview_channel))
        lifecycleScope.launch { PreviewSignIn.signIn(account, "android-notifier")?.let { android.widget.Toast.makeText(this@MainActivity, it, android.widget.Toast.LENGTH_LONG).show() } }
        setContent {
            val session by account.session.collectAsState()
            val appearance = rememberAppearance()
            val dark = appearance.dark()
            val updater = remember { ClientUpdater(this@MainActivity, "android-notifier") }
            LaunchedEffect(updater) { updater.check() }
            DisposableEffect(updater) { onDispose { updater.close() } }
            SideEffect { WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            } }
            NotificationTheme(darkTheme = dark) {
                if (session == null) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                            ClientUpdateButton("android-notifier", updater)
                            TextButton({ appearance.select(if (dark) Appearance.LIGHT else Appearance.DARK) }) { Text(if (dark) "浅色" else "深色") }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth().imePadding(), contentAlignment = Alignment.Center) {
                            Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(16.dp), shape = MaterialTheme.shapes.large,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                AccountLogin(account, "通知节点", "android-notifier", compact = true)
                            }
                        }
                    }
                } else key(session!!.scope) {
                    val model = remember { SettingsViewModel(application) }
                    val scope = rememberCoroutineScope()
                    var confirmingId by remember { mutableStateOf<String?>(null) }
                    var confirmationError by remember(alertQueue.firstOrNull()?.id) { mutableStateOf<String?>(null) }
                    DisposableEffect(session!!.scope) {
                        alarms = SharedPreferencesHelper(this@MainActivity)
                        alarms!!.prefs.registerOnSharedPreferenceChangeListener(queueListener)
                        syncAlarm()
                        if (NotificationNodeSettings(this@MainActivity).enabled) runCatching { NotificationNodeService.start(this@MainActivity) }
                        onDispose {
                            model.disposeAccount()
                            alarms?.prefs?.unregisterOnSharedPreferenceChangeListener(queueListener)
                            alarms = null
                            syncAlarm()
                        }
                    }
                    Box(Modifier.fillMaxSize().safeDrawingPadding().then(if (alertQueue.isNotEmpty()) Modifier.clearAndSetSemantics { } else Modifier)) {
                        NotificationDashboard(account, session!!, appearance, updater, model) { stopAccount() }
                    }
                    alertQueue.firstOrNull()?.let { item ->
                        AlarmDialog(item, (alertQueue.size - 1).coerceAtLeast(0), confirmingId != null, confirmationError) { expectedId ->
                            if (confirmingId == null && alertQueue.firstOrNull()?.id == expectedId) {
                                confirmingId = expectedId
                                confirmationError = null
                                val storage = alarms
                                scope.launch {
                                    val success = withContext(Dispatchers.IO) { runCatching { storage?.finishActiveAlert(expectedId, AlertEndType.MANUAL)?.success == true }.getOrDefault(false) }
                                    syncAlarm()
                                    if (!success && alertQueue.firstOrNull()?.id == expectedId) confirmationError = "确认保存失败，请重试"
                                    confirmingId = null
                                }
                            }
                        }
                    }
                }
            }
            LaunchedEffect(session?.scope) {
                if (session == null) {
                    stopService(Intent(this@MainActivity, NotificationNodeService::class.java))
                    stopService(Intent(this@MainActivity, AlarmPlaybackService::class.java))
                }
            }
        }
    }

    private fun stopAccount() {
        stopService(Intent(this, NotificationNodeService::class.java))
        stopService(Intent(this, AlarmPlaybackService::class.java))
        alarms?.clearAccountData()
        NotificationNodeSettings(this).clearAccountData()
        RingtoneLibrary.stopPreview()
        RingtoneLibrary.cancelRecording()
        alertQueue = emptyList()
    }

    private fun syncAlarm() {
        alertQueue = alarms?.getAlertQueue() ?: emptyList()
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(alertQueue.isNotEmpty())
            setTurnScreenOn(alertQueue.isNotEmpty())
        } else {
            @Suppress("DEPRECATION")
            val flags = android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (alertQueue.isNotEmpty()) window.addFlags(flags) else window.clearFlags(flags)
        }
    }
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); setIntent(intent); syncAlarm() }
    override fun onResume() { super.onResume(); syncAlarm() }
}
