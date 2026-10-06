package com.xgwnje.visionguard.account

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.unit.dp

/** Shared phone/tablet composition; font scaling keeps the reading order in one column. */
@Composable
fun VisionGuardColumns(
    primaryWeight: Float = 1f,
    primary: @Composable ColumnScope.() -> Unit,
    secondary: @Composable ColumnScope.() -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val wide = (maxWidth >= 840.dp || landscape && maxWidth >= 480.dp) && LocalDensity.current.fontScale <= 1.3f
        if (wide) Row(Modifier.widthIn(max = 1200.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(primaryWeight), verticalArrangement = Arrangement.spacedBy(12.dp), content = primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), content = secondary)
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            primary()
            secondary()
        }
    }
}
