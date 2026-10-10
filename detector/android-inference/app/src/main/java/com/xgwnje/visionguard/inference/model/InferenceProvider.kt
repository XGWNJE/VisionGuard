package com.xgwnje.visionguard.inference.model

/**
 * 可选的推理执行提供器。正式运行固定用 [CPU4]；其余档位只服务于推理加速测量。
 *
 * 为什么需要这张表：ONNX Runtime 官方 Android 包在 Java 侧只有 CPU、NNAPI、XNNPACK
 * 三个提供器（GPU 提供器全是桌面向）。主线曾实测 NNAPI 在 FP32 模型上比 CPU 慢 5–6 倍，
 * 因此把加速档位移除、固定走 CPU。现在要验证的是**量化模型**：加速器对 INT8 的接受度
 * 高于 FP32，所以需要按同一门槛（≤ 同精度 CPU 的 70%）重新实测，而不是沿用 FP32 的结论。
 *
 * 这里只保存枚举与显示名，不引用 ONNX Runtime 类型，方便在 JVM 单元测试里固定档位契约。
 */
enum class InferenceProvider(val id: String, val title: String) {
    CPU1("cpu_1thread", "CPU · 1 线程"),
    CPU4("cpu_4threads", "CPU · 4 线程"),
    CPU8("cpu_8threads", "CPU · 8 线程"),
    NNAPI_DEFAULT("nnapi", "NNAPI · 默认"),
    NNAPI_FP16("nnapi_fp16", "NNAPI · FP16"),
    NNAPI_NCHW("nnapi_nchw", "NNAPI · NCHW"),
    NNAPI_ACCEL_ONLY("nnapi_only", "NNAPI · 仅加速器");

    val usesNnapi: Boolean get() = this != CPU1 && this != CPU4 && this != CPU8

    /** CPU 档位的线程数；NNAPI 档位返回 null。 */
    val cpuThreads: Int?
        get() = when (this) {
            CPU1 -> 1
            CPU4 -> 4
            CPU8 -> 8
            else -> null
        }

    companion object {
        /** 正式运行使用的档位。 */
        val production: InferenceProvider = CPU4

        /** 测量顺序：先 CPU 基线，再加速档，便于按顺序读数。 */
        val benchmarkOrder: List<InferenceProvider> = listOf(
            CPU1, CPU4, CPU8, NNAPI_DEFAULT, NNAPI_FP16, NNAPI_NCHW, NNAPI_ACCEL_ONLY
        )

        fun fromId(id: String): InferenceProvider? = entries.firstOrNull { it.id == id }
    }
}
