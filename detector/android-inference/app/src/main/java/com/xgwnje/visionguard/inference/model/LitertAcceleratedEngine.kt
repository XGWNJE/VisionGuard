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
    val modelSize: Int,
    val provider: InferenceProvider,
    /** 环境实际报告的可用加速器，写进报告便于判断档位是否真的生效。 */
    val availableAccelerators: String
) {

    /** 后端标识：测量报告与心跳按它显示。 */
    val backend: String = provider.title

    /** 运行一次推理；[tensor] 为 CHW `float[3 * modelSize * modelSize]`。 */
    fun run(tensor: FloatArray): FloatArray {
        inputs.first().writeFloat(toNhwc(tensor))
        val produced = model.run(inputs)
        return (produced.firstOrNull() ?: outputs.first()).readFloat()
    }

    fun close() {
        runCatching { model.close() }
        runCatching { environment.close() }
    }

    private fun toNhwc(tensor: FloatArray): FloatArray {
        val result = FloatArray(3 * modelSize * modelSize)
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

            val model = CompiledModel.create(modelFile.absolutePath, options, environment)
            // 用带签名与张量名的重载：无参重载在不同构建里对默认名的解析不一致，
            // 实测无参重载会报 `Input tensor not found`。
            val inputType = model.getInputTensorType(inputName, signature)
            val dimensions = inputType.layout?.dimensions.orEmpty()
            val size = dimensions.getOrNull(1) ?: DEFAULT_MODEL_SIZE

            return LitertAcceleratedEngine(
                environment = environment,
                model = model,
                inputs = model.createInputBuffers(signature),
                outputs = model.createOutputBuffers(signature),
                modelSize = size,
                provider = provider,
                availableAccelerators = availableText
            )
        }

        private const val DEFAULT_MODEL_SIZE = 320
    }
}
