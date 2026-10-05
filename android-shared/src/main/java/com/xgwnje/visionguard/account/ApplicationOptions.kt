package com.xgwnje.visionguard.account
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.icons.LucideIcons

@Composable fun ApplicationOptions(preference: AppearancePreference, version: String, client: String) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(LucideIcons.SlidersHorizontal, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("外观 · ${preference.mode.title}") }
        ClientUpdateButton(version, client)
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("外观") }, text = { AppearanceSelector(preference) }, confirmButton = { TextButton({ open = false }, Modifier.heightIn(min = 48.dp)) { Text("完成") } })
}
