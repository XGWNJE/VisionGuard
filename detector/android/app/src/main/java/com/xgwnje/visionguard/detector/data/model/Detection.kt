package com.xgwnje.visionguard.detector.data.model

import android.graphics.RectF

data class Detection(
    val label: String,
    val confidence: Float,
    val bbox: RectF
)
