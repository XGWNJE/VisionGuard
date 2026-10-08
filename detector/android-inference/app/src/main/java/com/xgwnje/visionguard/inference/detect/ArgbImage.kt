package com.xgwnje.visionguard.inference.detect

import kotlin.math.min

/** 预处理产物：letterbox 张量与对应的坐标变换必须成对使用。 */
class PreprocessedFrame(val tensor: FloatArray, val transform: LetterboxTransform)

/**
 * 纯 JVM 的 ARGB 画面容器，承载旋转、遮罩与 letterbox 张量生成。
 *
 * 保持纯 Kotlin 是为了让整条推理前置链路能在单元测试里对拍，不依赖 Android 图形栈；
 * Android 侧只负责 Bitmap 与 [ArgbImage] 的互转。
 */
class ArgbImage(val width: Int, val height: Int, val pixels: IntArray) {

    init {
        require(width > 0 && height > 0) { "画面宽高必须大于 0。" }
        require(pixels.size == width * height) { "像素数量与宽高不一致。" }
    }

    fun copy(): ArgbImage = ArgbImage(width, height, pixels.copyOf())

    /**
     * 按顺时针 [degrees] 旋转画面，只接受 0/90/180/270；
     * 与 Android `ImageInfo.rotationDegrees` 的“顺时针转正”语义一致。
     */
    fun rotate(degrees: Int): ArgbImage {
        val normalized = ((degrees % 360) + 360) % 360
        require(normalized == 0 || normalized == 90 || normalized == 180 || normalized == 270) {
            "旋转角度只支持 0/90/180/270。"
        }
        if (normalized == 0) return this

        if (normalized == 180) {
            val out = IntArray(pixels.size)
            for (i in pixels.indices) out[pixels.size - 1 - i] = pixels[i]
            return ArgbImage(width, height, out)
        }

        val outWidth = height
        val outHeight = width
        val out = IntArray(pixels.size)
        for (row in 0 until height) {
            for (col in 0 until width) {
                val value = pixels[row * width + col]
                // 顺时针 90°：源 (col,row) → 目标 (height-1-row, col)；逆时针 90°：源 (col,row) → 目标 (row, width-1-col)
                val targetCol: Int
                val targetRow: Int
                if (normalized == 90) {
                    targetCol = height - 1 - row
                    targetRow = col
                } else {
                    targetCol = row
                    targetRow = width - 1 - col
                }
                out[targetRow * outWidth + targetCol] = value
            }
        }
        return ArgbImage(outWidth, outHeight, out)
    }

    /**
     * 就地涂黑全部遮罩区域，语义与 Windows `Inference/MaskApplier.cs` 一致：
     * 相对坐标 `round` 到像素（银行家舍入，对齐 C# `Math.Round`）、越界平移裁剪、宽高非正跳过。
     */
    fun applyMasks(masks: List<MaskRegion>) {
        if (masks.isEmpty()) return

        for (mask in masks) {
            var x = Math.rint((mask.x * width).toDouble()).toInt()
            var y = Math.rint((mask.y * height).toDouble()).toInt()
            var w = Math.rint((mask.width * width).toDouble()).toInt()
            var h = Math.rint((mask.height * height).toDouble()).toInt()

            if (x < 0) {
                w += x
                x = 0
            }
            if (y < 0) {
                h += y
                y = 0
            }
            if (x + w > width) w = width - x
            if (y + h > height) h = height - y
            if (w <= 0 || h <= 0) continue

            for (row in y until y + h) {
                val rowBase = row * width
                for (col in x until x + w) pixels[rowBase + col] = BLACK
            }
        }
    }

    /**
     * 等比缩放并居中补黑边，输出 CHW `float[3 * size * size]`、RGB、`/255`，与 Windows 端一致。
     *
     * 采样用半像素中心对齐的双线性，接近 GDI+ `PixelOffsetMode.Half` + `InterpolationMode.Bilinear`；
     * 精确到逐像素的插值结果不保证与 GDI+ 完全一致，但几何、通道顺序与归一化完全一致。
     */
    fun toLetterboxTensor(inputSize: Int): PreprocessedFrame {
        val transform = LetterboxTransform.create(width, height, inputSize)
        val planeSize = inputSize * inputSize
        val tensor = FloatArray(planeSize * 3)
        val scaleX = transform.scaleX
        val scaleY = transform.scaleY
        val padLeft = transform.padLeft
        val padTop = transform.padTop
        val resizedWidth = transform.resizedWidth
        val resizedHeight = transform.resizedHeight

        for (row in 0 until inputSize) {
            val inRow = row - padTop
            val rowInside = inRow >= 0 && inRow < resizedHeight
            val sourceY = if (rowInside) (inRow + 0.5f) / scaleY - 0.5f else 0f
            val y0 = clampIndex(sourceY.toInt(), height)
            val y1 = min(y0 + 1, height - 1)
            val fy = if (rowInside) (sourceY - y0).coerceIn(0f, 1f) else 0f

            for (col in 0 until inputSize) {
                val inCol = col - padLeft
                val inside = rowInside && inCol >= 0 && inCol < resizedWidth
                val index = row * inputSize + col
                if (!inside) continue

                val sourceX = (inCol + 0.5f) / scaleX - 0.5f
                val x0 = clampIndex(sourceX.toInt(), width)
                val x1 = min(x0 + 1, width - 1)
                val fx = (sourceX - x0).coerceIn(0f, 1f)

                val topLeft = pixels[y0 * width + x0]
                val topRight = pixels[y0 * width + x1]
                val bottomLeft = pixels[y1 * width + x0]
                val bottomRight = pixels[y1 * width + x1]

                val red = bilinear((topLeft shr 16) and 0xFF, (topRight shr 16) and 0xFF, (bottomLeft shr 16) and 0xFF, (bottomRight shr 16) and 0xFF, fx, fy)
                val green = bilinear((topLeft shr 8) and 0xFF, (topRight shr 8) and 0xFF, (bottomLeft shr 8) and 0xFF, (bottomRight shr 8) and 0xFF, fx, fy)
                val blue = bilinear(topLeft and 0xFF, topRight and 0xFF, bottomLeft and 0xFF, bottomRight and 0xFF, fx, fy)

                tensor[index] = red / 255f
                tensor[planeSize + index] = green / 255f
                tensor[planeSize * 2 + index] = blue / 255f
            }
        }

        return PreprocessedFrame(tensor, transform)
    }

    private fun clampIndex(value: Int, limit: Int): Int = when {
        value < 0 -> 0
        value >= limit -> limit - 1
        else -> value
    }

    private fun bilinear(a: Int, b: Int, c: Int, d: Int, fx: Float, fy: Float): Float {
        val top = a + (b - a) * fx
        val bottom = c + (d - c) * fx
        return top + (bottom - top) * fy
    }

    companion object {
        private const val BLACK = 0xFF000000.toInt()
    }
}
