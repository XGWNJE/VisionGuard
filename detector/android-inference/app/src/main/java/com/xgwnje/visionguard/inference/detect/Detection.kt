package com.xgwnje.visionguard.inference.detect

/** 检测框使用原始帧像素坐标（左上角 + 宽高），与 Windows 端 `Models/Detection.cs` 语义一致。 */
data class DetectionBox(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/** 单个检测结果；坐标已映射回原始捕获帧。 */
data class Detection(
    val classId: Int,
    val label: String,
    val confidence: Float,
    val box: DetectionBox
)

/** 检测框与标注裁剪工具。 */
internal object IoU {
    /** 交并比；无交集或并集为 0 时返回 0。 */
    fun compute(a: DetectionBox, b: DetectionBox): Float {
        val interWidth = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val interHeight = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (interWidth <= 0f || interHeight <= 0f) return 0f
        val intersection = interWidth * interHeight
        val union = a.width * a.height + b.width * b.height - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}
