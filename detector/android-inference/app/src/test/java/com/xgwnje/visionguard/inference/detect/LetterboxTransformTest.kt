package com.xgwnje.visionguard.inference.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** letterbox 几何与坐标映射契约：必须与 Windows `Inference/ImagePreprocessor.cs` 一致。 */
class LetterboxTransformTest {

    @Test
    fun `宽画面按高度缩放在上下补边`() {
        val transform = LetterboxTransform.create(sourceWidth = 640, sourceHeight = 480, inputSize = 320)
        assertEquals(320, transform.resizedWidth)
        assertEquals(240, transform.resizedHeight)
        assertEquals(0, transform.padLeft)
        assertEquals(40, transform.padTop)
        assertEquals(0.5f, transform.scaleX, 1e-6f)
        assertEquals(0.5f, transform.scaleY, 1e-6f)
    }

    @Test
    fun `正方形画面不留补边`() {
        val transform = LetterboxTransform.create(640, 640, 320)
        assertEquals(320, transform.resizedWidth)
        assertEquals(320, transform.resizedHeight)
        assertEquals(0, transform.padLeft)
        assertEquals(0, transform.padTop)
    }

    @Test
    fun `映射把模型坐标还原到原始帧`() {
        val transform = LetterboxTransform.create(640, 480, 320)
        val box = transform.mapAndClipToSource(left = 0f, top = 40f, width = 160f, height = 240f)
        assertNotNull(box)
        assertEquals(0f, box!!.left, 1e-4f)
        assertEquals(0f, box.top, 1e-4f)
        assertEquals(320f, box.width, 1e-4f)
        assertEquals(480f, box.height, 1e-4f)
    }

    @Test
    fun `完全落在黑边中的框被丢弃`() {
        val transform = LetterboxTransform.create(640, 480, 320)
        assertNull(transform.mapAndClipToSource(left = 10f, top = 0f, width = 100f, height = 40f))
    }

    @Test
    fun `跨边界的框裁剪到画面内`() {
        val transform = LetterboxTransform.create(640, 480, 320)
        val box = transform.mapAndClipToSource(left = 0f, top = 20f, width = 320f, height = 300f)
        assertNotNull(box)
        assertEquals(0f, box!!.top, 1e-4f)
        assertEquals(480f, box.bottom, 1e-4f)
    }

    @Test
    fun `尺寸非正的框直接丢弃`() {
        val transform = LetterboxTransform.create(640, 480, 320)
        assertNull(transform.mapAndClipToSource(0f, 40f, 0f, 10f))
        assertNull(transform.mapAndClipToSource(0f, 40f, 10f, -1f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `来源尺寸非法时抛异常`() {
        LetterboxTransform.create(0, 480, 320)
    }

    @Test
    fun `缩放比例取较小边并保证至少一像素`() {
        val transform = LetterboxTransform.create(sourceWidth = 2000, sourceHeight = 100, inputSize = 320)
        assertTrue(transform.resizedWidth in 1..320)
        assertTrue(transform.resizedHeight >= 1)
        assertEquals(320, transform.resizedWidth)
    }
}
