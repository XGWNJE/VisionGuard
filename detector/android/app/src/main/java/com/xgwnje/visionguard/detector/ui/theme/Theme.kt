package com.xgwnje.visionguard.detector.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.xgwnje.visionguard.account.VisionGuardTheme
import com.xgwnje.visionguard.account.VisionGuardThemeStyle
import com.xgwnje.visionguard.detector.R

// One bundled variable file supplies real weights; the camera's minSdk is 28.
@OptIn(ExperimentalTextApi::class)
private val CameraFont = FontFamily(listOf(100, 200, 300, 400, 500, 600, 700, 800, 900).map { weight ->
    Font(R.font.noto_sans_sc, weight = FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
})

@Composable
fun VisionguardTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    VisionGuardTheme(darkTheme, VisionGuardThemeStyle.CONSOLE) {
        val defaults = MaterialTheme.typography
        val typography = remember(defaults) { defaults.copy(
            displayLarge = defaults.displayLarge.copy(fontFamily = CameraFont),
            displayMedium = defaults.displayMedium.copy(fontFamily = CameraFont),
            displaySmall = defaults.displaySmall.copy(fontFamily = CameraFont),
            headlineLarge = defaults.headlineLarge.copy(fontFamily = CameraFont),
            headlineMedium = defaults.headlineMedium.copy(fontFamily = CameraFont),
            headlineSmall = defaults.headlineSmall.copy(fontFamily = CameraFont),
            titleLarge = defaults.titleLarge.copy(fontFamily = CameraFont),
            titleMedium = defaults.titleMedium.copy(fontFamily = CameraFont),
            titleSmall = defaults.titleSmall.copy(fontFamily = CameraFont),
            bodyLarge = defaults.bodyLarge.copy(fontFamily = CameraFont),
            bodyMedium = defaults.bodyMedium.copy(fontFamily = CameraFont),
            bodySmall = defaults.bodySmall.copy(fontFamily = CameraFont),
            labelLarge = defaults.labelLarge.copy(fontFamily = CameraFont),
            labelMedium = defaults.labelMedium.copy(fontFamily = CameraFont),
            labelSmall = defaults.labelSmall.copy(fontFamily = CameraFont)
        ) }
        MaterialTheme(typography = typography,
            shapes = MaterialTheme.shapes.copy(extraLarge = MaterialTheme.shapes.large), content = content)
    }
}
