package com.xgwnje.visionguard.receiver.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.xgwnje.visionguard.account.VisionGuardTheme

@Composable
fun VisionGuardReceiverTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    VisionGuardTheme(darkTheme, content)
}
