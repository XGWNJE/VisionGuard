package com.xgwnje.visionguard.inference.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 推理加速测量的档位契约、统计与报告口径。 */
class AccelerationMeasureTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `正式运行固定走 CPU 四线程`() {
        assertEquals(InferenceProvider.CPU4, InferenceProvider.production)
        assertEquals(4, InferenceProvider.production.cpuThreads)
        assertEquals("cpu_4threads", InferenceProvider.production.id)
        assertTrue(!InferenceProvider.production.usesNnapi)
    }

    @Test
    fun `测量档位先 CPU 后加速档且覆盖七个档位`() {
        val order = InferenceProvider.benchmarkOrder
        assertEquals(7, order.size)
        assertEquals(order.size, order.toSet().size)
        assertEquals(InferenceProvider.CPU1, order.first())
        assertTrue(order.contains(InferenceProvider.production))
        // 读数是「加速档 / 同精度 CPU」，CPU 基线必须先测出来，排在加速档之前。
        val lastCpu = order.indexOfLast { !it.usesNnapi }
        val firstAccelerated = order.indexOfFirst { it.usesNnapi }
        assertTrue(firstAccelerated > lastCpu)
    }

    @Test
    fun `每个档位都有独立 id 与显示名`() {
        assertEquals(InferenceProvider.entries.size, InferenceProvider.entries.map { it.id }.toSet().size)
        assertEquals(InferenceProvider.entries.size, InferenceProvider.entries.map { it.title }.toSet().size)
        InferenceProvider.entries.forEach { assertEquals(it, InferenceProvider.fromId(it.id)) }
        assertEquals(null, InferenceProvider.fromId("cuda"))
    }

    @Test
    fun `中位数基数与偶数各取一次`() {
        assertEquals(0.0, AccelerationMeasure.median(emptyList()), 0.0)
        assertEquals(7.0, AccelerationMeasure.median(listOf(7.0)), 0.0)
        assertEquals(6.0, AccelerationMeasure.median(listOf(4.0, 6.0, 20.0)), 0.0)
        assertEquals(5.0, AccelerationMeasure.median(listOf(4.0, 6.0)), 0.0)
        // 偶数个取中间两个的均值；调用方传入的已是排序结果。
        assertEquals(5.0, AccelerationMeasure.median(listOf(1.0, 4.0, 6.0, 10.0)), 0.0)
    }

    @Test
    fun `合成输入形状与取值范围固定`() {
        val size = 32
        val tensor = AccelerationMeasure.syntheticInput(size)
        assertEquals(3 * size * size, tensor.size)
        assertTrue(tensor.all { it >= 0.0f && it <= 1.0f })

        // 固定输入：同一尺寸两次生成必须逐元素一致，否则各档位读数不可比。
        assertTrue(tensor.contentEquals(AccelerationMeasure.syntheticInput(size)))
        // 通道间不能退化成同一张图，否则测不出真实的计算量差异。
        val plane = size * size
        assertTrue(tensor.copyOfRange(0, plane).toList() != tensor.copyOfRange(plane, 2 * plane).toList())
    }

    @Test
    fun `待测模型只取目录下的 onnx 且按名称排序`() {
        val dir = temporary.newFolder("bench-models")
        java.io.File(dir, "b.onnx").writeText("x")
        java.io.File(dir, "a.onnx").writeText("x")
        java.io.File(dir, "notes.txt").writeText("x")
        java.io.File(dir, "sub.onnx").mkdirs()

        assertEquals(listOf("a.onnx", "b.onnx"), AccelerationMeasure.modelsIn(dir).map { it.name })
    }

    @Test
    fun `报告按门槛给出达标与未达标判定`() {
        val report = AccelerationMeasure.Report(
            device = "Test Device / API 36",
            results = listOf(
                result(InferenceProvider.CPU4, medianMs = 100.0),
                result(InferenceProvider.NNAPI_DEFAULT, medianMs = 70.0),
                result(InferenceProvider.NNAPI_FP16, medianMs = 70.5),
                result(InferenceProvider.NNAPI_NCHW, medianMs = 250.0)
            ),
            failures = listOf("yolo26n_320.onnx · NNAPI · 仅加速器：no NNAPI device")
        )
        val text = report.lines().joinToString("\n")

        // 恰好 0.70 达标，0.705 未达标——门槛是 ≤，不是 <。
        assertTrue(text.contains("NNAPI · 默认 / CPU · 4 线程 = 0.70×（门槛 ≤ 0.70×，达标）"))
        assertTrue(text.contains("NNAPI · FP16 / CPU · 4 线程 = 0.71×（门槛 ≤ 0.70×，未达标）"))
        assertTrue(text.contains("NNAPI · NCHW / CPU · 4 线程 = 2.50×（门槛 ≤ 0.70×，未达标）"))
        assertTrue(text.contains("设备：Test Device / API 36"))
        assertTrue(text.contains("失败：yolo26n_320.onnx · NNAPI · 仅加速器：no NNAPI device"))
    }

    @Test
    fun `没有 CPU 基线时不给结论`() {
        val report = AccelerationMeasure.Report(
            device = "d",
            results = listOf(result(InferenceProvider.NNAPI_DEFAULT, medianMs = 5.0)),
            failures = emptyList()
        )
        assertTrue(report.lines().none { it.contains("×") })
    }

    private fun result(provider: InferenceProvider, medianMs: Double) = AccelerationMeasure.Result(
        model = "yolo26n_320.onnx",
        provider = provider,
        runs = 3,
        medianMs = medianMs,
        minMs = medianMs,
        maxMs = medianMs
    )
}
