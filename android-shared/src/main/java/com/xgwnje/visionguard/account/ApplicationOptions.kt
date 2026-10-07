package com.xgwnje.visionguard.account
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.icons.LucideIcons

@Composable fun ApplicationOptions(preference: AppearancePreference, version: String, client: String, modifier: Modifier = Modifier.fillMaxWidth(), compact: Boolean = false) {
    val context = LocalContext.current
    val updater = remember(version, client) { ClientUpdater(context, version, client) }
    val state by updater.state.collectAsState()
    LaunchedEffect(updater) { updater.check() }
    DisposableEffect(updater) { onDispose { updater.close() } }
    var open by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
        if (compact) IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) {
            BadgedBox(badge = { if (state.update != null) Badge() }) {
                Icon(LucideIcons.SlidersHorizontal, if (state.update == null) "设置" else "设置 · 新版本", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(LucideIcons.SlidersHorizontal, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(if (state.update == null) "设置" else "设置 · 新版本") }
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("应用设置") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) { Text("外观", style = MaterialTheme.typography.titleMedium); AppearanceSelector(preference); HorizontalDivider(); ClientUpdateButton(version, client, updater) } }, confirmButton = { TextButton({ open = false }, Modifier.heightIn(min = 48.dp)) { Text("完成") } })
}
