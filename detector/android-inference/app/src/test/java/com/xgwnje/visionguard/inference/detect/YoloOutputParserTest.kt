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

    /**
     * 锚点档：前 4 通道是框的两条对角坐标 `x1, y1, x2, y2`（与端到端档同义，不是中心宽高）。
     * 后 80 通道是已过 Sigmoid 的类别分数。
     */
    private fun anchorRow(
        raw: FloatArray,
        anchors: Int,
        anchor: Int,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        classId: Int,
        score: Float
    ) {
        raw[anchor] = x1
        raw[anchors + anchor] = y1
        raw[2 * anchors + anchor] = x2
        raw[3 * anchors + anchor] = y2
        raw[(4 + classId) * anchors + anchor] = score
    }

    @Test
    fun `按展平长度判定形态`() {
        assertEquals(YoloOutputParser.OutputLayout.Yolo26EndToEnd, YoloOutputParser.detect(1800))
        assertEquals(YoloOutputParser.OutputLayout.AnchorPreNms, YoloOutputParser.detect(84 * 2100))
        assertEquals(YoloOutputParser.OutputLayout.AnchorPreNms, YoloOutputParser.detect(84 * 8400))
        assertEquals("[1,300,6]", YoloOutputParser.describe(1800))
        assertEquals("[1,84,8400]", YoloOutputParser.describe(84 * 8400))
        assertEquals("锚点档（解析侧 NMS）", YoloOutputParser.describeBranch(84 * 8400))
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
    fun `锚点档逐类 NMS 抑制同类重叠框但保留其它类`() {
        val anchors = 2100
        val raw = FloatArray(84 * anchors)
        anchorRow(raw, anchors, 0, x1 = 40f, y1 = 40f, x2 = 60f, y2 = 60f, classId = 0, score = 0.80f)
        anchorRow(raw, anchors, 1, x1 = 42f, y1 = 42f, x2 = 62f, y2 = 62f, classId = 0, score = 0.79f)
        anchorRow(raw, anchors, 2, x1 = 41f, y1 = 41f, x2 = 61f, y2 = 61f, classId = 2, score = 0.70f)

        val result = YoloOutputParser.parse(raw, LetterboxTransform.create(640, 640, 320), 0.45f, emptySet())
        assertEquals(2, result.size)
        assertEquals("person", result[0].label)
        assertEquals(0.80f, result[0].confidence, 1e-6f)
        assertEquals("car", result[1].label)
    }

    @Test
    fun `锚点档忽略低于阈值的锚点与非正框`() {
        val anchors = 8400
        val raw = FloatArray(84 * anchors)
        anchorRow(raw, anchors, 0, x1 = 40f, y1 = 40f, x2 = 60f, y2 = 60f, classId = 0, score = 0.30f)
        // 两条对角坐标相同 → 宽高为 0，必须丢弃而不是产生零尺寸框
        anchorRow(raw, anchors, 1, x1 = 100f, y1 = 80f, x2 = 100f, y2 = 120f, classId = 0, score = 0.90f)
        anchorRow(raw, anchors, 2, x1 = 140f, y1 = 140f, x2 = 160f, y2 = 160f, classId = 0, score = 0.90f)

        val result = YoloOutputParser.parse(raw, LetterboxTransform.create(640, 640, 320), 0.45f, emptySet())
        assertEquals(1, result.size)
        // 边界 140~160 → 左上角 140，输入 320 对应 640 画面，缩放比 0.5
        assertEquals(280f, result[0].box.left, 1e-3f)
        assertEquals(280f, result[0].box.top, 1e-3f)
    }

    @Test
    fun `锚点档对角顺序不固定也能正确换算宽高`() {
        val anchors = 2100
        val raw = FloatArray(84 * anchors)
        // 故意把 x2/y2 给得比 x1/y1 小：参考实现用 min/max 归一，不能假设顺序
        anchorRow(raw, anchors, 5, x1 = 180f, y1 = 200f, x2 = 140f, y2 = 120f, classId = 0, score = 0.9f)
        val result = YoloOutputParser.parse(raw, LetterboxTransform.create(640, 640, 320), 0.45f, emptySet())
        assertEquals(1, result.size)
        assertEquals(280f, result[0].box.left, 1e-3f)
        assertEquals(240f, result[0].box.top, 1e-3f)
        assertEquals(80f, result[0].box.width, 1e-3f)
        assertEquals(160f, result[0].box.height, 1e-3f)
    }

    @Test
    fun `锚点档只保留被监视的类别`() {
        val anchors = 2100
        val raw = FloatArray(84 * anchors)
        anchorRow(raw, anchors, 0, x1 = 40f, y1 = 40f, x2 = 60f, y2 = 60f, classId = 0, score = 0.80f)
        anchorRow(raw, anchors, 1, x1 = 80f, y1 = 80f, x2 = 100f, y2 = 100f, classId = 2, score = 0.90f)

        val onlyPerson = YoloOutputParser.parse(raw, LetterboxTransform.create(640, 640, 320), 0.45f, setOf("person"))
        assertEquals(1, onlyPerson.size)
        assertEquals("person", onlyPerson[0].label)
    }
}
