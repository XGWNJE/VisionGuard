package com.xgwnje.visionguard.notifier.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.xgwnje.visionguard.notifier.node.NotificationNodeService
import com.xgwnje.visionguard.notifier.node.NotificationNodeSettings

@Composable
fun rememberAlarmTimeZone(): String? {
    val context = LocalContext.current
    val settings = remember(context) { NotificationNodeSettings(context) }
    val state by NotificationNodeService.state.collectAsState()
    return state.timeZone ?: settings.timeZone
}
