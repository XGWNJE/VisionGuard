package com.xgwnje.visionguard_android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = ReceiverPrimarySoft,
    onPrimary = ReceiverInk,
    secondary = ReceiverAmber,
    tertiary = ReceiverAlert,
    background = ReceiverDarkBackground,
    onBackground = Color(0xFFE7EFE8),
    surface = ReceiverDarkSurface,
    onSurface = Color(0xFFE7EFE8),
    surfaceVariant = ReceiverDarkSurfaceMuted,
    onSurfaceVariant = Color(0xFFB9C5BC),
    outline = Color(0xFF3A4A40),
    error = ReceiverAlert
)

private val LightColorScheme = lightColorScheme(
    primary = ReceiverPrimary,
    onPrimary = Color.White,
    secondary = ReceiverAmber,
    tertiary = ReceiverAlert,
    background = ReceiverBackground,
    onBackground = ReceiverInk,
    surface = ReceiverSurface,
    onSurface = ReceiverInk,
    surfaceVariant = ReceiverSurfaceMuted,
    onSurfaceVariant = ReceiverMuted,
    outline = ReceiverOutline,
    error = ReceiverAlert
)

@Composable
fun VisionGuard_AndroidTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
