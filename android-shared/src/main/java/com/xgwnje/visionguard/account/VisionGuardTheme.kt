package com.xgwnje.visionguard.account

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF087F83), onPrimary = Color.White,
    primaryContainer = Color(0xFFE2F1F2), onPrimaryContainer = Color(0xFF172326),
    background = Color(0xFFF5F7F8), onBackground = Color(0xFF172326),
    surface = Color.White, onSurface = Color(0xFF172326),
    surfaceVariant = Color(0xFFEFF3F4), onSurfaceVariant = Color(0xFF66777A),
    outline = Color(0xFF98A9AC), outlineVariant = Color(0xFFDFE6E7)
)
private val Dark = darkColorScheme(
    primary = Color(0xFF4FBCC1), onPrimary = Color(0xFF10191B),
    primaryContainer = Color(0xFF113437), onPrimaryContainer = Color(0xFFEDF4F5),
    background = Color(0xFF10191B), onBackground = Color(0xFFEDF4F5),
    surface = Color(0xFF172326), onSurface = Color(0xFFEDF4F5),
    surfaceVariant = Color(0xFF203033), onSurfaceVariant = Color(0xFF9AADB1),
    outline = Color(0xFF66777A), outlineVariant = Color(0xFF2C3A3D)
)
private val AppTypography = Typography().let { defaults ->
    defaults.copy(
        titleLarge = defaults.titleLarge.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
        titleMedium = defaults.titleMedium.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        bodyLarge = defaults.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
        labelLarge = defaults.labelLarge.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
    )
}
private val AppShapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp))

@Composable
fun VisionGuardTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, typography = AppTypography, shapes = AppShapes) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}
