package com.xgwnje.visionguard.inference.model

import com.google.ai.edge.litert.Accelerator
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * LiteRT（原 TFLite）会话封装，与 [OnnxEngine] 提供同一套语义：CHW float 输入、展平 float 输出。
 *
 * 为什么要有这个类：ONNX Runtime 的 Android 包只注册 CPU 与 NNAPI，而 NNAPI 会把图中的后处理
 * 切碎、跨设备搬运，真机实测比 CPU 慢 5–6 倍。LiteRT 是另一个运行时，量化模型用它的整数内核
 * 单独跑一遍才能在真机上对比两个引擎。
 *
 * 输入输出差别由本类吸收：
 * - LiteRT 的输入是 NHWC float32；本类把 CHW 转成 NHWC，调用方不用改预处理；
 * - 若模型内部已把输入量化成 int8，仍按 float32 送数（图里有 Quantize 节点），刻度自动生效；
 * - 输出统一读成 float32 展平数组，交给同一套 `YoloOutputParser`。
 *
 * 加速档位（GPU / NPU）不在这个入口：LiteRT 2.x 用 `CompiledModel` 的加速器 API，而本分发里的
 * `InterpreterApi.Options` 只暴露 `setNumThreads`，没有 `addDelegate`。接入加速器见专题文档。
 */
class LitertEngine private constructor(
    private val interpreter: Interpreter,
    /** 输出缓冲字节数：按模型实际输出张量算，不能写死。 */
    private val outputBytes: Int,
    val modelSize: Int,
    val provider: InferenceProvider
) {

    /** 后端标识：测量报告与心跳按它显示。 */
    val backend: String = provider.title

    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(1 * modelSize * modelSize * 3 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())

    private val outputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(outputBytes)
        .order(ByteOrder.nativeOrder())

    /** 运行一次推理；[tensor] 为 CHW `float[3 * modelSize * modelSize]`。 */
    fun run(tensor: FloatArray): FloatArray {
        inputBuffer.rewind()
        val floats = inputBuffer.asFloatBuffer()
        // CHW -> NHWC：LiteRT 的输入布局与 ONNX 不同，在这里吸收掉。
        val plane = modelSize * modelSize
        for (y in 0 until modelSize) {
            val row = y * modelSize
            for (x in 0 until modelSize) {
                val pixel = row + x
                floats.put(tensor[pixel])
                floats.put(tensor[plane + pixel])
                floats.put(tensor[2 * plane + pixel])
            }
        }
        inputBuffer.rewind()

        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        val raw = FloatArray(outputBuffer.remaining() / Float.SIZE_BYTES)
        outputBuffer.asFloatBuffer().get(raw)
        return raw
    }

    fun close() {
        runCatching { interpreter.close() }
    }

    companion object {
        /** 输出缓冲上限：`yolo26` 端到端是 `[1,300,6]`，锚点档是 `[1,84,2100]`，留足余量。 */
        private const val MAX_OUTPUT_BYTES = 84 * 8400 * Float.SIZE_BYTES

        /** 支持的模型扩展名：LiteRT 只吃 `.tflite`。 */
        const val MODEL_EXTENSION = ".tflite"

        fun create(modelFile: File, provider: InferenceProvider): LitertEngine {
            require(modelFile.isFile) { "模型文件不存在：${modelFile.name}" }
            require(provider.runtime == InferenceRuntimeKind.LITERT) {
                "档位 ${provider.title} 不属于 LiteRT"
            }
            require(!provider.usesGpuDelegate) {
                "档位 ${provider.title} 需要 CompiledModel 加速器 API，当前入口未接入"
            }

            val options = Interpreter.Options()
            provider.cpuThreads?.let { options.setNumThreads(it) }

            val interpreter = Interpreter(modelFile, options)
            val shape = interpreter.getInputTensor(0).shape() // NHWC
            val size = if (shape.size >= 3 && shape[1] > 0) shape[1] else DEFAULT_MODEL_SIZE

            // 输出缓冲按实际输出张量算：锚点档输出比端到端档大得多，写死会直接报
            // `Cannot copy from a TensorFlowLite tensor ... to a Java Buffer`。
            val outputShape = interpreter.getOutputTensor(0).shape()
            val elements = outputShape.fold(1L) { acc, dim -> acc * dim.coerceAtLeast(0) }.coerceAtLeast(1L)
            val bytes = (elements * Float.SIZE_BYTES).coerceAtMost(MAX_OUTPUT_BYTES.toLong()).toInt()

            return LitertEngine(
                interpreter = interpreter,
                outputBytes = bytes,
                modelSize = size,
                provider = provider
            )
        }

        private const val DEFAULT_MODEL_SIZE = 320
    }
}
