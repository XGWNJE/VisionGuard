package com.xgwnje.visionguard.notifier

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.xgwnje.visionguard.account.*
import com.xgwnje.visionguard.notifier.node.NotificationNodeService
import com.xgwnje.visionguard.notifier.node.NotificationNodeSettings
import com.xgwnje.visionguard.notifier.ui.dialogs.AlarmDialog
import com.xgwnje.visionguard.notifier.ui.history.AlertHistoryScreen
import com.xgwnje.visionguard.notifier.ui.main.NotificationDashboard
import com.xgwnje.visionguard.notifier.ui.main.NotificationHelpButton
import com.xgwnje.visionguard.notifier.ui.settings.RingtoneLibraryScreen
import com.xgwnje.visionguard.notifier.ui.settings.SettingsViewModel
import com.xgwnje.visionguard.notifier.ui.theme.NotificationTheme

class MainActivity : AppCompatActivity() {
    private var alarms: SharedPreferencesHelper? = null
    private var activeAlert by mutableStateOf<AlertQueueItem?>(null)
    private val queueListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "alert_queue") runOnUiThread { syncAlarm() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 && !PermissionUtils.canPostNotifications(this))
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), PermissionUtils.REQUEST_CODE_POST_NOTIFICATIONS)
        val account = AccountStore.get(this)
        setContent {
            val session by account.session.collectAsState()
            val appearance = rememberAppearance()
            val dark = appearance.dark()
            SideEffect { WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            } }
            NotificationTheme(darkTheme = dark) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                if (session == null) ApplicationOptions(appearance, BuildConfig.VERSION_NAME, "android-notifier")
                Box(Modifier.weight(1f)) {
                if (session == null) AccountLogin(account, "通知节点", "android-notifier")
                else key(session!!.scope) {
                    val model = remember { SettingsViewModel(application) }
                    var confirmationError by remember(activeAlert?.id) { mutableStateOf<String?>(null) }
                    DisposableEffect(session!!.scope) {
                        alarms = SharedPreferencesHelper(this@MainActivity)
                        alarms!!.prefs.registerOnSharedPreferenceChangeListener(queueListener)
                        syncAlarm()
                        if (NotificationNodeSettings(this@MainActivity).enabled) runCatching { NotificationNodeService.start(this@MainActivity) }
                        onDispose {
                            model.disposeAccount()
                            alarms?.prefs?.unregisterOnSharedPreferenceChangeListener(queueListener)
                            alarms = null; activeAlert = null
                            syncAlarm()
                        }
                    }
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        AccountHeader(account, session!!, actions = {
                            Row {
                                NotificationHelpButton()
                                ApplicationOptions(appearance, BuildConfig.VERSION_NAME, "android-notifier", Modifier, compact = true)
                            }
                        }, compact = true, beforeLogout = { stopAccount() })
                        val nav = rememberNavController()
                        NavHost(navController = nav, startDestination = "node", modifier = Modifier.weight(1f)) {
                            composable("node") { NotificationDashboard(model, onHistory = { nav.navigate("history") }, onLibrary = { nav.navigate("ringtones") }) }
                            composable("history") { AlertHistoryScreen(onNavigateBack = { nav.popBackStack() }, viewModel = model) }
                            composable("ringtones") { RingtoneLibraryScreen(onNavigateBack = { nav.popBackStack() }, viewModel = model) }
                        }
                    }
                    activeAlert?.let { item ->
                        AlarmDialog(onDismissRequest = {}, onConfirm = {
                            confirmationError = null
                            if (alarms?.finishActiveAlert(item.id, AlertEndType.MANUAL)?.success != true)
                                confirmationError = "确认保存失败，请重试"
                            syncAlarm()
                        }, matchedKeyword = item.keyword, sourceApp = item.sourceApp, snippet = item.snippet, eventTimeMillis = item.firstTriggeredAt, confirmationError = confirmationError)
                    }
                }
                }
                }
            }
            LaunchedEffect(session?.scope) { if (session == null) { stopService(Intent(this@MainActivity, NotificationNodeService::class.java)); stopService(Intent(this@MainActivity, AlarmPlaybackService::class.java)) } }
        }
    }
    private fun stopAccount() {
        stopService(Intent(this, NotificationNodeService::class.java))
        stopService(Intent(this, AlarmPlaybackService::class.java))
        alarms?.clearAccountData()
        NotificationNodeSettings(this).clearAccountData()
        RingtoneLibrary.stopPreview()
        activeAlert = null
    }
    private fun syncAlarm() {
        activeAlert = alarms?.getActiveAlert()
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(activeAlert != null); setTurnScreenOn(activeAlert != null) }
        else {
            @Suppress("DEPRECATION")
            val flags = android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (activeAlert != null) window.addFlags(flags) else window.clearFlags(flags)
        }
    }
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); setIntent(intent); syncAlarm() }
    override fun onResume() { super.onResume(); syncAlarm() }
}
