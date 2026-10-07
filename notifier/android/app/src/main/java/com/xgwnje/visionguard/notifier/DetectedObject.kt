package com.xgwnje.visionguard.notifier

/** Event-time object snapshot; selection never substitutes a supported illustration for a higher-scoring class. */
data class DetectedObject(val label: String, val confidence: Double) {
    val valid: Boolean get() = label.isNotBlank() && label.length <= 64 && confidence.isFinite() && confidence in 0.0..1.0
    val displayName: String get() = when (label) {
        "person" -> "人"
        "car" -> "汽车"
        else -> label
    }
}

/** Equal scores keep detector order; invalid scores cannot win or erase a valid candidate. */
fun highestConfidenceObject(objects: List<DetectedObject>): DetectedObject? =
    objects.filter { it.valid }.maxByOrNull { it.confidence }
