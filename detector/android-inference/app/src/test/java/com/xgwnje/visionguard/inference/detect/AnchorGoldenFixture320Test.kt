package com.xgwnje.visionguard.inference.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 320 档锚点样本回归。
 *
 * 与 640 的那份同源（`yolo26m_320_anchor_float32.tflite` 跑同一张实例图），但锚点数是 2100 而不是
 * 8400：解析器只要悄悄依赖了某个锚点数常量，这里就会先失败。期望值来自同一份张量上的参考解码。
 */
class AnchorGoldenFixture320Test {

    private fun transform() = LetterboxTransform(
        sourceWidth = AnchorGoldenFixture320.SOURCE_WIDTH,
        sourceHeight = AnchorGoldenFixture320.SOURCE_HEIGHT,
        inputSize = AnchorGoldenFixture320.MODEL_SIZE,
        resizedWidth = AnchorGoldenFixture320.MODEL_SIZE - AnchorGoldenFixture320.PAD_LEFT,
        resizedHeight = AnchorGoldenFixture320.MODEL_SIZE - 2 * AnchorGoldenFixture320.PAD_TOP,
        padLeft = AnchorGoldenFixture320.PAD_LEFT,
        padTop = AnchorGoldenFixture320.PAD_TOP
    )

    @Test
    fun `320 锚点样本解出的框与参考实现一致`() {
        val detections = YoloOutputParser.parse(
            syntheticTensor(),
            transform(),
            confThreshold = 0.45f,
            watchedClasses = emptySet()
        )

        assertEquals(AnchorGoldenFixture320.EXPECTED.size, detections.size)
        AnchorGoldenFixture320.EXPECTED.forEachIndexed { index, expected ->
            val actual = detections[index]
            assertEquals("第 $index 个框的类别", CocoClassMap.englishName(expected.classId), actual.label)
            assertEquals("第 $index 个框的分数", expected.confidence, actual.confidence, 1e-4f)
            assertEquals("第 $index 个框的左边界", expected.left, actual.box.left, 0.01f)
            assertEquals("第 $index 个框的上边界", expected.top, actual.box.top, 0.01f)
            assertEquals("第 $index 个框的宽度", expected.width, actual.box.width, 0.01f)
            assertEquals("第 $index 个框的高度", expected.height, actual.box.height, 0.01f)
        }
        assertEquals(
            CocoClassMap.englishName(AnchorGoldenFixture320.EXPECTED_TOP_CLASS),
            detections.first().label
        )
    }

    @Test
    fun `只保留被监视类别时命中参考里的 person 框`() {
        val onlyPerson = YoloOutputParser.parse(
            syntheticTensor(),
            transform(),
            confThreshold = 0.45f,
            watchedClasses = setOf("person")
        )

        val expectedPerson = AnchorGoldenFixture320.EXPECTED.count { it.classId == 0 }
        assertEquals(expectedPerson, onlyPerson.size)
        assertTrue(onlyPerson.all { it.label == "person" })
    }

    /** 通道优先布局的稀疏张量：只填样本锚点，其余为 0（0 分不影响阈值 0.45 的结果）。 */
    private fun syntheticTensor(): FloatArray {
        val raw = FloatArray(AnchorGoldenFixture320.ANCHORS * 84)
        for (sample in AnchorGoldenFixture320.SAMPLE_ANCHORS) {
            for (channel in 0 until 84) {
                raw[channel * AnchorGoldenFixture320.ANCHORS + sample.anchor] = sample.row[channel]
            }
        }
        assertEquals(2056, AnchorGoldenFixture320.SAMPLE_ANCHORS.first().anchor)
        return raw
    }
}
