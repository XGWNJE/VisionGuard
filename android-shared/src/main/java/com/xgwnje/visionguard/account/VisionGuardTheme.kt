package com.xgwnje.visionguard.account

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF087F83), onPrimary = Color.White,
    primaryContainer = Color(0xFFEDEDED), onPrimaryContainer = Color(0xFF07666A),
    background = Color(0xFFF5F5F5), onBackground = Color(0xFF202020),
    surface = Color.White, onSurface = Color(0xFF202020),
    surfaceVariant = Color(0xFFEEEEEE), onSurfaceVariant = Color(0xFF626262),
    secondary = Color(0xFF087F83), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEDED), onSecondaryContainer = Color(0xFF07666A),
    tertiary = Color(0xFF805500), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFF1D6), onTertiaryContainer = Color(0xFF805500),
    error = Color(0xFFB83434), onError = Color.White,
    errorContainer = Color(0xFFFCEBEC), onErrorContainer = Color(0xFFB83434),
    outline = Color(0xFF626262), outlineVariant = Color(0xFFDDDDDD),
    inverseSurface = Color(0xFF1B1B1B), inverseOnSurface = Color(0xFFF2F2F2), inversePrimary = Color(0xFF4FBCC1),
    surfaceTint = Color.Transparent,
    surfaceBright = Color.White, surfaceDim = Color(0xFFEEEEEE),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFEEEEEE), surfaceContainerHighest = Color(0xFFEEEEEE)
)
private val Dark = darkColorScheme(
    primary = Color(0xFF4FBCC1), onPrimary = Color(0xFF062E30),
    primaryContainer = Color(0xFF303030), onPrimaryContainer = Color(0xFF75D2D6),
    background = Color(0xFF121212), onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF1B1B1B), onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF282828), onSurfaceVariant = Color(0xFFB3B3B3),
    secondary = Color(0xFF4FBCC1), onSecondary = Color(0xFF062E30),
    secondaryContainer = Color(0xFF303030), onSecondaryContainer = Color(0xFF75D2D6),
    tertiary = Color(0xFFF4CC79), onTertiary = Color(0xFF2B200D),
    tertiaryContainer = Color(0xFF3B3020), onTertiaryContainer = Color(0xFFF4CC79),
    error = Color(0xFFFF9696), onError = Color(0xFF431B18),
    errorContainer = Color(0xFF442326), onErrorContainer = Color(0xFFFF9696),
    outline = Color(0xFFB3B3B3), outlineVariant = Color(0xFF3D3D3D),
    inverseSurface = Color.White, inverseOnSurface = Color(0xFF202020), inversePrimary = Color(0xFF087F83),
    surfaceTint = Color.Transparent,
    surfaceBright = Color(0xFF282828), surfaceDim = Color(0xFF121212),
    surfaceContainerLowest = Color(0xFF121212), surfaceContainerLow = Color(0xFF1B1B1B),
    surfaceContainer = Color(0xFF1B1B1B), surfaceContainerHigh = Color(0xFF282828), surfaceContainerHighest = Color(0xFF282828)
)
private val AppTypography = Typography().let { defaults ->
    defaults.copy(
        titleLarge = defaults.titleLarge.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleMedium = defaults.titleMedium.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        bodyLarge = defaults.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
        headlineLarge = defaults.headlineLarge.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        headlineMedium = defaults.headlineMedium.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        headlineSmall = defaults.headlineSmall.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleSmall = defaults.titleSmall.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        bodyMedium = defaults.bodyMedium.copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
        bodySmall = defaults.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
        labelLarge = defaults.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        labelMedium = defaults.labelMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
        labelSmall = defaults.labelSmall.copy(fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp)
    )
}
private val AppShapes = Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(24.dp))

private val LocalDarkTheme = staticCompositionLocalOf { false }

/** Semantic status pairs follow the selected appearance. */
object VisionGuardStatusColors {
    val success: Color @Composable get() = MaterialTheme.colorScheme.primary
    val successContainer: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer
    val onSuccessContainer: Color @Composable get() = if (LocalDarkTheme.current) Color(0xFF75D2D6) else Color(0xFF07666A)
    val warning: Color @Composable get() = MaterialTheme.colorScheme.tertiary
    val warningContainer: Color @Composable get() = MaterialTheme.colorScheme.tertiaryContainer
    val onWarningContainer: Color @Composable get() = MaterialTheme.colorScheme.onTertiaryContainer
    val error: Color @Composable get() = MaterialTheme.colorScheme.error
}

/** Readable neutral disabled states, shared by the three Android applications. */
object VisionGuardControlColors {
    @Composable
    fun checkbox() = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary,
        uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant, checkmarkColor = MaterialTheme.colorScheme.onPrimary,
        disabledCheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledUncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant)

    @Composable
    fun button(containerColor: Color = MaterialTheme.colorScheme.primary, contentColor: Color = MaterialTheme.colorScheme.onPrimary) =
        ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant, disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant)

    @Composable
    fun outlinedButton(containerColor: Color = Color.Transparent, contentColor: Color = MaterialTheme.colorScheme.primary) =
        ButtonDefaults.outlinedButtonColors(containerColor = containerColor, contentColor = contentColor,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant, disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant)

    @Composable
    fun textButton(contentColor: Color = VisionGuardStatusColors.onSuccessContainer) =
        ButtonDefaults.textButtonColors(contentColor = contentColor, disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant)

    @Composable
    fun outlinedField(focusedLabelColor: Color = VisionGuardStatusColors.onSuccessContainer) =
        OutlinedTextFieldDefaults.colors(focusedLabelColor = focusedLabelColor,
            disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledSupportingTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledBorderColor = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun VisionGuardTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = if (darkTheme) Dark else Light, typography = AppTypography, shapes = AppShapes) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}
