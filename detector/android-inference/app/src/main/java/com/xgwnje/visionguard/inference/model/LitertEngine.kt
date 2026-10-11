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
 * - LiteRT 的输入是 NHWC；本类把 CHW 转成 NHWC，调用方不用改预处理；
 * - **模型自带量化边界时按模型要求的类型喂数**：完整量化的模型（输入 `uint8`/`int8`）需要按
 *   量化参数换算，图内不再有 Quantize 节点。GPU / NPU 一类加速器通常只接受这种完整量化模型，
 *   所以这一条必须支持，否则用 float32 边界去试加速器会得出错误的"不支持"结论；
 * - 输出统一读成 float32 展平数组，交给同一套 `YoloOutputParser`。
 *
 * 加速档位（GPU / NPU）不在这个入口：LiteRT 2.x 用 `CompiledModel` 的加速器 API，而本分发里的
 * `InterpreterApi.Options` 只暴露 `setNumThreads` / `setUseXNNPACK`，没有 `addDelegate`。
 */
class LitertEngine private constructor(
    private val interpreter: Interpreter,
    /** 输出缓冲字节数：按模型实际输出张量算，不能写死。 */
    private val outputBytes: Int,
    private val inputKind: InputKind,
    private val outputKind: OutputKind,
    val modelSize: Int,
    val provider: InferenceProvider
) {

    /** 后端标识：测量报告与心跳按它显示。 */
    val backend: String = provider.title

    /** 模型边界的量化形态，写进报告便于判断是否与加速器要求匹配。 */
    val quantizationNote: String
        get() = "输入 $inputKind / 输出 $outputKind"

    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(inputByteCount())
        .order(ByteOrder.nativeOrder())

    private val outputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(outputBytes)
        .order(ByteOrder.nativeOrder())

    /** 运行一次推理；[tensor] 为 CHW `float[3 * modelSize * modelSize]`。 */
    fun run(tensor: FloatArray): FloatArray {
        writeInput(tensor)
        // 位置与 limit 都归零：输入缓冲在类初始化时是满 limit，写完后位置停在末尾；
        // 不重置就会让 native 从错误位置读输入。
        inputBuffer.rewind()
        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)
        return readOutput()
    }

    fun close() {
        runCatching { interpreter.close() }
    }

    private fun inputByteCount(): Int {
        val floats = 1 * modelSize * modelSize * 3
        return when (inputKind) {
            InputKind.FLOAT32 -> floats * Float.SIZE_BYTES
            is InputKind.Quantized -> floats
        }
    }

    /** CHW float → NHWC；再按模型要求的输入类型写入缓冲。 */
    private fun writeInput(tensor: FloatArray) {
        inputBuffer.rewind()
        val plane = modelSize * modelSize
        when (val kind = inputKind) {
            InputKind.FLOAT32 -> {
                val floats = inputBuffer.asFloatBuffer()
                for (y in 0 until modelSize) {
                    val row = y * modelSize
                    for (x in 0 until modelSize) {
                        val pixel = row + x
                        val target = pixel * 3
                        floats.put(target, tensor[pixel])
                        floats.put(target + 1, tensor[plane + pixel])
                        floats.put(target + 2, tensor[2 * plane + pixel])
                    }
                }
            }

            is InputKind.Quantized -> {
                for (y in 0 until modelSize) {
                    val row = y * modelSize
                    for (x in 0 until modelSize) {
                        val pixel = row + x
                        val target = (pixel * 3)
                        inputBuffer.put(target, quantize(tensor[pixel], kind))
                        inputBuffer.put(target + 1, quantize(tensor[plane + pixel], kind))
                        inputBuffer.put(target + 2, quantize(tensor[2 * plane + pixel], kind))
                    }
                }
            }
        }
    }

    private fun readOutput(): FloatArray {
        // 读取前必须把位置归零：`interpreter.run()` 会写满缓冲并让位置停在末尾，
        // 此时按视图的当前 `position` 取值就是越界读（真机上表现为 BufferUnderflowException）。
        // 元素数与字节数一律以张量自报的 numBytes 为准，不再自己按形状推算。
        val count = outputBytes / when (outputKind) {
            OutputKind.FLOAT32 -> Float.SIZE_BYTES
            is OutputKind.Quantized -> (outputKind as OutputKind.Quantized).bytes
        }
        outputBuffer.rewind()
        return when (val kind = outputKind) {
            OutputKind.FLOAT32 -> {
                val raw = FloatArray(count)
                // 批量读而不是逐元素绝对读：`ByteBufferAsFloatBuffer` 在 JVM 上按元素对齐取 idx，
                // 绝对索引落在奇数字节上会读错位置；批量 get 一次拷完，也与 native 写入顺序一致。
                outputBuffer.asFloatBuffer().get(raw)
                raw
            }

            is OutputKind.Quantized -> FloatArray(count) { index ->
                dequantize(outputBuffer.get(index), kind)
            }
        }
    }

    private fun quantize(value: Float, kind: InputKind.Quantized): Byte {
        val scaled = value / kind.scale + kind.zeroPoint
        val clamped = scaled.coerceIn(kind.min, kind.max)
        return clamped.toInt().toByte()
    }

    private fun dequantize(value: Byte, kind: OutputKind.Quantized): Float {
        val stored = when (kind) {
            is OutputKind.Unsigned -> (value.toInt() and 0xFF).toFloat()
            is OutputKind.Signed -> value.toFloat()
        }
        return (stored - kind.zeroPoint) * kind.scale
    }

    /** 模型输入张量的边界类型。 */
    sealed interface InputKind {
        data object FLOAT32 : InputKind

        data class Quantized(
            val scale: Float,
            val zeroPoint: Float,
            /** 该字节类型能表示的最小 / 最大整数值（用于饱和裁剪）。 */
            val min: Float,
            val max: Float,
            val label: String
        ) : InputKind
    }

    /** 模型输出张量的边界类型。 */
    sealed interface OutputKind {
        data object FLOAT32 : OutputKind

        sealed interface Quantized : OutputKind {
            val scale: Float
            val zeroPoint: Float
            val bytes: Int
        }

        data class Unsigned(override val scale: Float, override val zeroPoint: Float) : Quantized {
            override val bytes: Int get() = 1
        }

        data class Signed(override val scale: Float, override val zeroPoint: Float) : Quantized {
            override val bytes: Int get() = 1
        }
    }

    companion object {
        /** 输出缓冲上限：`yolo26` 端到端是 `[1,300,6]`，锚点档是 `[1,84,8400]`，留足余量。 */
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
            // XNNPACK 是官方 LiteRT 的 CPU 加速路径，显式开一档（不要靠构建默认值）。
            options.setUseXNNPACK(provider.usesXnnpack)

            val interpreter = Interpreter(modelFile, options)
            val inputTensor = interpreter.getInputTensor(0)
            val shape = inputTensor.shape() // NHWC
            val size = if (shape.size >= 3 && shape[1] > 0) shape[1] else DEFAULT_MODEL_SIZE

            // 输出缓冲按实际输出张量算：锚点档输出比端到端档大得多，写死会直接报
            // `Cannot copy from a TensorFlowLite tensor ... to a Java Buffer`。
            val outputTensor = interpreter.getOutputTensor(0)
            val outputShape = outputTensor.shape()
            val elements = outputShape.fold(1L) { acc, dim -> acc * dim.coerceAtLeast(0) }.coerceAtLeast(1L)

            val inputKind = inputKindOf(inputTensor)
            val outputKind = outputKindOf(outputTensor)
            val bytesPerElement = when (outputKind) {
                OutputKind.FLOAT32 -> Float.SIZE_BYTES
                is OutputKind.Quantized -> 1
            }
            val bytes = (elements * bytesPerElement).coerceAtMost(MAX_OUTPUT_BYTES.toLong()).toInt()

            return LitertEngine(
                interpreter = interpreter,
                outputBytes = bytes,
                inputKind = inputKind,
                outputKind = outputKind,
                modelSize = size,
                provider = provider
            )
        }

        private fun inputKindOf(tensor: org.tensorflow.lite.Tensor): InputKind {
            val params = tensor.quantizationParams()
            val scale = params.scale
            val zeroPoint = params.zeroPoint
            if (tensor.dataType() == org.tensorflow.lite.DataType.FLOAT32 || scale <= 0f) {
                return InputKind.FLOAT32
            }
            return when (tensor.dataType()) {
                org.tensorflow.lite.DataType.UINT8 -> InputKind.Quantized(
                    scale = scale,
                    zeroPoint = zeroPoint.toFloat(),
                    min = 0f,
                    max = 255f,
                    label = "uint8 量化(scale=$scale, zero=$zeroPoint)"
                )

                org.tensorflow.lite.DataType.INT8 -> InputKind.Quantized(
                    scale = scale,
                    zeroPoint = zeroPoint.toFloat(),
                    min = -128f,
                    max = 127f,
                    label = "int8 量化(scale=$scale, zero=$zeroPoint)"
                )

                else -> InputKind.FLOAT32
            }
        }

        private fun outputKindOf(tensor: org.tensorflow.lite.Tensor): OutputKind {
            val params = tensor.quantizationParams()
            val scale = params.scale
            val zeroPoint = params.zeroPoint
            if (tensor.dataType() == org.tensorflow.lite.DataType.FLOAT32 || scale <= 0f) {
                return OutputKind.FLOAT32
            }
            return when (tensor.dataType()) {
                org.tensorflow.lite.DataType.UINT8 -> OutputKind.Unsigned(scale, zeroPoint.toFloat())
                org.tensorflow.lite.DataType.INT8 -> OutputKind.Signed(scale, zeroPoint.toFloat())
                else -> OutputKind.FLOAT32
            }
        }

        private const val DEFAULT_MODEL_SIZE = 320
    }
}
