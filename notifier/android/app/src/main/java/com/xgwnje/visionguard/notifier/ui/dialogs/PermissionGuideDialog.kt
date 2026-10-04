package com.xgwnje.visionguard.notifier.ui.dialogs

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.notifier.ui.NotifierDialog

@Composable
fun PermissionGuideDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "前往设置"
) {
    NotifierDialog(
        title = title,
        onDismiss = onDismiss,
        confirmButton = {
            TextButton({ onConfirm(); onDismiss() }, Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text(confirmText) }
        },
        dismissButton = { TextButton(onDismiss, Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("取消") } }
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
    }
}
