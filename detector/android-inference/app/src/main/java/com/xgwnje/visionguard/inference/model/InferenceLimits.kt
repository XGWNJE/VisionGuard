package com.xgwnje.visionguard.inference.model

/**
 * 单机同一档位能同时跑几路来源（单路帧率 ≥5 帧/秒 为界）。
 *
 * 为什么按「档位」计数而不是全机总数：每路来源各建一个独立会话，争用的是同一份算力。
 * 实测（Xiaomi 26048RP6AC / MT8910 / API 37，320 与 640 输入、真实实例图，8 帧多路扫描）：
 *
 * | 配置 | 上限 | 依据（同轮单路延迟中位） |
 * |---|---|---|
 * | ONNX CPU 4 线程 + `yolo26m_320_int8` | 4 路 | 4 路 159.5 ms（6.3 帧/秒 ✓）；8 路 322.1 ms（3.1 ✗） |
 * | ONNX CPU 4 线程 + `yolo26m_640_int8` | 2 路 | 2 路 151.4 ms（6.6 ✓）；4 路 548.0 ms（1.8 ✗） |
 * | LiteRT GPU + `yolo26m_320_gpu.tflite` | 32 路（理论封顶） | 8 / 16 / 32 路单路 21.5 / 21.1 / 21.0 ms，未触底 |
 * | LiteRT GPU + `yolo26m_640_gpu.tflite` | 32 路（理论封顶） | 32 路仍 15.4 帧/秒，受内存而非算力约束 |
 *
 * 两个用户档位按实际后端采用上述限制；GPU 回落后按同尺寸 CPU 限制重新校验。
 */
object InferenceLimits {

    /** 未在册组合的保守上限。 */
    const val CONSERVATIVE_LIMIT = 2

    /** 在册的每档位上限。 */
    private val limits: Map<String, Int> = buildMap {
        put(entry("yolo26m_320", gpu = false), 4)
        put(entry("yolo26m_640", gpu = false), 2)
        // 截断图在 GPU 上是排队执行：路数翻倍只降单路帧率，32 路仍未触底，按理论上限 32 封顶。
        ModelKeys.gpuKeys.forEach { put(entry(it, gpu = true), 32) }
    }

    /** 该档位在该后端下的并发上限；未在册组合取保守值。 */
    fun perConfiguration(modelKey: String, gpu: Boolean): Int =
        limits[entry(modelKey, gpu)] ?: CONSERVATIVE_LIMIT

    /** 控制台与心跳用的键：`模型键@后端`。 */
    fun entry(modelKey: String, gpu: Boolean): String = "$modelKey@${if (gpu) "gpu" else "cpu"}"

    /**
     * 本端可宣告的全部上限，按 [ModelKeys.keys] 顺序、每个键含 CPU 与（GPU 档的）GPU 两条。
     * 控制台据此解释「为什么这个设备加不了更多来源」。
     */
    fun declaredLimits(): Map<String, Int> = buildMap {
        for (key in ModelKeys.keys) {
            put(entry(key, gpu = false), perConfiguration(key, gpu = false))
            if (ModelKeys.supportsGpu(key)) put(entry(key, gpu = true), perConfiguration(key, gpu = true))
        }
    }
}
