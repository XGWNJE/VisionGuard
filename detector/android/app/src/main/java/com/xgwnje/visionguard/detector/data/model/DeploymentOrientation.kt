package com.xgwnje.visionguard.detector.data.model

enum class DeploymentOrientation {
    PORTRAIT,
    LANDSCAPE;

    val storageValue: String
        get() = when (this) {
            PORTRAIT -> "portrait"
            LANDSCAPE -> "landscape"
        }

    companion object {
        fun fromStorage(value: String?): DeploymentOrientation? =
            when (value) {
                "portrait" -> PORTRAIT
                "landscape" -> LANDSCAPE
                else -> null
            }
    }
}
