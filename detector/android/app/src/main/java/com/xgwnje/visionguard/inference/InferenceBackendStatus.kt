package com.xgwnje.visionguard.inference

enum class InferenceBackend { CPU, NNAPI, QNN }

data class InferenceBackendStatus(
    val requested: InferenceBackend = InferenceBackend.CPU,
    val active: InferenceBackend = InferenceBackend.CPU,
    val fallbackReason: String = "",
    val actualProvider: String? = null,
    val profilePath: String? = null
) {
    val hardwareExecutionConfirmed: Boolean
        get() = active == InferenceBackend.NNAPI &&
            actualProvider == "NnapiExecutionProvider"

    val displayText: String
        get() {
            val state = when {
                hardwareExecutionConfirmed -> "已确认实际执行"
                active == InferenceBackend.NNAPI -> "已注册，等待执行证据"
                else -> ""
            }
            val details = listOf(fallbackReason, state).filter { it.isNotBlank() }
            return if (details.isEmpty()) active.name else "${active.name}（${details.joinToString("；")}）"
        }
}

object AndroidInferenceBackendPolicy {
    /** 当前 Maven 发行物不包含 QNN EP；接入 Qualcomm SDK 构建后才能把此值改为 true。 */
    const val QNN_COMPILED_IN = false

    /** NNAPI EP 已随当前 ORT Android AAR 提供，Android 9/API 29 起可使用 CPU_DISABLED。 */
    fun resolve(
        requested: InferenceBackend,
        nnapiAvailable: Boolean = true
    ): InferenceBackendStatus = when {
        requested == InferenceBackend.QNN && !QNN_COMPILED_IN -> InferenceBackendStatus(
            requested = requested,
            active = InferenceBackend.CPU,
            fallbackReason = "QNN 构建未包含"
        )
        requested == InferenceBackend.NNAPI && !nnapiAvailable -> InferenceBackendStatus(
            requested = requested,
            active = InferenceBackend.CPU,
            fallbackReason = "NNAPI 需要 Android 9/API 29+"
        )
        else -> InferenceBackendStatus(requested, requested)
    }
}
