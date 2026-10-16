package com.xgwnje.visionguard.notifier.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.xgwnje.visionguard.account.VisionGuardTheme
import com.xgwnje.visionguard.account.VisionGuardThemeStyle

@Composable
fun NotificationTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    VisionGuardTheme(darkTheme, VisionGuardThemeStyle.CONSOLE, content)
}
