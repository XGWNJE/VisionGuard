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
 */
class LitertAcceleratedEngine private constructor(
    private val environment: Environment,
    private val model: CompiledModel,
    private val inputs: List<TensorBuffer>,
    private val outputs: List<TensorBuffer>,
    val modelSize: Int,
    val provider: InferenceProvider,
    /** 环境实际报告的可用加速器，写进失败说明与报告。 */
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
                val target = (row + x) * 3
                result[target] = tensor[pixel]
                result[target + 1] = tensor[plane + pixel]
                result[target + 2] = tensor[2 * plane + pixel]
            }
        }
        return result
    }

    companion object {
        fun create(modelFile: File, provider: InferenceProvider): LitertAcceleratedEngine {
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
            provider.gpuPrecision?.let { precision ->
                options.gpuOptions = CompiledModel.GpuOptions(precision = precision)
            }

            val model = CompiledModel.create(modelFile.absolutePath, options, environment)
            // 空串表示「主图第一个张量 / 无签名」，与库内默认值一致。
            val inputType = model.getInputTensorType("", "")
            val dimensions = inputType.layout?.dimensions.orEmpty()
            val size = dimensions.getOrNull(1) ?: DEFAULT_MODEL_SIZE

            return LitertAcceleratedEngine(
                environment = environment,
                model = model,
                inputs = model.createInputBuffers(),
                outputs = model.createOutputBuffers(),
                modelSize = size,
                provider = provider,
                availableAccelerators = availableText
            )
        }

        private const val DEFAULT_MODEL_SIZE = 320
    }
}
