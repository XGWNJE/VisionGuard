package com.xgwnje.visionguard.inference

enum class InferenceBackend { CPU, QNN }

data class InferenceBackendStatus(
    val requested: InferenceBackend = InferenceBackend.CPU,
    val active: InferenceBackend = InferenceBackend.CPU,
    val fallbackReason: String = ""
) {
    val displayText: String
        get() = if (fallbackReason.isBlank()) active.name else "${active.name}（$fallbackReason）"
}

object AndroidInferenceBackendPolicy {
    /** 当前 Maven 发行物不包含 QNN EP；接入 Qualcomm SDK 构建后才能把此值改为 true。 */
    const val QNN_COMPILED_IN = false

    fun resolve(requested: InferenceBackend): InferenceBackendStatus = when {
        requested == InferenceBackend.QNN && !QNN_COMPILED_IN -> InferenceBackendStatus(
            requested = requested,
            active = InferenceBackend.CPU,
            fallbackReason = "QNN 构建未包含"
        )
        else -> InferenceBackendStatus(requested, requested)
    }
}
