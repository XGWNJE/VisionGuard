package com.xgwnje.visionguard.inference.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.xgwnje.visionguard.account.VisionGuardTheme

/** 推理节点不另建页面主题，只转发共享的 Material 主题。 */
@Composable
fun VisionguardTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    VisionGuardTheme(darkTheme, content)
}
