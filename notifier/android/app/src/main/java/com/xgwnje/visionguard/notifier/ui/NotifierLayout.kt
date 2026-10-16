package com.xgwnje.visionguard.notifier.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.icons.LucideIcons
import kotlinx.coroutines.launch

@Composable
internal fun NotifierSection(title: String, action: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    NotifierPanel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            action()
        }
        content()
    }
}

internal data class NotifierSectionSpec(val title: String, val content: @Composable () -> Unit)

@Composable
internal fun NotifierLongPage(sections: List<NotifierSectionSpec>) {
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val currentSection by remember { derivedStateOf { scroll.firstVisibleItemIndex } }
    Row(Modifier.fillMaxSize().imePadding(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LazyColumn(Modifier.width(112.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            items(sections.size) { index ->
                val selected = currentSection == index
                TextButton({ scope.launch { scroll.animateScrollToItem(index) } },
                    Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.textButtonColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onSurface)) { Text(sections[index].title) }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = scroll,
            contentPadding = PaddingValues(top = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(sections.size, key = { sections[it].title }) { index -> sections[index].content() }
        }
    }
}

@Composable
internal fun NotifierSelect(label: String, value: String, options: List<String>, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton({ expanded = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled,
                shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                Text(value, Modifier.weight(1f))
                Icon(LucideIcons.ChevronDown, null, Modifier.padding(start = 8.dp).size(16.dp))
            }
            DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 300.dp)) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onSelect(index) })
                }
            }
        }
    }
}

@Composable
internal fun NotifierMessage(message: String?, error: Boolean = false) {
    if (!message.isNullOrBlank()) Text(message, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun NotifierField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun NotifierTableHeader() {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("事件" to 1.1f, "来源" to 1.5f, "时间" to 1.4f, "结果" to 1f).forEach { (text, weight) ->
            Text(text, Modifier.weight(weight), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
