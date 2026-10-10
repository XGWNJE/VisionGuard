package com.xgwnje.visionguard.inference.model

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.providers.NNAPIFlags
import java.io.File
import java.nio.FloatBuffer
import java.util.EnumSet

/**
 * ONNX Runtime 会话封装，语义对齐 Windows `Inference/OnnxInferenceEngine.cs`。
 *
 * - 输入取模型元数据的第一项，输出只取第一个，两者都按张量展平给解析器；
 * - 输入尺寸取 `shape[2]`，形状不足四维时回退 320；
 * - 线程与会话参数与 Windows 一致（intra=2、inter=1、全量图优化、顺序执行）；
 * - 加速方案由 [AccelerationMode] 指定：注册成功但会话创建失败时显式降档重试，
 *   并把实际生效的后端与降档原因一起报给界面，绝不静默换档。
 */
class OnnxEngine private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val inputName: String,
    private val outputName: String,
    val modelSize: Int,
    val backend: String,
    /** 自动模式下实际胜出的方案；手动模式下与 [requestedMode] 相同。 */
    val effectiveMode: AccelerationMode,
    val requestedMode: AccelerationMode,
    val fallbackReason: String?
) {

    /** 运行一次推理；[tensor] 为 CHW `float[3 * modelSize * modelSize]`。 */
    fun run(tensor: FloatArray): FloatArray {
        val shape = longArrayOf(1, 3, modelSize.toLong(), modelSize.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(tensor), shape).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                val first = result.get(0)
                val output = first as? OnnxTensor
                    ?: throw IllegalStateException("模型输出不是张量，无法解析。")
                val buffer = output.floatBuffer
                val raw = FloatArray(buffer.remaining())
                buffer.get(raw)
                return raw
            }
        }
    }

    fun close() {
        runCatching { session.close() }
    }

    companion object {
        /** 低于该体积的模型文件视为未下载完成。 */
        const val MIN_MODEL_BYTES = 1L * 1024 * 1024

        /**
         * 按 [mode] 建会话。自动模式按 [AccelerationMode.AUTO_ORDER] 逐档尝试，
         * 首个能建起会话的方案胜出；手动模式只用指定方案，失败即返回原因。
         */
        fun create(modelFile: File, mode: AccelerationMode = AccelerationMode.AUTO): OnnxEngine {
            require(modelFile.isFile) { "模型文件不存在：${modelFile.name}" }
            val environment = OrtEnvironment.getEnvironment()
            val attempts = if (mode == AccelerationMode.AUTO) AccelerationMode.AUTO_ORDER else listOf(mode)

            val failures = StringBuilder()
            for ((index, candidate) in attempts.withIndex()) {
                val options = OrtSession.SessionOptions()
                options.setIntraOpNumThreads(candidate.intraOpThreads)
                options.setInterOpNumThreads(1)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                val registered = applyProvider(options, candidate)
                val session = runCatching { environment.createSession(modelFile.absolutePath, options) }
                if (session.isSuccess) {
                    val reason = when {
                        failures.isEmpty() -> null
                        else -> "自动降档：$failures"
                    }
                    return build(environment, session.getOrThrow(), candidate, candidate, registered, reason)
                }
                runCatching { options.close() }
                val message = session.exceptionOrNull()?.message ?: "会话创建失败"
                if (mode != AccelerationMode.AUTO) {
                    throw IllegalStateException("${candidate.title} 不可用：$message")
                }
                if (failures.isNotEmpty()) failures.append("；")
                failures.append("${candidate.title}→${message.take(80)}")
                if (index == attempts.lastIndex) {
                    throw IllegalStateException("所有加速方案都不可用：$failures")
                }
            }
            throw IllegalStateException("没有可用的加速方案")
        }

        /** 注册执行提供器；返回实际注册成功的后端名。 */
        private fun applyProvider(
            options: OrtSession.SessionOptions,
            mode: AccelerationMode
        ): String = when (mode) {
            AccelerationMode.CPU_XNNPACK -> "CPU · XNNPACK"

            AccelerationMode.CPU_THREADS4 -> "CPU · 4 线程"

            AccelerationMode.NNAPI -> {
                options.addNnapi()
                "NNAPI"
            }

            AccelerationMode.NNAPI_FP16 -> {
                options.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                "NNAPI · FP16"
            }

            AccelerationMode.NNAPI_NCHW -> {
                options.addNnapi(EnumSet.of(NNAPIFlags.USE_NCHW, NNAPIFlags.USE_FP16))
                "NNAPI · NCHW"
            }

            AccelerationMode.NNAPI_ACCEL_ONLY -> {
                options.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED, NNAPIFlags.USE_FP16))
                "NNAPI · 仅加速器"
            }

            // 自动模式在 [create] 里已经展开成具体方案，这里不会走到。
            AccelerationMode.AUTO -> "CPU · XNNPACK"
        }

        private fun build(
            environment: OrtEnvironment,
            session: OrtSession,
            effective: AccelerationMode,
            requested: AccelerationMode,
            backend: String,
            fallbackReason: String?
        ): OnnxEngine {
            val inputName = session.inputNames.firstOrNull()
                ?: throw IllegalStateException("模型没有输入张量。")
            val outputName = session.outputNames.firstOrNull()
                ?: throw IllegalStateException("模型没有输出张量。")
            val modelSize = runCatching {
                val info = session.inputInfo[inputName]?.info as? TensorInfo
                val shape = info?.shape
                if (shape != null && shape.size >= 4 && shape[2] > 0) shape[2].toInt() else DEFAULT_MODEL_SIZE
            }.getOrDefault(DEFAULT_MODEL_SIZE)

            return OnnxEngine(
                environment = environment,
                session = session,
                inputName = inputName,
                outputName = outputName,
                modelSize = modelSize,
                backend = backend,
                effectiveMode = effective,
                requestedMode = requested,
                fallbackReason = fallbackReason
            )
        }

        private const val DEFAULT_MODEL_SIZE = 320
    }
}
