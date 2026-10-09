package com.xgwnje.visionguard.inference.detect

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * letterbox 预处理几何：等比缩放到模型输入、居中黑色补边，并把模型输入坐标映射回原始帧。
 *
 * 与 Windows 端 `Inference/ImagePreprocessor.cs` 的 `LetterboxTransform` 完全同构：
 * 缩放比 `min(input/source)`、`resized = round(source * scale)` 且限制在 `[1, input]`，
 * 补边 `(input - resized) / 2` 向下取整。
 */
data class LetterboxTransform(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val inputSize: Int,
    val resizedWidth: Int,
    val resizedHeight: Int,
    val padLeft: Int,
    val padTop: Int
) {
    val scaleX: Float = resizedWidth / sourceWidth.toFloat()
    val scaleY: Float = resizedHeight / sourceHeight.toFloat()

    /**
     * 把模型输入坐标中的框还原并裁剪到原始帧；完全落进黑边或尺寸非正时返回 null。
     */
    fun mapAndClipToSource(left: Float, top: Float, width: Float, height: Float): DetectionBox? {
        if (width <= 0f || height <= 0f) return null
        val mappedLeft = clamp((left - padLeft) / scaleX, 0f, sourceWidth.toFloat())
        val mappedTop = clamp((top - padTop) / scaleY, 0f, sourceHeight.toFloat())
        val mappedRight = clamp((left + width - padLeft) / scaleX, 0f, sourceWidth.toFloat())
        val mappedBottom = clamp((top + height - padTop) / scaleY, 0f, sourceHeight.toFloat())
        if (mappedRight <= mappedLeft || mappedBottom <= mappedTop) return null
        return DetectionBox(mappedLeft, mappedTop, mappedRight - mappedLeft, mappedBottom - mappedTop)
    }

    private fun clamp(value: Float, low: Float, high: Float): Float = maxOf(low, minOf(high, value))

    companion object {
        /** 按来源尺寸计算居中等比留白几何；参数非法时抛异常。 */
        fun create(sourceWidth: Int, sourceHeight: Int, inputSize: Int): LetterboxTransform {
            require(sourceWidth > 0 && sourceHeight > 0) { "原始画面宽高必须大于 0。" }
            require(inputSize > 0) { "模型输入尺寸必须大于 0。" }

            val scale = min(inputSize.toDouble() / sourceWidth, inputSize.toDouble() / sourceHeight)
            val resizedWidth = min(inputSize, maxOf(1, (sourceWidth * scale).roundToInt()))
            val resizedHeight = min(inputSize, maxOf(1, (sourceHeight * scale).roundToInt()))
            return LetterboxTransform(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                inputSize = inputSize,
                resizedWidth = resizedWidth,
                resizedHeight = resizedHeight,
                padLeft = (inputSize - resizedWidth) / 2,
                padTop = (inputSize - resizedHeight) / 2
            )
        }
    }
}
