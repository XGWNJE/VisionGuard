package com.xgwnje.visionguard.inference.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 两档模型输出解析契约：形态判定、过滤、NMS 与坐标映射。 */
class YoloOutputParserTest {

    private val yolo26Length = 300 * 6

    private fun yolo26Row(raw: FloatArray, index: Int, x1: Float, y1: Float, x2: Float, y2: Float, confidence: Float, classId: Int) {
        val base = index * 6
        raw[base] = x1
        raw[base + 1] = y1
        raw[base + 2] = x2
        raw[base + 3] = y2
        raw[base + 4] = confidence
        raw[base + 5] = classId.toFloat()
    }

    private fun yoloV5Anchor(raw: FloatArray, anchors: Int, anchor: Int, cx: Float, cy: Float, bw: Float, bh: Float, classId: Int, score: Float) {
        raw[anchor] = cx
        raw[anchors + anchor] = cy
        raw[2 * anchors + anchor] = bw
        raw[3 * anchors + anchor] = bh
        raw[(4 + classId) * anchors + anchor] = score
    }

    @Test
    fun `按展平长度判定形态`() {
        assertEquals(YoloOutputParser.OutputLayout.Yolo26EndToEnd, YoloOutputParser.detect(1800))
        assertEquals(YoloOutputParser.OutputLayout.YoloV5Anchors, YoloOutputParser.detect(84 * 2100))
        assertEquals(YoloOutputParser.OutputLayout.YoloV5Anchors, YoloOutputParser.detect(84 * 8400))
        assertEquals("[1,300,6]", YoloOutputParser.describe(1800))
        assertEquals("[1,84,8400]", YoloOutputParser.describe(84 * 8400))
    }

    @Test
    fun `无法识别的长度必须报错而不是硬读`() {
        assertThrows(IllegalArgumentException::class.java) { YoloOutputParser.detect(84 * 1000) }
        assertThrows(IllegalArgumentException::class.java) { YoloOutputParser.detect(1234) }
        assertThrows(IllegalArgumentException::class.java) { YoloOutputParser.detect(0) }
    }

    @Test
    fun `YOLO26 输出映射回原帧并受阈值与类别过滤`() {
        val raw = FloatArray(yolo26Length)
        yolo26Row(raw, 0, 0f, 0f, 100f, 100f, 0.90f, 0)
        yolo26Row(raw, 1, 0f, 0f, 100f, 100f, 0.20f, 0)
        yolo26Row(raw, 2, 0f, 0f, 100f, 100f, 0.80f, 2)

        val transform = LetterboxTransform.create(640, 640, 320)
        val onlyPerson = YoloOutputParser.parse(raw, transform, confThreshold = 0.45f, watchedClasses = setOf("person"))
        assertEquals(1, onlyPerson.size)
        assertEquals("person", onlyPerson[0].label)
        assertEquals(200f, onlyPerson[0].box.width, 1e-3f)
        assertEquals(200f, onlyPerson[0].box.height, 1e-3f)

        val all = YoloOutputParser.parse(raw, transform, confThreshold = 0.45f, watchedClasses = emptySet())
        assertEquals(2, all.size)
        assertEquals("car", all[1].label)
    }

    @Test
    fun `YOLO26 落进黑边的框被丢弃`() {
        val raw = FloatArray(yolo26Length)
        yolo26Row(raw, 0, 0f, 0f, 100f, 40f, 0.9f, 0)
        val transform = LetterboxTransform.create(640, 480, 320)
        assertTrue(YoloOutputParser.parse(raw, transform, 0.45f, emptySet()).isEmpty())
    }

    @Test
    fun `YOLO26 最多返回五个框并按置信度降序`() {
        val raw = FloatArray(yolo26Length)
        for (i in 0 until 8) {
            yolo26Row(raw, i, 0f, 0f, 10f, 10f, 0.5f + i * 0.05f, 0)
        }
        val result = YoloOutputParser.parse(raw, LetterboxTransform.create(640, 640, 320), 0.45f, emptySet())
        assertEquals(5, result.size)
        assertEquals(0.85f, result[0].confidence, 1e-6f)
        assertEquals(0.65f, result[4].confidence, 1e-6f)
    }

    @Test
    fun `YOLOv5 逐类 NMS 抑制同类重叠框但保留其它类`() {
        val anchors = 2100
        val raw = FloatArray(84 * anchors)
        yoloV5Anchor(raw, anchors, 0, cx = 50f, cy = 50f, bw = 20f, bh = 20f, classId = 0, score = 0.80f)
        yoloV5Anchor(raw, anchors, 1, cx = 52f, cy = 52f, bw = 20f, bh = 20f, classId = 0, score = 0.79f)
        yoloV5Anchor(raw, anchors, 2, cx = 51f, cy = 51f, bw = 20f, bh = 20f, classId = 2, score = 0.70f)

        val result = YoloOutputParser.parse(raw, LetterboxTransform.create(640, 640, 320), 0.45f, emptySet())
        assertEquals(2, result.size)
        assertEquals("person", result[0].label)
        assertEquals(0.80f, result[0].confidence, 1e-6f)
        assertEquals("car", result[1].label)
    }

    @Test
    fun `YOLOv5 忽略低于阈值的锚点与非正宽高`() {
        val anchors = 8400
        val raw = FloatArray(84 * anchors)
        yoloV5Anchor(raw, anchors, 0, cx = 50f, cy = 50f, bw = 20f, bh = 20f, classId = 0, score = 0.30f)
        yoloV5Anchor(raw, anchors, 1, cx = 100f, cy = 100f, bw = 0f, bh = 20f, classId = 0, score = 0.90f)
        yoloV5Anchor(raw, anchors, 2, cx = 150f, cy = 150f, bw = 20f, bh = 20f, classId = 0, score = 0.90f)

        val result = YoloOutputParser.parse(raw, LetterboxTransform.create(640, 640, 320), 0.45f, emptySet())
        assertEquals(1, result.size)
        // cx=150、bw=20 → 左上角 140，输入 320 对应 640 画面，缩放比 0.5
        assertEquals(280f, result[0].box.left, 1e-3f)
        assertEquals(280f, result[0].box.top, 1e-3f)
    }

    @Test
    fun `YOLOv5 通道优先索引与中心宽高换算正确`() {
        val anchors = 2100
        val raw = FloatArray(84 * anchors)
        yoloV5Anchor(raw, anchors, 5, cx = 160f, cy = 160f, bw = 40f, bh = 80f, classId = 0, score = 0.9f)
        val result = YoloOutputParser.parse(raw, LetterboxTransform.create(640, 640, 320), 0.45f, emptySet())
        assertEquals(1, result.size)
        assertEquals(280f, result[0].box.left, 1e-3f)
        assertEquals(240f, result[0].box.top, 1e-3f)
        assertEquals(80f, result[0].box.width, 1e-3f)
        assertEquals(160f, result[0].box.height, 1e-3f)
    }
}
