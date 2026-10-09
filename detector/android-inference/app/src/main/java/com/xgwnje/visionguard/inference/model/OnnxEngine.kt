package com.xgwnje.visionguard.inference.model

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer

/**
 * ONNX Runtime 会话封装，语义对齐 Windows `Inference/OnnxInferenceEngine.cs`。
 *
 * - 输入取模型元数据的第一项，输出只取第一个，两者都按张量展平给解析器；
 * - 输入尺寸取 `shape[2]`，形状不足四维时回退 320；
 * - 线程与会话参数与 Windows 一致（intra=2、inter=1、全量图优化、顺序执行）；
 * - 默认先尝试 NNAPI 加速，失败时回落 CPU 并保留原因，绝不静默换档。
 */
class OnnxEngine private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val inputName: String,
    private val outputName: String,
    val modelSize: Int,
    val backend: String,
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

        fun create(modelFile: File, preferNnapi: Boolean = true): OnnxEngine {
            require(modelFile.isFile) { "模型文件不存在：${modelFile.name}" }
            val environment = OrtEnvironment.getEnvironment()

            var fallbackReason: String? = null
            val options = OrtSession.SessionOptions()
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

            var nnapiEnabled = false
            if (preferNnapi) {
                runCatching { options.addNnapi() }
                    .onSuccess { nnapiEnabled = true }
                    .onFailure { fallbackReason = "NNAPI 不可用（${it.message ?: it::class.java.simpleName}）" }
            }

            val session = try {
                environment.createSession(modelFile.absolutePath, options)
            } catch (error: Exception) {
                // NNAPI 注册成功但会话创建失败时，必须显式退回 CPU 重试。
                if (!nnapiEnabled) throw error
                nnapiEnabled = false
                fallbackReason = "NNAPI 会话创建失败（${error.message ?: error::class.java.simpleName}）"
                val cpuOptions = OrtSession.SessionOptions()
                cpuOptions.setIntraOpNumThreads(2)
                cpuOptions.setInterOpNumThreads(1)
                cpuOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                environment.createSession(modelFile.absolutePath, cpuOptions)
            }

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
                backend = if (nnapiEnabled) "NNAPI" else "CPU",
                fallbackReason = fallbackReason
            )
        }

        private const val DEFAULT_MODEL_SIZE = 320
    }
}
