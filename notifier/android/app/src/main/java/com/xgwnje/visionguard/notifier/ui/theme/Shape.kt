// src/main/java/com/example/vg_notifier/ui/theme/Shape.kt
package com.xgwnje.visionguard.notifier.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes // *** 更改为 Material 3 ***
import androidx.compose.ui.unit.dp

// Material 3 形状方案
// 您可以根据需要调整这些值
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp), // M3 通常 medium 圆角比 M2 大一些
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp) // 例如用于全屏对话框的卡片
)
