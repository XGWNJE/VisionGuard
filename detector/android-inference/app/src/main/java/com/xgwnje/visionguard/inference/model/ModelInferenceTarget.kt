package com.xgwnje.visionguard.inference.model

/**
 * 一个档位当前跑在哪个后端 —— UI 与心跳都按它显示。
 *
 * 判定必须与 [InferenceEngineFactory] 的真实选择一致，否则界面会显示一个后端、实际跑另一个。
 * 每个档位同时含 CPU / GPU 产物；这里预测可用后端，实际运行以引擎结果为准。
 */
enum class ModelInferenceTarget(
    /** 界面上显示的标签，例如 `GPU`、`CPU`。 */
    val label: String,
    /** 是否走硬件加速。 */
    val accelerated: Boolean
) {
    Gpu("GPU", accelerated = true),
    Cpu("CPU", accelerated = false);

    companion object {
        /** 按「档位 + 产物是否就绪 + 设备是否支持」定后端。 */
        fun resolve(modelKey: String, gpuReady: Boolean, gpuAvailable: Boolean): ModelInferenceTarget =
            if (gpuAvailable && gpuReady && ModelKeys.supportsGpu(modelKey)) Gpu else Cpu
    }
}
