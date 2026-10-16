package com.xgwnje.visionguard.notifier.ui.dialogs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import com.xgwnje.visionguard.notifier.AlertQueueItem

/** An activity-sized overlay keeps cutouts, system bars and touch bounds in the same coordinate space. */
@Composable
fun AlarmDialog(item: AlertQueueItem, queuedCount: Int, confirming: Boolean, confirmationError: String?, onConfirm: (String) -> Unit) {
    val focus = LocalFocusManager.current
    LaunchedEffect(item.id) { focus.clearFocus(force = true) }
    BackHandler { }
    Box(Modifier.fillMaxSize().semantics { paneTitle = "当前报警" }.pointerInput(Unit) {
        // Become the top hit-test layer, without consuming the child confirmation button's gestures.
        awaitEachGesture { awaitFirstDown(requireUnconsumed = false) }
    }) {
        AlarmSurface(item, queuedCount, confirming, confirmationError, onConfirm)
    }
}
