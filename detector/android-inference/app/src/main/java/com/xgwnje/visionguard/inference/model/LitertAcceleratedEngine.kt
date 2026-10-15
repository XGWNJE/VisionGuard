package com.xgwnje.visionguard.inference.model

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.File

/**
 * LiteRT 2.x `CompiledModel` 会话：这是**唯一能吃到 GPU / NPU 的 LiteRT 入口**。
 *
 * 为什么不用老的 `Interpreter`：本次分发的 `InterpreterApi.Options` 只暴露 `setNumThreads`，
 * 没有 `addDelegate`，GPU delegate 在编译期就接不上（已实测）。`CompiledModel` 用
 * `Accelerator` 枚举（CPU / GPU / NPU）选择加速器，`Environment.getAvailableAccelerators()`
 * 还能在运行前问出本机到底支持哪些——不支持时明确失败，不静默回落 CPU。
 *
 * 输入输出语义与 [OnnxEngine] 一致：CHW float 进、展平 float 出；CHW→NHWC 在这里吸收。
 *
 * 张量名与签名取模型自带的（`serving_default` / `images` / `output0`），换模型只需换这三个值；
 * 名字错了会立刻抛 `Input tensor not found`，不会静默读错张量。
 */
class LitertAcceleratedEngine private constructor(
    private val environment: Environment,
    private val model: CompiledModel,
    private val inputs: List<TensorBuffer>,
    private val outputs: List<TensorBuffer>,
    override val modelSize: Int,
    val provider: InferenceProvider,
    /** 环境实际报告的可用加速器，写进报告便于判断档位是否真的生效。 */
    val availableAccelerators: String,
    /** CPU 档实际生效的 XNNPACK / 线程设置，写进报告避免「有没有开」靠猜。 */
    val cpuNote: String = "",
    /** 模型输入的边界类型与量化参数（读不出就按 float32 处理）。 */
    private val inputInfo: TfliteQuantization.TensorInfo? = null,
    /** 模型输出的边界类型与量化参数。 */
    private val outputInfo: TfliteQuantization.TensorInfo? = null
) : InferenceEngine {

    /** 后端标识：测量报告与心跳按它显示。 */
    override val backend: String = provider.title

    /** 该档是否走 GPU 加速器；加速器 API 的 CPU 档不算加速。 */
    override val accelerated: Boolean = provider.usesGpuDelegate

    /** 后端细节：加速器可见性 + 边界精度（+ CPU 档的 XNNPACK 设置）。 */
    override val backendNote: String = buildString {
        append("输入 ${modelSize}px，可用加速器 ${availableAccelerators.ifEmpty { "无" }}")
        append("，").append(quantizationNote())
        if (cpuNote.isNotEmpty()) append("，").append(cpuNote)
    }

    private val inputQuantized: Boolean get() = inputInfo?.quantized == true
    private val outputQuantized: Boolean get() = outputInfo?.quantized == true
    private val inputUnsigned: Boolean get() = inputInfo?.unsigned == true
    private val outputUnsigned: Boolean get() = outputInfo?.unsigned == true
    private val inputScales: List<Float> get() = inputInfo?.scales.orEmpty()
    private val inputZeroPoints: List<Int> get() = inputInfo?.zeroPoints.orEmpty()
    private val outputScales: List<Float> get() = outputInfo?.scales.orEmpty()
    private val outputZeroPoints: List<Int> get() = outputInfo?.zeroPoints.orEmpty()

    /**
     * 运行一次推理；[tensor] 为 CHW `float[3 * modelSize * modelSize]`。
     *
     * 统一量化模型（int8 / uint8 边界）与 float32 模型共用这个入口：端侧只认 float 张量，
     * 边界的量化换算在这里吸收。**必须**按模型自报的 scale 换算——按 float32 尺寸写缓冲会报
     * `TensorBuffer host memory buffer size is smaller than the given data size`。
     */
    override fun run(tensor: FloatArray): FloatArray {
        val nhwc = toNhwc(tensor)
        if (inputQuantized) {
            inputs.first().writeInt8(quantize(nhwc))
        } else {
            inputs.first().writeFloat(nhwc)
        }
        model.run(inputs, outputs)
        val produced = outputs.first()
        return if (outputQuantized) dequantize(produced.readInt8()) else produced.readFloat()
    }

    override fun close() {
        outputs.forEach { runCatching { it.close() } }
        inputs.forEach { runCatching { it.close() } }
        runCatching { model.close() }
        runCatching { environment.close() }
    }

    /** 模型边界的量化形态，写进报告便于判断是否与加速器要求匹配。 */
    fun quantizationNote(): String =
        "输入 ${if (inputQuantized) "量化(${inputScales.firstOrNull() ?: 0f})" else "FLOAT32"} / " +
            "输出 ${if (outputQuantized) "量化(${outputScales.firstOrNull() ?: 0f})" else "FLOAT32"}"

    private fun quantize(values: FloatArray): ByteArray {
        val scales = inputScales.ifEmpty { listOf(1f) }
        val zeroPoints = inputZeroPoints.ifEmpty { listOf(0) }
        val low = if (inputUnsigned) 0f else -128f
        val high = if (inputUnsigned) 255f else 127f
        return ByteArray(values.size) { index ->
            val channel = if (scales.size > 1) index % scales.size else 0
            val scaled = values[index] / scales[channel] + zeroPoints.getOrElse(channel) { 0 }
            scaled.coerceIn(low, high).toInt().toByte()
        }
    }

    private fun dequantize(values: ByteArray): FloatArray {
        val scales = outputScales.ifEmpty { listOf(1f) }
        val zeroPoints = outputZeroPoints.ifEmpty { listOf(0) }
        return FloatArray(values.size) { index ->
            val channel = if (scales.size > 1) index % scales.size else 0
            val stored = if (outputUnsigned) (values[index].toInt() and 0xFF).toFloat() else values[index].toFloat()
            (stored - zeroPoints.getOrElse(channel) { 0 }) * scales[channel]
        }
    }

    private val nhwcScratch = FloatArray(3 * modelSize * modelSize)

    private fun toNhwc(tensor: FloatArray): FloatArray {
        val result = nhwcScratch
        val plane = modelSize * modelSize
        for (y in 0 until modelSize) {
            val row = y * modelSize
            for (x in 0 until modelSize) {
                val pixel = row + x
                val target = pixel * 3
                result[target] = tensor[pixel]
                result[target + 1] = tensor[plane + pixel]
                result[target + 2] = tensor[2 * plane + pixel]
            }
        }
        return result
    }

    companion object {
        /** 默认张量名与签名：export 出来的 LiteRT 模型固定用这一套。 */
        const val DEFAULT_SIGNATURE = "serving_default"
        const val DEFAULT_INPUT_NAME = "images"

        fun create(
            modelFile: File,
            provider: InferenceProvider,
            signature: String = DEFAULT_SIGNATURE,
            inputName: String = DEFAULT_INPUT_NAME
        ): LitertAcceleratedEngine {
            require(modelFile.isFile) { "模型文件不存在：${modelFile.name}" }
            require(provider.runtime == InferenceRuntimeKind.LITERT) {
                "档位 ${provider.title} 不属于 LiteRT"
            }
            val accelerator = provider.litertAccelerator
                ?: error("档位 ${provider.title} 没有指定 LiteRT 加速器")

            val environment = Environment.create()
            var allocatedModel: CompiledModel? = null
            var allocatedInputs: List<TensorBuffer> = emptyList()
            var allocatedOutputs: List<TensorBuffer> = emptyList()
            try {
                // 先问本机支持什么：不支持就不要假装测过，直接把可用列表写进失败原因。
                val available = runCatching { environment.getAvailableAccelerators() }.getOrDefault(emptySet())
                val availableText = available.joinToString(",") { it.name }
                check(available.contains(accelerator)) {
                    "本机不支持 LiteRT 加速器 ${accelerator.name}（可用：${availableText.ifEmpty { "无" }}）"
                }

                val options = CompiledModel.Options(accelerator)
                when (provider) {
                    InferenceProvider.LITERT_GPU_FP16 ->
                        options.gpuOptions = CompiledModel.GpuOptions(
                            precision = CompiledModel.GpuOptions.Precision.FP16
                        )

                    InferenceProvider.LITERT_GPU_TEXTURE ->
                        options.gpuOptions = CompiledModel.GpuOptions(
                            preferTextureWeights = true,
                            externalTensorsMode = false
                        )

                    InferenceProvider.LITERT_GPU_NO_EXT ->
                        options.gpuOptions = CompiledModel.GpuOptions(externalTensorsMode = false)

                    else -> Unit
                }

                // CPU 加速器：官方 CPU 路径是 XNNPACK，这里显式给 flags 或显式关掉，
                // 不靠库默认值——否则「XNNPACK 到底有没有生效」只能猜。
                val cpuNote = if (accelerator == Accelerator.CPU) {
                    when {
                        provider.disablesXnnpack -> {
                            options.cpuOptions = CompiledModel.CpuOptions(
                                numThreads = DEFAULT_CPU_THREADS,
                                xnnPackFlags = XNNPACK_DISABLED_FLAGS
                            )
                            "XNNPACK 关闭，线程 $DEFAULT_CPU_THREADS"
                        }

                        provider.usesXnnpack -> {
                            options.cpuOptions = CompiledModel.CpuOptions(
                                numThreads = provider.cpuThreads ?: DEFAULT_CPU_THREADS,
                                xnnPackFlags = XNNPACK_DEFAULT_FLAGS
                            )
                            "XNNPACK flags=$XNNPACK_DEFAULT_FLAGS，线程 ${provider.cpuThreads ?: DEFAULT_CPU_THREADS}"
                        }

                        else -> ""
                    }
                } else {
                    ""
                }

                val model = CompiledModel.create(modelFile.absolutePath, options, environment).also { allocatedModel = it }
                // 用带签名与张量名的重载：无参重载在不同构建里对默认名的解析不一致，
                // 实测无参重载会报 `Input tensor not found`。
                val inputType = model.getInputTensorType(inputName, signature)
                val dimensions = inputType.layout?.dimensions.orEmpty()
                val size = dimensions.getOrNull(1) ?: DEFAULT_MODEL_SIZE

                // 量化参数只能从模型文件读：`TensorType` 只有 elementType 与 layout。
                val quantization = TfliteQuantization.read(modelFile)

                return LitertAcceleratedEngine(
                    environment = environment,
                    model = model,
                    inputs = model.createInputBuffers(signature).also { allocatedInputs = it },
                    outputs = model.createOutputBuffers(signature).also { allocatedOutputs = it },
                    modelSize = size,
                    provider = provider,
                    availableAccelerators = availableText,
                    cpuNote = cpuNote,
                    // 量化参数从模型文件里读：CompiledModel 的 TensorType 不暴露 scale / zero point，
                    // 而统一量化模型必须按 scale 把 float 换算成整数才能写进缓冲。
                    inputInfo = quantization?.first,
                    outputInfo = quantization?.second
                )
            } catch (error: Throwable) {
                allocatedOutputs.forEach { runCatching { it.close() } }
                allocatedInputs.forEach { runCatching { it.close() } }
                runCatching { allocatedModel?.close() }
                runCatching { environment.close() }
                throw error
            }
        }

        /** XNNPACK 默认配置：交给库按位掩码决定（全位置位）。 */
        private const val XNNPACK_DEFAULT_FLAGS = -1

        /** 关闭 XNNPACK：位掩码为 0。 */
        private const val XNNPACK_DISABLED_FLAGS = 0

        private const val DEFAULT_CPU_THREADS = 4
        private const val DEFAULT_MODEL_SIZE = 320
    }
}
