package com.xgwnje.visionguard.account

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

enum class Appearance(val title: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色");
    fun dark(systemDark: Boolean?) = when (this) { SYSTEM -> systemDark ?: false; LIGHT -> false; DARK -> true }
    companion object { fun read(value: String?) = entries.find { it.name == value } ?: SYSTEM }
}
class AppearancePreference(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    var mode by mutableStateOf(Appearance.read(prefs.getString("mode", null))); private set
    var error by mutableStateOf(""); private set
    fun select(value: Appearance) {
        if (prefs.edit().putString("mode", value.name).commit()) { mode = value; error = "" }
        else error = "外观保存失败，请重试"
    }
}
@Composable fun rememberAppearance(): AppearancePreference {
    val context = LocalContext.current.applicationContext
    return remember(context) { AppearancePreference(context) }
}
@Composable fun AppearancePreference.dark() = mode.dark(isSystemInDarkTheme())
@Composable fun AppearanceSelector(preference: AppearancePreference) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Appearance.entries.forEach { value ->
                FilterChip(selected = preference.mode == value, onClick = { preference.select(value) },
                    label = { Text(value.title, maxLines = 1) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
            }
        }
        if (preference.error.isNotEmpty()) Text(preference.error, color = MaterialTheme.colorScheme.error)
    }
}
