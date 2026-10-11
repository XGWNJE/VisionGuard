package com.xgwnje.visionguard.inference.model

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.Environment
import java.io.File

/**
 * 正式链路的引擎选择：档位定后端，用不了就回落到**同尺寸的 CPU 档**并说明原因。
 *
 * 为什么必须显式回落而不是直接选 CPU：设备可能没有可用的 GPU 加速器，截断图也可能还没下载完；
 * 两者都要给出可读原因，否则界面只能显示「CPU」，看不出是「没下 GPU 档」还是「设备不支持」。
 *
 * 为什么回落目标是同尺寸 INT8 而不是 FP32：CPU 上 INT8 快 1.5–2.0 倍、体积小 3–4 倍，
 * FP32 本体已经不再下发（见 [ModelKeys]）。
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

    /** 后端偏好：本次该不该走 GPU，以及不走时的可读原因。 */
    data class BackendPreference(val preferGpu: Boolean, val fallbackReason: String?)

    /**
     * 后端选择判定（纯函数，便于单测覆盖整张判定表）。
     *
     * 这是正式链路上「到底用没用 GPU」的唯一判据，三个输入都可能是「没有」：
     * GPU 档的截断图没下载、设备不支持 GPU 加速器、来源配置选的是 CPU 档。
     * 每种都要给出可读原因，否则界面只能显示 CPU，看不出是哪一种。
     */
    fun decide(
        wantGpu: Boolean,
        gpuFileExists: Boolean,
        gpuFileName: String?,
        gpuAcceleratorAvailable: Boolean,
        availableAccelerators: String
    ): BackendPreference {
        if (!wantGpu) return BackendPreference(preferGpu = false, fallbackReason = null)
        if (!gpuFileExists) {
            return BackendPreference(false, "GPU 档的截断图未就绪（${gpuFileName ?: "无"}），已回落 CPU 档")
        }
        if (!gpuAcceleratorAvailable) {
            return BackendPreference(
                false,
                "本机不支持 LiteRT GPU 加速器（可用：${availableAccelerators.ifEmpty { "无" }}），已回落 CPU 档"
            )
        }
        return BackendPreference(preferGpu = true, fallbackReason = null)
    }

    /**
     * 创建来源运行时的引擎。
     *
     * @param cpuOnnxFile 回落与 CPU 档使用的量化本体；GPU 档时调用方传同尺寸 CPU 档的文件
     * @param tfliteFile GPU 档的截断图；CPU 档为 null
     * @param wantGpu 是否优先使用 GPU（来源配置选的是 GPU 档时为 true）
     */
    fun create(cpuOnnxFile: File?, tfliteFile: File?, wantGpu: Boolean): Selection {
        val available = if (wantGpu) availableAccelerators() else emptySet()
        val preference = decide(
            wantGpu = wantGpu,
            gpuFileExists = tfliteFile?.isFile == true,
            gpuFileName = tfliteFile?.name,
            gpuAcceleratorAvailable = available.contains(Accelerator.GPU),
            availableAccelerators = available.joinToString(",") { it.name }
        )
        if (!preference.preferGpu) return cpuSelection(cpuOnnxFile, preference.fallbackReason)

        val gpu = runCatching {
            LitertAcceleratedEngine.create(tfliteFile!!, GPU_PROVIDER) as InferenceEngine
        }
        return gpu.fold(
            onSuccess = { Selection(it, GPU_PROVIDER, null) },
            onFailure = { error ->
                val reason = "GPU 模型加载失败（${error.message ?: error::class.java.simpleName}），已回落 CPU 档"
                // 没有可回落的 CPU 档时把 GPU 的失败原样抛出，由来源状态显示真实原因。
                runCatching { cpuSelection(cpuOnnxFile, reason) }.getOrElse { throw error }
            }
        )
    }

    private fun cpuSelection(cpuOnnxFile: File?, reason: String?): Selection {
        val file = cpuOnnxFile
            ?: error("没有可用的 CPU 档模型；请先下载同尺寸的 CPU 档")
        return Selection(OnnxEngine.create(file), InferenceProvider.production, reason)
    }

    /** 本机是否有可用的 GPU 加速器；判定与 [create] 同源，同一进程内只探测一次。 */
    fun hasGpuAccelerator(): Boolean = availableAccelerators().contains(Accelerator.GPU)

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
