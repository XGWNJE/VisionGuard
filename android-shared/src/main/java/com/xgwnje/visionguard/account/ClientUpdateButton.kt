package com.xgwnje.visionguard.account

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable fun ClientUpdateButton(version: String, client: String) {
    val context = LocalContext.current
    val updater = remember(version, client) { ClientUpdater(context, version, client) }
    val state by updater.state.collectAsState()
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(updater) { updater.check() }
    DisposableEffect(updater) { onDispose { updater.close() } }
    TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.update == null) "检查更新 · $version" else "新版本 ${state.update!!.version}") }
    if (open) AlertDialog(onDismissRequest = { if (!state.busy) open = false }, title = { Text("客户端更新") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.message.ifEmpty { "GitHub 稳定版 · 当前 $version" })
            state.update?.let { Text("${it.asset.name} · ${it.asset.size / 1024 / 1024} MiB") }
            if (state.downloading) { LinearProgressIndicator(progress = { (state.bytes.toFloat() / (state.update?.asset?.size ?: 1)).coerceIn(0f, 1f) }); Text("已下载 ${state.bytes / 1024} KiB") }
        } },
        confirmButton = { TextButton(enabled = !state.busy, onClick = { when { state.ready -> updater.install(); state.update != null -> updater.download(); else -> updater.check() } }, modifier = Modifier.heightIn(min = 48.dp)) { Text(when { state.ready -> "安装"; state.update != null -> "下载"; else -> "检查更新" }) } },
        dismissButton = { TextButton(onClick = { if (state.busy) updater.cancel() else open = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.busy) "取消" else "关闭") } })
}
