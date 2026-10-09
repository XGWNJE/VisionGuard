package com.xgwnje.visionguard.inference.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()

private fun imageOf(width: Int, height: Int, vararg values: Int) = ArgbImage(width, height, values.toList().toIntArray())

/** 像素链路契约：旋转、遮罩与 letterbox 张量。 */
class ArgbImageTest {

    @Test
    fun `顺时针旋转九十度把左上角移到右上角`() {
        val image = imageOf(2, 2, 1, 2, 3, 4)
        val rotated = image.rotate(90)
        assertEquals(2, rotated.width)
        assertEquals(2, rotated.height)
        assertTrue(rotated.pixels.contentEquals(intArrayOf(3, 1, 4, 2)))
    }

    @Test
    fun `逆时针旋转九十度方向相反`() {
        val image = imageOf(2, 2, 1, 2, 3, 4)
        assertTrue(image.rotate(270).pixels.contentEquals(intArrayOf(2, 4, 1, 3)))
    }

    @Test
    fun `旋转一百八十度按中心对称`() {
        val image = imageOf(2, 2, 1, 2, 3, 4)
        assertTrue(image.rotate(180).pixels.contentEquals(intArrayOf(4, 3, 2, 1)))
    }

    @Test
    fun `零度与三百六十度不改变画面`() {
        val image = imageOf(2, 2, 1, 2, 3, 4)
        assertTrue(image.rotate(0).pixels.contentEquals(image.pixels))
        assertTrue(image.rotate(360).pixels.contentEquals(image.pixels))
    }

    @Test
    fun `遮罩按相对坐标涂黑并保留其余像素`() {
        val image = ArgbImage(100, 100, IntArray(10_000) { WHITE })
        image.applyMasks(listOf(MaskRegion(0.1f, 0.2f, 0.3f, 0.5f)))

        assertEquals(BLACK, image.pixels[25 * 100 + 15])
        assertEquals(BLACK, image.pixels[20 * 100 + 10])
        assertEquals(BLACK, image.pixels[69 * 100 + 39])
        assertEquals(WHITE, image.pixels[19 * 100 + 15])
        assertEquals(WHITE, image.pixels[25 * 100 + 40])
    }

    @Test
    fun `遮罩像素换算使用银行家舍入以对齐 C 端`() {
        // 10 * 0.05 = 0.5 → 取偶数 0；10 * 0.15 = 1.5 → 取偶数 2
        val image = ArgbImage(10, 10, IntArray(100) { WHITE })
        image.applyMasks(listOf(MaskRegion(0.05f, 0f, 0.1f, 1f), MaskRegion(0.15f, 0f, 0.1f, 1f)))
        assertEquals(BLACK, image.pixels[0 * 10 + 0])
        assertEquals(WHITE, image.pixels[0 * 10 + 1])
        assertEquals(BLACK, image.pixels[0 * 10 + 2])
    }

    @Test
    fun `遮罩超出画面时被裁剪而不是越界写入`() {
        val image = ArgbImage(10, 10, IntArray(100) { WHITE })
        image.applyMasks(listOf(MaskRegion(0.5f, 0.5f, 1f, 1f)))
        assertEquals(BLACK, image.pixels[5 * 10 + 5])
        assertEquals(BLACK, image.pixels[9 * 10 + 9])
        assertEquals(WHITE, image.pixels[4 * 10 + 9])
    }

    @Test
    fun `letterbox 张量是 CHW 且补边为零`() {
        // 1×2 源图：上红下蓝，缩放到 4×4 会左右各补一列黑边
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        val image = imageOf(1, 2, red, blue)
        val preprocessed = image.toLetterboxTensor(4)

        val transform = preprocessed.transform
        assertEquals(2, transform.resizedWidth)
        assertEquals(4, transform.resizedHeight)
        assertEquals(1, transform.padLeft)
        assertEquals(0, transform.padTop)
        assertEquals(3 * 4 * 4, preprocessed.tensor.size)

        val planeSize = 16
        // 左补边整列为 0
        for (row in 0 until 4) {
            assertEquals(0f, preprocessed.tensor[row * 4 + 0], 1e-6f)
            assertEquals(0f, preprocessed.tensor[planeSize + row * 4 + 0], 1e-6f)
            assertEquals(0f, preprocessed.tensor[planeSize * 2 + row * 4 + 0], 1e-6f)
        }

        // 第一行是纯红：R=1，G/B=0
        assertEquals(1f, preprocessed.tensor[0 * 4 + 1], 1e-6f)
        assertEquals(0f, preprocessed.tensor[planeSize + 0 * 4 + 1], 1e-6f)
        assertEquals(0f, preprocessed.tensor[planeSize * 2 + 0 * 4 + 1], 1e-6f)

        // 最后一行是纯蓝：B=1，R/G=0
        assertEquals(0f, preprocessed.tensor[3 * 4 + 1], 1e-6f)
        assertEquals(1f, preprocessed.tensor[planeSize * 2 + 3 * 4 + 1], 1e-6f)
    }

    @Test
    fun `张量尺寸与像素数量不匹配时构造失败`() {
        val failure = runCatching { ArgbImage(2, 2, IntArray(3)) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
