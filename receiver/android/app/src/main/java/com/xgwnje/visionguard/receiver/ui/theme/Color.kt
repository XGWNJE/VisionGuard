package com.xgwnje.visionguard.receiver.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.xgwnje.visionguard.account.VisionGuardStatusColors

val ReceiverBackground: Color @Composable get() = MaterialTheme.colorScheme.background
val ReceiverSurface: Color @Composable get() = MaterialTheme.colorScheme.surface
val ReceiverSurfaceMuted: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
val ReceiverPrimary: Color @Composable get() = MaterialTheme.colorScheme.primary
val ReceiverPrimaryText: Color @Composable get() = VisionGuardStatusColors.onSuccessContainer
val ReceiverOnPrimary: Color @Composable get() = MaterialTheme.colorScheme.onPrimary
val ReceiverPrimarySoft: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer
val ReceiverInk: Color @Composable get() = MaterialTheme.colorScheme.onSurface
val ReceiverMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val ReceiverOutline: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
val ReceiverAlert: Color @Composable get() = MaterialTheme.colorScheme.onErrorContainer
val ReceiverAlertSoft: Color @Composable get() = MaterialTheme.colorScheme.errorContainer
val ReceiverAmber: Color @Composable get() = VisionGuardStatusColors.onWarningContainer
val ReceiverAmberSoft: Color @Composable get() = VisionGuardStatusColors.warningContainer
