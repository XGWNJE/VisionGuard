package com.xgwnje.visionguard.inference.model

/**
 * 推理会话的统一入口。
 *
 * 之所以要这层抽象：正式链路原先写死 [OnnxEngine]，而 GPU 加速只能用 LiteRT 的
 * `CompiledModel`（见 [LitertAcceleratedEngine]）。来源运行时只关心「多大输入、跑一帧、
 * 后端叫什么」，不关心底下是哪个运行时，这样换后端不需要动来源运行时。
 *
 * 输入输出语义由实现保证一致：CHW `float[3 * modelSize * modelSize]` 进、展平 float 出。
 */
interface InferenceEngine {

    /** 模型输入边长（正方形），来源运行时按它做 letterbox。 */
    val modelSize: Int

    /** 后端标识：界面与心跳按它显示，例如 `ONNX Runtime · CPU 4 线程`。 */
    val backend: String

    /** 是否走硬件加速（GPU）。未加速时为 false，用于并发上限与「为何没用 GPU」的口径。 */
    val accelerated: Boolean

    /** 后端细节，写进状态与心跳，便于判断加速到底有没有生效。 */
    val backendNote: String

    /** 运行一次推理；[tensor] 为 CHW `float[3 * modelSize * modelSize]`。 */
    fun run(tensor: FloatArray): FloatArray

    fun close()
}
