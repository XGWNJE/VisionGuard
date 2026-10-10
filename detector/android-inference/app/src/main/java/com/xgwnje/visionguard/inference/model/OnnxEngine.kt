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
 * - 固定走 CPU（intra=4、inter=1、全量图优化、顺序执行）。
 *
 * 之所以只用 CPU：真机实测（MediaTek MT8910，yolo26m · 640）NNAPI 四档为
 * 1738–2252 ms，而 CPU 只要 335 ms——系统加速器在这条链上把图切碎后，
 * 跨设备拷贝的开销超过了加速收益。多档位、自动探测与诊断已移除，
 * 推理框架与模型的优化转到 `feature/inference-optimization` 分支。
 */
class OnnxEngine private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val inputName: String,
    private val outputName: String,
    val modelSize: Int
) {

    /** 后端标识：界面与心跳按它显示。 */
    val backend: String = BACKEND

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

        /** 后端显示名。 */
        const val BACKEND = "CPU"

        /** CPU 线程数：本机实测 4 线程比 2 线程快约两成。 */
        private const val INTRA_OP_THREADS = 4

        fun create(modelFile: File): OnnxEngine {
            require(modelFile.isFile) { "模型文件不存在：${modelFile.name}" }
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            options.setIntraOpNumThreads(INTRA_OP_THREADS)
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
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
                modelSize = modelSize
            )
        }

        private const val DEFAULT_MODEL_SIZE = 320
    }
}
