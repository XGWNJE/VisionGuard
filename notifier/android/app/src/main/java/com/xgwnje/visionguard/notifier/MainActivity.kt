package com.xgwnje.visionguard.notifier

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.xgwnje.visionguard.notifier.node.NotificationNodeService
import com.xgwnje.visionguard.notifier.node.NotificationNodeSettings
import com.xgwnje.visionguard.notifier.ui.dialogs.AlarmDialog
import com.xgwnje.visionguard.notifier.ui.history.AlertHistoryScreen
import com.xgwnje.visionguard.notifier.ui.main.NotificationDashboard
import com.xgwnje.visionguard.notifier.ui.settings.RingtoneLibraryScreen
import com.xgwnje.visionguard.notifier.ui.settings.SettingsViewModel
import com.xgwnje.visionguard.notifier.ui.settings.SettingsViewModelFactory
import com.xgwnje.visionguard.notifier.ui.theme.NotificationTheme

class MainActivity : AppCompatActivity() {
    private val settingsViewModel: SettingsViewModel by viewModels { SettingsViewModelFactory(application) }
    private lateinit var alarms: SharedPreferencesHelper
    private var activeAlert by mutableStateOf<AlertQueueItem?>(null)
    private val queueListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "alert_queue") runOnUiThread { syncAlarm() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alarms = SharedPreferencesHelper(this)
        alarms.prefs.registerOnSharedPreferenceChangeListener(queueListener)
        requestNotifications()
        if (NotificationNodeSettings(this).enabled) runCatching { NotificationNodeService.start(this) }
            .onFailure { Toast.makeText(this, "请在接警页重新连接通知节点", Toast.LENGTH_LONG).show() }
        syncAlarm()
        if (activeAlert != null) runCatching { androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, AlarmPlaybackService::class.java)) }
        setContent {
            NotificationTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "node", modifier = Modifier.fillMaxSize()) {
                    composable("node") { NotificationDashboard(settingsViewModel, onHistory = { nav.navigate("history") }, onLibrary = { nav.navigate("ringtones") }) }
                    composable("history") { AlertHistoryScreen(onNavigateBack = { nav.popBackStack() }, viewModel = settingsViewModel) }
                    composable("ringtones") { RingtoneLibraryScreen(onNavigateBack = { nav.popBackStack() }, viewModel = settingsViewModel) }
                }
                activeAlert?.let { item ->
                    AlarmDialog(onDismissRequest = {}, onConfirm = {
                        if (!alarms.finishActiveAlert(item.id, AlertEndType.MANUAL).success)
                            Toast.makeText(this, "确认保存失败，请重试", Toast.LENGTH_LONG).show()
                        syncAlarm()
                    }, matchedKeyword = item.keyword, sourceApp = item.sourceApp, snippet = item.snippet, eventTimeMillis = item.firstTriggeredAt)
                }
            }
        }
    }
    private fun syncAlarm() {
        activeAlert = alarms.getActiveAlert()
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(activeAlert != null)
            setTurnScreenOn(activeAlert != null)
        } else {
            @Suppress("DEPRECATION")
            val flags = android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (activeAlert != null) window.addFlags(flags) else window.clearFlags(flags)
        }
    }
    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !PermissionUtils.canPostNotifications(this))
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), PermissionUtils.REQUEST_CODE_POST_NOTIFICATIONS)
    }
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); setIntent(intent); syncAlarm() }
    override fun onResume() { super.onResume(); if (::alarms.isInitialized) syncAlarm() }
    override fun onDestroy() {
        if (::alarms.isInitialized) alarms.prefs.unregisterOnSharedPreferenceChangeListener(queueListener)
        super.onDestroy()
    }
}
