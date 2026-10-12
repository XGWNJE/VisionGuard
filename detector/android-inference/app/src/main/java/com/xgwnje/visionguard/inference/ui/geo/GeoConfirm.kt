package com.xgwnje.visionguard.inference.ui.geo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.icons.LucideIcons

/**
 * 危险闸门：删除、清空、退出、改密等操作的二次确认。
 * 取消默认聚焦，确认键独立错误语义，忙碌时由调用方禁用。
 */
@Composable
fun GeoConfirm(
    title: String,
    text: String,
    confirmLabel: String = "确认",
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    cancelEnabled: Boolean = true
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    // 记一层「有确认框开着」：根层 Escape 只在没有确认框时才关抽屉，否则一次 Escape 会连抽屉一起关。
    DisposableEffect(Unit) {
        GeoModal.confirmDepth += 1
        onDispose { GeoModal.confirmDepth -= 1 }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f))
        )
        Column(
            Modifier
                .widthIn(min = 360.dp, max = 520.dp)
                .background(MaterialTheme.colorScheme.surface, RectangleShape)
                .border(Geo.Border, MaterialTheme.colorScheme.outlineVariant, RectangleShape)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onCancel()
                        true
                    } else {
                        false
                    }
                }
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            GeoDivider()
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(Geo.Gap, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GeoKey(
                    onClick = onCancel,
                    label = "取消",
                    enabled = cancelEnabled,
                    modifier = Modifier.focusRequester(cancelFocus).focusable()
                )
                GeoKey(
                    onClick = onConfirm,
                    label = confirmLabel,
                    icon = LucideIcons.Check,
                    tone = GeoTone.Danger,
                    modifier = Modifier.focusable()
                )
            }
        }
    }
}
