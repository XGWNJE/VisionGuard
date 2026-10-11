package com.xgwnje.visionguard.inference.model

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.Environment
import java.io.File

/**
 * 正式链路的引擎选择：能用 GPU 就用 GPU，用不了就如实回落 CPU 并说明原因。
 *
 * 为什么必须显式回落而不是直接选 CPU：这台设备的 GPU 只在**截断图**（图尾无 int64 后处理）
 * 上编得过，普通图会报 `Failed to compile model`；模型文件也可能还没下载。两者都要给出可读原因，
 * 否则界面只能显示「CPU」，看不出是「没装 GPU 模型」还是「设备不支持」。
 *
 * 为什么 GPU 只走「加速器 API GPU + FP32」这一档：真机同轮实测 int8 边界在 GPU 上比 FP32 慢
 * 1.58 倍且类别分数被压成 0（混量程张量），纹理权重与关外部张量两档也没有更快，
 * 结论见 `docs/32-推理框架与模型优化.md`。
 */
object InferenceEngineFactory {

    /** GPU 档固定用这一档：质量零损失、同轮最快。 */
    val GPU_PROVIDER: InferenceProvider = InferenceProvider.LITERT_GPU

    /** 选择结果：真正生效的引擎、档位，以及回落原因（没回落时为 null）。 */
    data class Selection(
        val engine: InferenceEngine,
        val provider: InferenceProvider,
        val fallbackReason: String?
    )

    /**
     * 创建来源运行时的引擎。
     *
     * @param onnxFile 本体模型（正式档走 CPU 时使用）
     * @param tfliteFile GPU 产物；为 null 或文件缺失时回落 CPU
     * @param wantGpu 是否优先使用 GPU（来源配置与设备能力都满足时为 true）
     */
    fun create(onnxFile: File, tfliteFile: File?, wantGpu: Boolean): Selection {
        if (!wantGpu) {
            return Selection(OnnxEngine.create(onnxFile), InferenceProvider.production, null)
        }

        if (tfliteFile == null || !tfliteFile.isFile) {
            return Selection(
                OnnxEngine.create(onnxFile),
                InferenceProvider.production,
                "未下载 GPU 模型（${tfliteFile?.name ?: "无"}），已用 CPU"
            )
        }

        // 先问设备支持哪些加速器：不支持就不必付编译失败的代价。
        val available = availableAccelerators()
        if (!available.contains(Accelerator.GPU)) {
            return Selection(
                OnnxEngine.create(onnxFile),
                InferenceProvider.production,
                "本机不支持 LiteRT GPU 加速器（可用：${available.joinToString(",") { it.name }.ifEmpty { "无" }}），已用 CPU"
            )
        }

        val gpu = runCatching {
            LitertAcceleratedEngine.create(tfliteFile, GPU_PROVIDER) as InferenceEngine
        }
        return gpu.fold(
            onSuccess = { Selection(it, GPU_PROVIDER, null) },
            onFailure = { error ->
                Selection(
                    OnnxEngine.create(onnxFile),
                    InferenceProvider.production,
                    "GPU 模型加载失败（${error.message ?: error::class.java.simpleName}），已用 CPU"
                )
            }
        )
    }

    /**
     * 本机可见的 LiteRT 加速器；同一进程内结果固定，问一次就够。
     *
     * 失败时返回空集合：拿不到信息时按「不支持」处理，会回落 CPU 而不是盲目试编译。
     */
    fun availableAccelerators(): Set<Accelerator> = cached ?: runCatching {
        Environment.create().use { it.getAvailableAccelerators() }
    }.getOrDefault(emptySet()).also { cached = it }

    @Volatile
    private var cached: Set<Accelerator>? = null

    /** 单测用：清掉缓存，让下一次调用重新探测。 */
    fun resetCacheForTest() {
        cached = null
    }
}
