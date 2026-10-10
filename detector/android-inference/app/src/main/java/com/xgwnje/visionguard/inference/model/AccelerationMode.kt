package com.xgwnje.visionguard.inference.model

/**
 * 推理加速方案。
 *
 * 本端用 ONNX Runtime 官方 Android 包，Java 侧可用的执行提供器只有两档：NNAPI（系统加速器，
 * 在联发科设备上落到 Neuron / APU，在骁龙上落到 Hexagon，也可能落到 GPU）与 CPU。
 * CPU 一档在官方包里已经是 XNNPACK 实现（同为向量指令优化），因此这里的差别是线程数；
 * NNAPI 一档的差别是半精度、布局偏好与是否允许回退 CPU。
 *
 * QNN / NeuroPilot 这类厂商 NPU 提供器不在官方包里，Java API 也没有注册入口，
 * 因此不列为可选项；能力与依据见 `docs/31-Android视觉推理节点.md`。
 */
enum class AccelerationMode(
    val id: String,
    val title: String,
    val detail: String,
    val intraOpThreads: Int = 2
) {
    AUTO(
        "auto",
        "自动",
        "按推荐顺序探测，记住首个可用方案，之后直接复用"
    ),
    NNAPI_FP16(
        "nnapi_fp16",
        "NNAPI · FP16",
        "系统加速器优先半精度；多数卷积模型更快，精度损失可忽略"
    ),
    NNAPI(
        "nnapi",
        "NNAPI · 默认",
        "系统加速器默认精度，不支持的算子由运行时回退"
    ),
    NNAPI_NCHW(
        "nnapi_nchw",
        "NNAPI · NCHW",
        "偏好 NCHW 布局并启用 FP16；部分模型能少一次转置"
    ),
    NNAPI_ACCEL_ONLY(
        "nnapi_only",
        "NNAPI · 仅加速器",
        "禁止回退 CPU，用来确认整图是否真的跑在加速器上；不支持的算子会直接失败"
    ),
    CPU_XNNPACK(
        "cpu_xnnpack",
        "CPU · XNNPACK",
        "官方包默认的 CPU 实现（XNNPACK），2 线程，不依赖系统加速器"
    ),
    CPU_THREADS4(
        "cpu_4threads",
        "CPU · 4 线程",
        "CPU 实现开到 4 线程，用于观察线程数与加速器的差距",
        intraOpThreads = 4
    );

    companion object {
        /** 自动模式的探测顺序：先把加速器各档试一遍，再退到 CPU。 */
        val AUTO_ORDER = listOf(
            NNAPI_FP16,
            NNAPI,
            NNAPI_NCHW,
            CPU_XNNPACK,
            CPU_THREADS4,
            NNAPI_ACCEL_ONLY
        )

        fun fromId(id: String?): AccelerationMode? = entries.firstOrNull { it.id == id }

        /** 自动模式不在手动列表里重复出现，其余按探测顺序排列。 */
        val selectable: List<AccelerationMode> = listOf(AUTO) + AUTO_ORDER
    }
}
