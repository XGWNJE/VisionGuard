package com.xgwnje.visionguard.inference.detect

/**
 * ONNX 原始输出解析；语义与 Windows 端 `Inference/YoloOutputParser.cs` 一致。
 *
 * 形态只按展平长度判定，两者互不整除，判定唯一：
 * - `300 * 6 = 1800` → YOLO26 端到端 `[1,300,6]`，模型内含 NMS，只做阈值与类别过滤；
 * - `84 * 2100 / 84 * 8400` → YOLOv5u `[1,84,N]`，原始锚点，需要解析侧逐类 NMS。
 *
 * 无法识别的长度直接抛异常，绝不按另一档下标硬读（那会静默出错框）。
 * 两档统一最多返回 5 个检测框，按置信度降序。
 */
object YoloOutputParser {

    /** 输出张量形态。 */
    enum class OutputLayout { Yolo26EndToEnd, YoloV5Anchors }

    private const val YOLO26_DETECTIONS = 300
    private const val YOLO26_VALUES_PER_BOX = 6
    private const val YOLO26_CONFIDENCE_INDEX = 4
    private const val YOLO26_CLASS_INDEX = 5

    private const val YOLOV5_CHANNELS = 84
    private const val YOLOV5_BOX_CHANNELS = 4
    private const val YOLOV5_ANCHORS_AT_320 = 2100
    private const val YOLOV5_ANCHORS_AT_640 = 8400

    private const val NMS_IOU_THRESHOLD = 0.45f
    private const val MAX_CANDIDATES_FOR_NMS = 3000
    private const val MAX_DETECTIONS = 5

    /** 按输出长度推断形态；不符合任一档契约时抛异常。 */
    fun detect(rawLength: Int): OutputLayout {
        if (rawLength == YOLO26_DETECTIONS * YOLO26_VALUES_PER_BOX) return OutputLayout.Yolo26EndToEnd

        if (rawLength > 0 && rawLength % YOLOV5_CHANNELS == 0) {
            val anchors = rawLength / YOLOV5_CHANNELS
            if (anchors == YOLOV5_ANCHORS_AT_320 || anchors == YOLOV5_ANCHORS_AT_640) {
                return OutputLayout.YoloV5Anchors
            }
        }

        throw IllegalArgumentException(
            "无法识别的模型输出张量：展平长度 $rawLength 既不匹配 YOLO26 的 [1,300,6]，也不匹配 YOLOv5 的 [1,84,N]。" +
                "请确认所选模型与本机推理链一致。"
        )
    }

    /** 输出张量的可读形态文本，用于诊断。 */
    fun describe(rawLength: Int): String = when (detect(rawLength)) {
        OutputLayout.Yolo26EndToEnd -> "[1,$YOLO26_DETECTIONS,$YOLO26_VALUES_PER_BOX]"
        OutputLayout.YoloV5Anchors -> "[1,$YOLOV5_CHANNELS,${rawLength / YOLOV5_CHANNELS}]"
    }

    /** 解析分支名称：说明 NMS 来自模型还是解析侧。 */
    fun describeBranch(rawLength: Int): String =
        if (detect(rawLength) == OutputLayout.Yolo26EndToEnd) "YOLO26 端到端（模型内置 NMS）" else "YOLOv5 锚点（解析侧 NMS）"

    /**
     * 解析 ONNX 原始输出为检测结果。
     *
     * @param rawOutput 展平后的原始输出
     * @param transform 预处理产生的 letterbox 几何，用于把输入坐标映射回原始帧
     * @param confThreshold 置信度阈值 `[0,1]`
     * @param watchedClasses 只保留这些英文类名；为空表示全部通过
     */
    fun parse(
        rawOutput: FloatArray,
        transform: LetterboxTransform,
        confThreshold: Float,
        watchedClasses: Set<String>
    ): List<Detection> = when (detect(rawOutput.size)) {
        OutputLayout.Yolo26EndToEnd -> parseYolo26(rawOutput, transform, confThreshold, watchedClasses)
        OutputLayout.YoloV5Anchors -> parseYoloV5(rawOutput, transform, confThreshold, watchedClasses)
    }

    // ── YOLO26 [1,300,6]：模型内置 NMS ────────────────────────────────────

    private fun parseYolo26(
        rawOutput: FloatArray,
        transform: LetterboxTransform,
        confThreshold: Float,
        watchedClasses: Set<String>
    ): List<Detection> {
        val candidates = ArrayList<Detection>()
        for (i in 0 until YOLO26_DETECTIONS) {
            val idx = i * YOLO26_VALUES_PER_BOX
            val confidence = rawOutput[idx + YOLO26_CONFIDENCE_INDEX]
            if (confidence < confThreshold) continue

            val classId = rawOutput[idx + YOLO26_CLASS_INDEX].toInt()
            val label = CocoClassMap.englishName(classId) ?: continue
            if (!isWatched(label, watchedClasses)) continue

            val box = transform.mapAndClipToSource(
                rawOutput[idx + 0],
                rawOutput[idx + 1],
                rawOutput[idx + 2] - rawOutput[idx + 0],
                rawOutput[idx + 3] - rawOutput[idx + 1]
            ) ?: continue

            candidates.add(Detection(classId, label, confidence, box))
        }
        return topByConfidence(candidates, MAX_DETECTIONS)
    }

    // ── YOLOv5u [1,84,N]：通道优先原始锚点 ───────────────────────────────

    private fun parseYoloV5(
        rawOutput: FloatArray,
        transform: LetterboxTransform,
        confThreshold: Float,
        watchedClasses: Set<String>
    ): List<Detection> {
        val anchors = rawOutput.size / YOLOV5_CHANNELS
        val classCount = minOf(80, CocoClassMap.EnglishNames.size)
        val candidates = ArrayList<Detection>()

        for (a in 0 until anchors) {
            // 置信度取 80 个类分数中的最大值（YOLOv5u 没有独立 objectness）。
            var bestClass = -1
            var bestScore = confThreshold
            for (c in 0 until classCount) {
                val score = rawOutput[(YOLOV5_BOX_CHANNELS + c) * anchors + a]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = c
                }
            }
            if (bestClass < 0) continue

            val label = CocoClassMap.EnglishNames[bestClass]
            if (!isWatched(label, watchedClasses)) continue

            val cx = rawOutput[a]
            val cy = rawOutput[anchors + a]
            val bw = rawOutput[2 * anchors + a]
            val bh = rawOutput[3 * anchors + a]
            if (bw <= 0f || bh <= 0f) continue

            val box = transform.mapAndClipToSource(cx - bw / 2f, cy - bh / 2f, bw, bh) ?: continue
            candidates.add(Detection(bestClass, label, bestScore, box))
        }

        // 顺序很关键：先按置信度截断候选，再按类 NMS，最后取前 5；
        // 若先取前 5 再 NMS，同一目标的重复框会吃光名额。
        val pooled = topByConfidence(candidates, MAX_CANDIDATES_FOR_NMS)
        return topByConfidence(nonMaximumSuppression(pooled), MAX_DETECTIONS)
    }

    // ── 公共后处理 ───────────────────────────────────────────────────────

    private fun isWatched(label: String, watchedClasses: Set<String>): Boolean =
        watchedClasses.isEmpty() || watchedClasses.contains(label)

    private fun topByConfidence(detections: List<Detection>, limit: Int): List<Detection> {
        val sorted = detections.sortedByDescending { it.confidence }
        return if (sorted.size > limit) sorted.subList(0, limit).toList() else sorted
    }

    /** 逐类 NMS：保留置信度更高的框，抑制同类重叠框。 */
    private fun nonMaximumSuppression(detections: List<Detection>): List<Detection> {
        val kept = ArrayList<Detection>(detections.size)
        val suppressed = BooleanArray(detections.size)

        for (i in detections.indices) {
            if (suppressed[i]) continue
            kept.add(detections[i])
            for (j in i + 1 until detections.size) {
                if (suppressed[j] || detections[j].classId != detections[i].classId) continue
                if (IoU.compute(detections[i].box, detections[j].box) > NMS_IOU_THRESHOLD) suppressed[j] = true
            }
        }
        return kept
    }
}
