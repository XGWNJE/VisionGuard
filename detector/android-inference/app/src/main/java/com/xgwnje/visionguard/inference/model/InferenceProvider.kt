package com.xgwnje.visionguard.inference.model

/** 推理运行时。同一台设备上可并存，用于真机对比测量；正式跑哪一个由测量结论决定。 */
enum class InferenceRuntimeKind(val id: String, val title: String) {
    /** ONNX Runtime：现有主线运行时，提供器为 CPU / NNAPI。 */
    ONNX("onnx", "ONNX Runtime"),

    /** LiteRT（原 TFLite）：提供 CPU 与 GPU delegate，是 NNAPI 之外唯一公开可得的加速路线。 */
    LITERT("litert", "LiteRT")
}

/**
 * 可选的推理档位：运行时 + 执行提供器。正式运行固定用 [ONNX.CPU4]；其余档位只服务于推理加速测量。
 *
 * 为什么需要这张表：ONNX Runtime 官方 Android 包在 Java 侧只有 CPU、NNAPI、XNNPACK
 * 三个提供器（GPU 提供器全是桌面向），而真机实测 NNAPI 在 FP32 模型上比 CPU 慢 5–6 倍，
 * 因此主线把加速档位移除、固定走 CPU。现在要验证的是**量化模型 + 换运行时**：
 * 加速器对 INT8 的接受度高于 FP32，LiteRT 又有独立的 GPU delegate，因此需要按同一门槛
 * （≤ 同精度 CPU 的 70%）重新实测，而不是沿用 FP32 或 NNAPI 的结论。
 *
 * 这里只保存枚举与显示名，不引用任何运行时类型，方便在 JVM 单元测试里固定档位契约。
 */
enum class InferenceProvider(val id: String, val title: String, val runtime: InferenceRuntimeKind) {
    // ── ONNX Runtime ───────────────────────────────────────────────
    ONNX_CPU1("onnx_cpu_1thread", "ONNX · CPU 1 线程", InferenceRuntimeKind.ONNX),
    ONNX_CPU4("onnx_cpu_4threads", "ONNX · CPU 4 线程", InferenceRuntimeKind.ONNX),
    ONNX_CPU8("onnx_cpu_8threads", "ONNX · CPU 8 线程", InferenceRuntimeKind.ONNX),
    ONNX_NNAPI_DEFAULT("onnx_nnapi", "ONNX · NNAPI 默认", InferenceRuntimeKind.ONNX),
    ONNX_NNAPI_FP16("onnx_nnapi_fp16", "ONNX · NNAPI FP16", InferenceRuntimeKind.ONNX),
    ONNX_NNAPI_NCHW("onnx_nnapi_nchw", "ONNX · NNAPI NCHW", InferenceRuntimeKind.ONNX),
    ONNX_NNAPI_ACCEL_ONLY("onnx_nnapi_only", "ONNX · NNAPI 仅加速器", InferenceRuntimeKind.ONNX),

    // ── LiteRT ─────────────────────────────────────────────────────
    LITERT_CPU1("litert_cpu_1thread", "LiteRT · CPU 1 线程", InferenceRuntimeKind.LITERT),
    LITERT_CPU4("litert_cpu_4threads", "LiteRT · CPU 4 线程", InferenceRuntimeKind.LITERT),
    LITERT_CPU8("litert_cpu_8threads", "LiteRT · CPU 8 线程", InferenceRuntimeKind.LITERT),
    LITERT_CPU2_XNNPACK("litert_cpu_xnnpack_2", "LiteRT · CPU XNNPACK 2 线程", InferenceRuntimeKind.LITERT),
    LITERT_CPU4_XNNPACK("litert_cpu_xnnpack_4", "LiteRT · CPU XNNPACK 4 线程", InferenceRuntimeKind.LITERT),
    LITERT_ACCEL_CPU("litert_accel_cpu", "LiteRT · 加速器 API CPU", InferenceRuntimeKind.LITERT),
    LITERT_ACCEL_CPU_XNNPACK("litert_accel_cpu_xnnpack", "LiteRT · 加速器 API CPU + XNNPACK 4 线程", InferenceRuntimeKind.LITERT),
    LITERT_ACCEL_CPU_NO_XNNPACK("litert_accel_cpu_no_xnnpack", "LiteRT · 加速器 API CPU 关 XNNPACK", InferenceRuntimeKind.LITERT),
    LITERT_GPU("litert_gpu", "LiteRT · GPU 加速器", InferenceRuntimeKind.LITERT),
    LITERT_GPU_FP16("litert_gpu_fp16", "LiteRT · GPU 加速器 FP16", InferenceRuntimeKind.LITERT),
    LITERT_GPU_TEXTURE("litert_gpu_texture", "LiteRT · GPU 纹理权重", InferenceRuntimeKind.LITERT),
    LITERT_GPU_NO_EXT("litert_gpu_no_ext", "LiteRT · GPU 关外部张量", InferenceRuntimeKind.LITERT),
    LITERT_NPU("litert_npu", "LiteRT · NPU 加速器", InferenceRuntimeKind.LITERT);

    /** 是否使用 NNAPI。 */
    val usesNnapi: Boolean
        get() = this == ONNX_NNAPI_DEFAULT || this == ONNX_NNAPI_FP16 ||
            this == ONNX_NNAPI_NCHW || this == ONNX_NNAPI_ACCEL_ONLY

    /** LiteRT 老 `Interpreter` 档位（非加速器 API）。 */
    val usesLitertInterpreter: Boolean
        get() = runtime == InferenceRuntimeKind.LITERT && !usesLitertAcceleratorApi

    /**
     * 是否显式开启 XNNPACK。
     *
     * 官方 LiteRT 的 CPU 路径就是 XNNPACK；老 `Interpreter` 档默认是否启用取决于构建，
     * 显式开一档才能确认官方推荐的 CPU 加速在这台设备上到底值多少。
     */
    val usesXnnpack: Boolean
        get() = this == LITERT_CPU2_XNNPACK || this == LITERT_CPU4_XNNPACK ||
            this == LITERT_ACCEL_CPU_XNNPACK

    /** 是否显式关闭 XNNPACK（用于反向确认这一档确实依赖它）。 */
    val disablesXnnpack: Boolean get() = this == LITERT_ACCEL_CPU_NO_XNNPACK

    /**
     * 走 LiteRT `CompiledModel` 加速器 API 的档位。
     *
     * 与走老 `Interpreter` 的 CPU 档区分开：加速器 API 是 GPU / NPU 的唯一入口，
     * 单列一档 CPU 是为了让 GPU 有「同 API 的 CPU 基线」可对照。
     */
    val usesLitertAcceleratorApi: Boolean
        get() = this == LITERT_ACCEL_CPU || this == LITERT_ACCEL_CPU_XNNPACK ||
            this == LITERT_ACCEL_CPU_NO_XNNPACK || this == LITERT_GPU ||
            this == LITERT_GPU_FP16 || this == LITERT_GPU_TEXTURE ||
            this == LITERT_GPU_NO_EXT || this == LITERT_NPU

    /** 是否使用 LiteRT 的 GPU 加速器。 */
    val usesGpuDelegate: Boolean
        get() = this == LITERT_GPU || this == LITERT_GPU_FP16 ||
            this == LITERT_GPU_TEXTURE || this == LITERT_GPU_NO_EXT

    /** 是否使用 LiteRT 的 NPU 加速器。 */
    val usesNpu: Boolean get() = this == LITERT_NPU

    /** 是否走硬件加速（判定门槛时与同精度 CPU 对照的就是这些档位）。 */
    val usesAccelerator: Boolean get() = usesNnapi || usesGpuDelegate || usesNpu

    /** LiteRT 加速器档位对应的加速器枚举值；非该 API 的档位返回 null。 */
    val litertAccelerator: com.google.ai.edge.litert.Accelerator?
        get() = when (this) {
            LITERT_ACCEL_CPU, LITERT_ACCEL_CPU_XNNPACK, LITERT_ACCEL_CPU_NO_XNNPACK ->
                com.google.ai.edge.litert.Accelerator.CPU
            LITERT_GPU, LITERT_GPU_FP16, LITERT_GPU_TEXTURE, LITERT_GPU_NO_EXT ->
                com.google.ai.edge.litert.Accelerator.GPU
            LITERT_NPU -> com.google.ai.edge.litert.Accelerator.NPU
            else -> null
        }

    /** GPU 档位的精度；非 FP16 档位返回 null（即使用库默认精度）。 */
    val gpuPrecision: com.google.ai.edge.litert.CompiledModel.GpuOptions.Precision?
        get() = if (this == LITERT_GPU_FP16) {
            com.google.ai.edge.litert.CompiledModel.GpuOptions.Precision.FP16
        } else {
            null
        }

    /**
     * GPU 档位的配置说明，写进报告便于区分失败的到底是哪一种配置。
     *
     * 之所以要这几种变体：真机上 GPU 档报 `Failed to compile model`，而 FP32 与 INT8 两种图都失败，
     * 说明卡在配置或算子支持上。逐个变体试才能把「模型结构问题」和「GPU 配置问题」分开。
     */
    val gpuVariantNote: String
        get() = when (this) {
            LITERT_GPU_FP16 -> "精度 FP16"
            LITERT_GPU_TEXTURE -> "权重用纹理存储、关外部张量"
            LITERT_GPU_NO_EXT -> "关外部张量模式"
            LITERT_GPU -> "库默认配置"
            else -> ""
        }

    /** CPU 档位的线程数；加速档位返回 null。 */
    val cpuThreads: Int?
        get() = when (this) {
            ONNX_CPU1, LITERT_CPU1 -> 1
            ONNX_CPU4, LITERT_CPU4 -> 4
            ONNX_CPU8, LITERT_CPU8 -> 8
            LITERT_CPU2_XNNPACK -> 2
            LITERT_CPU4_XNNPACK -> 4
            LITERT_ACCEL_CPU_XNNPACK -> 4
            else -> null
        }

    companion object {
        /**
         * 正式运行档位：**ONNX Runtime · CPU 4 线程 + INT8 量化模型**。
         *
         * 这是 2026-10-11 真机实测（Xiaomi 26048RP6AC / API 37）得出的最优组合，三条依据：
         * 1. 运行时——ORT 的 NNAPI 提供器拒绝 `uint8` 量化图（四档全失败）；LiteRT 的 GPU 四档
         *    在三种模型结构下全部 `Failed to compile model`，NPU 因 MT8910 不在官方支持 SoC
         *    列表内而不可用；LiteRT 的 CPU 最好档为 37.2 ms，是 ORT 的 6.3 倍。
         * 2. 精度——INT8 在 640 上把 4 线程耗时从 356.4 ms（同结构 FP32）降到 131.6 ms（2.7 倍），
         *    生产模型 4 线程 112.6 ms / 8 线程 102.5 ms。
         * 3. 线程——真机 CPU 4 线程 5.9 ms（320）优于 1 线程 8.8 ms；8 线程在 320 上更差（6.5 ms）。
         *
         * 改这一行等于改发布行为，必须同时改 `AccelerationMeasureTest` 里的固定断言。
         */
        val production: InferenceProvider = ONNX_CPU4

        /**
         * 每个运行时的 CPU 基线档（判定门槛时的对照）。
         *
         * 门槛只在「同运行时、同精度」之间比较才有意义：LiteRT 的 GPU delegate 要与 LiteRT 的
         * CPU 比，不能拿 ONNX 的 CPU 读数当对照。
         */
        fun cpuBaseline(runtime: InferenceRuntimeKind): InferenceProvider = when (runtime) {
            InferenceRuntimeKind.ONNX -> ONNX_CPU4
            InferenceRuntimeKind.LITERT -> LITERT_CPU4
        }

        /** 测量顺序：先各运行时的 CPU 基线，再加速档。 */
        val benchmarkOrder: List<InferenceProvider> = listOf(
            ONNX_CPU1, ONNX_CPU4, ONNX_CPU8,
            ONNX_NNAPI_DEFAULT, ONNX_NNAPI_FP16, ONNX_NNAPI_NCHW, ONNX_NNAPI_ACCEL_ONLY,
            LITERT_CPU1, LITERT_CPU4, LITERT_CPU8,
            LITERT_CPU2_XNNPACK, LITERT_CPU4_XNNPACK,
            LITERT_ACCEL_CPU, LITERT_ACCEL_CPU_XNNPACK, LITERT_ACCEL_CPU_NO_XNNPACK,
            LITERT_GPU, LITERT_GPU_FP16, LITERT_GPU_TEXTURE, LITERT_GPU_NO_EXT,
            LITERT_NPU
        )

        fun fromId(id: String): InferenceProvider? = entries.firstOrNull { it.id == id }
    }
}
