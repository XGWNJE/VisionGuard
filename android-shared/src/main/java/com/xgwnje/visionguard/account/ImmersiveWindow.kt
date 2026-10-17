package com.xgwnje.visionguard.account

import android.view.Window
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

fun enterImmersiveMode(window: Window) {
    WindowCompat.getInsetsController(window, window.decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsetsCompat.Type.systemBars())
    }
}

/** Compose full-screen dialogs own a separate window and must opt in as well. */
@Composable
fun ImmersiveDialogWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as DialogWindowProvider).window
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) enterImmersiveMode(window) }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        enterImmersiveMode(window)
        onDispose { view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
    }
}
