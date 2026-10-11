package com.xgwnje.visionguard.inference.model

/**
 * 单机同一档位能同时跑几路来源（单路帧率 ≥5 帧/秒 为界）。
 *
 * 为什么按「档位」计数而不是全机总数：每路来源各建一个独立会话，争用的是同一份算力。
 * 已实测（Xiaomi 26048RP6AC / MT8910 / API 37，640 输入、真实实例图）：
 *
 * | 配置 | 上限 | 依据 |
 * |---|---|---|
 * | ONNX CPU 4 线程 + `yolo26m_640`（FP32，已下架） | 2 路 | 2 路 6.4 帧/秒；4 路掉到 1.8 |
 * | LiteRT GPU + 截断图 `yolo26m_640_gpu.tflite` | 32 路 | 32 路仍 15.4 帧/秒，受内存而非算力约束 |
 *
 * **INT8 两档还没有多路读数**：单路中位是 320 的 65.7 ms、640 的 219.9 ms（同轮），
 * 但压力测量没做过，所以这里不登记、按保守值拦截，等真机补测后回填
 * （落点见 `docs/06-任务清单.md` 第 11 项）。数值一律是**实测下限**，不是理论值。
 */
object InferenceLimits {

    /** 未在册组合的保守上限。 */
    const val CONSERVATIVE_LIMIT = 2

    /** 在册的每档位上限。 */
    private val limits: Map<String, Int> = buildMap {
        // 截断图在 GPU 上是排队执行：路数翻倍只降单路帧率，32 路仍未触底，故按 32 记。
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
