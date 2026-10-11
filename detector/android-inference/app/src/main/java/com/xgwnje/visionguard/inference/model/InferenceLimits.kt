package com.xgwnje.visionguard.inference.model

/**
 * 单机同一模型配置能同时跑几路来源（单路帧率 ≥5 帧/秒 为界）。
 *
 * 为什么按「同一模型 + 同一后端」计数而不是全机总数：每路来源各建一个独立会话，
 * 争用的是同一份算力。实测（Xiaomi 26048RP6AC / MT8910 / API 37，640 输入、真实实例图）：
 *
 * | 配置 | 上限 | 依据 |
 * |---|---|---|
 * | ONNX CPU 4 线程 + `yolo26m_640` | 2 路 | 2 路 6.4 帧/秒；4 路掉到 1.8 |
 * | ONNX CPU 4 线程 + `yolo26n_640` | 4 路 | 4 路 5.8 帧/秒；8 路掉到 3.3 |
 * | ONNX CPU 4 线程 + 320 档 | 32 路 | 32 路仍有 10.5 帧/秒，未触底 |
 * | LiteRT GPU + 截断图（任一 640 档） | 32 路 | 32 路仍有 15.4 帧/秒，受内存而非算力约束 |
 *
 * 数值是**实测下限**，不是理论值：GPU 只扫到 32 路未触底，所以按 32 记；更小内存的设备需重测。
 * 表里没有的组合按最保守的 2 路处理——宁可让用户先跑通，也不要让来源静默卡死。
 */
object InferenceLimits {

    /** 未在册组合的保守上限。 */
    const val CONSERVATIVE_LIMIT = 2

    /** 在册的每配置上限。 */
    private val limits: Map<String, Int> = buildMap {
        put(entry("yolo26m_640", gpu = false), 2)
        put(entry("yolo26n_640", gpu = false), 4)
        put(entry("yolo26n_320", gpu = false), 32)
        put(entry("yolo26s_320", gpu = false), 32)
        put(entry("yolo26m_320", gpu = false), 32)
        // 截断图在 GPU 上是排队执行：路数翻倍只降单路帧率，32 路仍未触底，故按 32 记。
        ModelKeys.gpuKeys.forEach { put(entry(it, gpu = true), 32) }
    }

    /** 该模型在该后端下的并发上限；未在册组合取保守值。 */
    fun perConfiguration(modelKey: String, gpu: Boolean): Int =
        limits[entry(modelKey, gpu)] ?: CONSERVATIVE_LIMIT

    /** 控制台与心跳用的键：`模型键@后端`。 */
    fun entry(modelKey: String, gpu: Boolean): String = "$modelKey@${if (gpu) "gpu" else "cpu"}"

    /**
     * 本端可宣告的全部上限，按 [ModelKeys.keys] 顺序、每个键含 CPU 与（有 GPU 产物时的）GPU 两条。
     * 控制台据此解释「为什么这个设备加不了更多来源」。
     */
    fun declaredLimits(): Map<String, Int> = buildMap {
        for (key in ModelKeys.keys) {
            put(entry(key, gpu = false), perConfiguration(key, gpu = false))
            if (ModelKeys.supportsGpu(key)) put(entry(key, gpu = true), perConfiguration(key, gpu = true))
        }
    }
}
