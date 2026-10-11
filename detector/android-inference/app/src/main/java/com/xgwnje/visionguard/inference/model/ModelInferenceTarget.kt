package com.xgwnje.visionguard.inference.model

/**
 * 一个模型键当前跑在哪个后端 —— UI 与心跳都按它显示。
 *
 * 判定必须与 [InferenceEngineFactory] 的真实选择一致，否则界面会显示一个后端、实际跑另一个：
 * 有 GPU 产物且已下载 → GPU；量化版 → ONNX Runtime CPU（这些产物只有 `.onnx`，没有 GPU 版本）；
 * 其余 FP32 本体 → ONNX Runtime CPU。
 */
enum class ModelInferenceTarget(
    /** 界面上显示的标签，例如 `GPU`、`CPU · INT8`。 */
    val label: String,
    /** 是否走硬件加速。 */
    val accelerated: Boolean
) {
    GpuFp32("GPU", accelerated = true),
    CpuFp32("CPU", accelerated = false),
    CpuInt8("CPU · INT8", accelerated = false);

    companion object {
        /** 按「键 + GPU 产物是否已就绪」定后端。 */
        fun resolve(modelKey: String, gpuReady: Boolean): ModelInferenceTarget = when {
            gpuReady && ModelKeys.supportsGpu(modelKey) -> GpuFp32
            ModelKeys.isQuantized(modelKey) -> CpuInt8
            else -> CpuFp32
        }
    }
}
