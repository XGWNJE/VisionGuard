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
 * - 正式运行固定走 CPU（intra=4、inter=1、全量图优化、顺序执行）。
 *
 * 之所以正式运行只用 CPU：真机实测（MediaTek MT8910，yolo26m · 640，FP32）NNAPI 四档为
 * 1738–2252 ms，而 CPU 只要 335 ms——系统加速器在这条链上把图切碎后，跨设备拷贝的开销
 * 超过了加速收益。主线据此移除档位与诊断。本分支重新开启加速档位的唯一目的，是实测
 * **量化模型**（INT8）能否满足「快于同精度 CPU 30%」的门槛；未达标前正式运行仍是 CPU。
 */
class OnnxEngine private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val inputName: String,
    private val outputName: String,
    val modelSize: Int,
    /** 实际创建时使用的执行提供器档位。 */
    val provider: InferenceProvider
) {

    /** 后端标识：界面与心跳按它显示。 */
    val backend: String = provider.title

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

        /** 后端显示名（正式运行）。 */
        const val BACKEND = "CPU"

        /** CPU 线程数：本机实测 4 线程比 2 线程快约两成。 */
        private const val INTRA_OP_THREADS = 4

        /** 正式运行：固定 CPU · 4 线程。 */
        fun create(modelFile: File): OnnxEngine = create(modelFile, InferenceProvider.production)

        /** 指定执行提供器创建会话；加速档位只用于测量，正式路径仍走 [InferenceProvider.production]。 */
        fun create(modelFile: File, provider: InferenceProvider): OnnxEngine {
            require(modelFile.isFile) { "模型文件不存在：${modelFile.name}" }
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            val threads = provider.cpuThreads
            if (threads != null) {
                options.setIntraOpNumThreads(threads)
            }
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (provider.usesNnapi) options.addNnapi(nnapiFlags(provider))
            val session = environment.createSession(modelFile.absolutePath, options)

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
                provider = provider
            )
        }

        /** NNAPI 档位到标志位的映射；默认档不附加任何标志。 */
        private fun nnapiFlags(provider: InferenceProvider): EnumSet<NNAPIFlags> = when (provider) {
            InferenceProvider.ONNX_NNAPI_FP16 -> EnumSet.of(NNAPIFlags.USE_FP16)
            InferenceProvider.ONNX_NNAPI_NCHW -> EnumSet.of(NNAPIFlags.USE_NCHW)
            InferenceProvider.ONNX_NNAPI_ACCEL_ONLY -> EnumSet.of(NNAPIFlags.CPU_DISABLED)
            else -> EnumSet.noneOf(NNAPIFlags::class.java)
        }

        private const val DEFAULT_MODEL_SIZE = 320
    }
}
