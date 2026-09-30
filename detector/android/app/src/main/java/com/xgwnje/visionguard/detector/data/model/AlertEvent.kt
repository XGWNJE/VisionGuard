package com.xgwnje.visionguard.detector.data.model

import android.graphics.Bitmap

data class AlertEvent(
    val alertId: String,
    val timestamp: Long,
    val detections: List<Detection>,
    val renderedFrame: Bitmap?,
    val timings: Map<String, Long> = emptyMap()
)
