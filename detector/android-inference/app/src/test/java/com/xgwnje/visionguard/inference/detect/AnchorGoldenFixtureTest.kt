package com.xgwnje.visionguard.inference.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锚点档的黄金样本回归：喂真实模型输出的锚点子集，断言解出的框与参考实现一致。
 *
 * 为什么不能只用合成数组：合成数组测不出「把 `x1, y1, x2, y2` 当成中心宽高」这类错误——
 * 两种读法都能算出正数宽高，但框会整体错位。这里的数据来自
 * `yolo26m_640_anchor_float32.tflite` 跑实例图的真实输出（`.local/model-quant/make-anchor-golden.py`），
 * 期望值由同一份张量上的参考解码给出，端侧解析只要与它对齐就算过。
 */
class AnchorGoldenFixtureTest {

    @Test
    fun `真实锚点样本解出的框与参考实现一致`() {
        val raw = syntheticTensor()
        val transform = LetterboxTransform(
            sourceWidth = AnchorGoldenFixture.SOURCE_WIDTH,
            sourceHeight = AnchorGoldenFixture.SOURCE_HEIGHT,
            inputSize = AnchorGoldenFixture.MODEL_SIZE,
            resizedWidth = AnchorGoldenFixture.MODEL_SIZE - AnchorGoldenFixture.PAD_LEFT,
            resizedHeight = AnchorGoldenFixture.MODEL_SIZE - 2 * AnchorGoldenFixture.PAD_TOP,
            padLeft = AnchorGoldenFixture.PAD_LEFT,
            padTop = AnchorGoldenFixture.PAD_TOP
        )

        val detections = YoloOutputParser.parse(raw, transform, confThreshold = 0.45f, watchedClasses = emptySet())

        assertEquals(AnchorGoldenFixture.EXPECTED.size, detections.size)
        AnchorGoldenFixture.EXPECTED.forEachIndexed { index, expected ->
            val actual = detections[index]
            assertEquals("第 $index 个框的类别", CocoClassMap.englishName(expected.classId), actual.label)
            assertEquals("第 $index 个框的分数", expected.confidence, actual.confidence, 1e-4f)
            assertEquals("第 $index 个框的左边界", expected.left, actual.box.left, 0.01f)
            assertEquals("第 $index 个框的上边界", expected.top, actual.box.top, 0.01f)
            assertEquals("第 $index 个框的宽度", expected.width, actual.box.width, 0.01f)
            assertEquals("第 $index 个框的高度", expected.height, actual.box.height, 0.01f)
        }
        // 参考实现里最高分的框是 person（类别 0）：类别下标错位时这里会先失败
        assertTrue(detections.isNotEmpty())
        assertEquals(CocoClassMap.englishName(AnchorGoldenFixture.EXPECTED_TOP_CLASS), detections[0].label)
    }

    @Test
    fun `只保留被监视类别时仍然命中同一个框`() {
        val raw = syntheticTensor()
        val transform = LetterboxTransform(
            sourceWidth = AnchorGoldenFixture.SOURCE_WIDTH,
            sourceHeight = AnchorGoldenFixture.SOURCE_HEIGHT,
            inputSize = AnchorGoldenFixture.MODEL_SIZE,
            resizedWidth = AnchorGoldenFixture.MODEL_SIZE - AnchorGoldenFixture.PAD_LEFT,
            resizedHeight = AnchorGoldenFixture.MODEL_SIZE - 2 * AnchorGoldenFixture.PAD_TOP,
            padLeft = AnchorGoldenFixture.PAD_LEFT,
            padTop = AnchorGoldenFixture.PAD_TOP
        )

        val onlyPerson = YoloOutputParser.parse(raw, transform, confThreshold = 0.45f, watchedClasses = setOf("person"))
        assertEquals(AnchorGoldenFixture.EXPECTED.size, onlyPerson.size)
        assertTrue(onlyPerson.all { it.label == "person" })
    }

    /**
     * 按真实输出的通道优先布局搭一张稀疏张量：只填样本锚点，其余为 0。
     * 0 分锚点对结果没有影响（阈值 0.45），因此解出的框必须与参考实现相同。
     */
    private fun syntheticTensor(): FloatArray {
        val raw = FloatArray(AnchorGoldenFixture.ANCHORS * 84)
        for (sample in AnchorGoldenFixture.SAMPLE_ANCHORS) {
            for (channel in 0 until 84) {
                raw[channel * AnchorGoldenFixture.ANCHORS + sample.anchor] = sample.row[channel]
            }
        }
        assertEquals(202.233688f, raw[0 * AnchorGoldenFixture.ANCHORS + 8253], 1e-3f)
        assertEquals(90.582367f, raw[1 * AnchorGoldenFixture.ANCHORS + 8253], 1e-3f)
        assertEquals(0.966816f, raw[4 * AnchorGoldenFixture.ANCHORS + 8253], 1e-6f)
        return raw
    }
}
