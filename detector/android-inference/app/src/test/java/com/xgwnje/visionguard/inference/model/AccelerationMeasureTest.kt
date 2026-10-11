package com.xgwnje.visionguard.inference.model

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
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
    fun `正式运行固定走 ONNX CPU 四线程`() {
        assertEquals(InferenceProvider.ONNX_CPU4, InferenceProvider.production)
        assertEquals(4, InferenceProvider.production.cpuThreads)
        assertEquals("onnx_cpu_4threads", InferenceProvider.production.id)
        assertEquals(InferenceRuntimeKind.ONNX, InferenceProvider.production.runtime)
        assertEquals("ONNX · CPU 4 线程", InferenceProvider.production.title)
        assertTrue(!InferenceProvider.production.usesAccelerator)
        assertTrue(!InferenceProvider.production.usesLitertAcceleratorApi)
        assertTrue(InferenceProvider.production.litertAccelerator == null)
    }

    @Test
    fun `正式路径只依赖 ONNX Runtime 的 CPU 提供器`() {
        // 硬件加速档位全部不达标（真机实测），因此正式档位必须落在 CPU 上，
        // 且不能是 NNAPI / LiteRT 任一档——换档等于换发布行为，这里守住。
        val production = InferenceProvider.production
        assertEquals(InferenceRuntimeKind.ONNX, production.runtime)
        assertTrue(!production.usesNnapi)
        assertTrue(!production.usesGpuDelegate)
        assertTrue(!production.usesNpu)
        assertTrue(!production.usesXnnpack)
        assertTrue(!production.disablesXnnpack)
        assertEquals(4, production.cpuThreads)
    }

    @Test
    fun `两个运行时各有自己的 CPU 基线`() {
        assertEquals(InferenceProvider.ONNX_CPU4, InferenceProvider.cpuBaseline(InferenceRuntimeKind.ONNX))
        assertEquals(InferenceProvider.LITERT_CPU4, InferenceProvider.cpuBaseline(InferenceRuntimeKind.LITERT))
        // 门槛只在同运行时内比较：基线不能跨运行时借用。
        InferenceRuntimeKind.entries.forEach { runtime ->
            assertEquals(runtime, InferenceProvider.cpuBaseline(runtime).runtime)
            assertTrue(!InferenceProvider.cpuBaseline(runtime).usesAccelerator)
        }
    }

    @Test
    fun `测量档位先 CPU 后加速档且每个运行时都覆盖`() {
        val order = InferenceProvider.benchmarkOrder
        assertEquals(InferenceProvider.entries.size, order.size)
        assertEquals(order.size, order.toSet().size)
        assertTrue(order.contains(InferenceProvider.production))
        // 每个运行时都要有 CPU 基线与至少一个加速档，否则比值算不出来。
        InferenceRuntimeKind.entries.forEach { runtime ->
            assertTrue(order.any { it.runtime == runtime && !it.usesAccelerator })
            assertTrue(order.any { it.runtime == runtime && it.usesAccelerator })
            val lastCpu = order.indexOfLast { it.runtime == runtime && !it.usesAccelerator }
            val firstAccelerated = order.indexOfFirst { it.runtime == runtime && it.usesAccelerator }
            assertTrue("$runtime 的 CPU 基线应排在加速档之前", firstAccelerated > lastCpu)
        }
    }

    @Test
    fun `每个档位都有独立 id 与显示名`() {
        assertEquals(InferenceProvider.entries.size, InferenceProvider.entries.map { it.id }.toSet().size)
        assertEquals(InferenceProvider.entries.size, InferenceProvider.entries.map { it.title }.toSet().size)
        InferenceProvider.entries.forEach { assertEquals(it, InferenceProvider.fromId(it.id)) }
        assertEquals(null, InferenceProvider.fromId("cuda"))
    }

    @Test
    fun `报告在耗时行下带质量行且格式固定`() {
        val report = AccelerationMeasure.Report(
            device = "d",
            results = listOf(
                result(InferenceProvider.ONNX_CPU4, medianMs = 5.9).copy(
                    quality = AccelerationMeasure.qualityLine(2, 0.9014f, 0.5f, 0.25f, 0.125f, 0.4f)
                )
            ),
            failures = emptyList()
        )
        val lines = report.lines()

        // 质量必须紧跟在对应档位的耗时行之后，否则报告里对不上是哪个档位。
        val index = lines.indexOfFirst { it.contains("ONNX · CPU 4 线程：中位") }
        assertTrue(index >= 0)
        assertEquals("    质量：person 2 个 / 最高分 0.901 / 最高框 中心 0.500,0.250 宽高 0.125,0.400", lines[index + 1])
        // 小数点是点号而不是逗号：不同区域设置下报告必须一样。
        assertTrue(!lines[index + 1].contains(",").not())
    }

    @Test
    fun `没有质量读数的档位不写质量行`() {
        val report = AccelerationMeasure.Report(
            device = "d",
            results = listOf(result(InferenceProvider.ONNX_CPU4, medianMs = 5.9)),
            failures = emptyList()
        )
        assertTrue(report.lines().none { it.contains("质量：") })
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
    fun `报告按门槛给出达标与未达标判定`() {
        val report = AccelerationMeasure.Report(
            device = "Test Device / API 36",
            results = listOf(
                result(InferenceProvider.ONNX_CPU4, medianMs = 100.0),
                result(InferenceProvider.ONNX_NNAPI_DEFAULT, medianMs = 70.0),
                result(InferenceProvider.ONNX_NNAPI_FP16, medianMs = 70.5),
                result(InferenceProvider.ONNX_NNAPI_NCHW, medianMs = 250.0)
            ),
            failures = listOf("yolo26n_320 · ONNX · NNAPI 仅加速器：no NNAPI device")
        )
        val text = report.lines().joinToString("\n")

        // 恰好 0.70 达标，0.705 未达标——门槛是 ≤，不是 <。
        assertTrue(text.contains("ONNX · NNAPI 默认 / ONNX · CPU 4 线程 = 0.70×（门槛 ≤ 0.70×，达标）"))
        assertTrue(text.contains("ONNX · NNAPI FP16 / ONNX · CPU 4 线程 = 0.71×（门槛 ≤ 0.70×，未达标）"))
        assertTrue(text.contains("ONNX · NNAPI NCHW / ONNX · CPU 4 线程 = 2.50×（门槛 ≤ 0.70×，未达标）"))
        assertTrue(text.contains("设备：Test Device / API 36"))
        assertTrue(text.contains("失败：yolo26n_320 · ONNX · NNAPI 仅加速器：no NNAPI device"))
    }

    @Test
    fun `报告首行带芯片与核心数，缺芯片时不编造`() {
        // 完整指纹：厂商 型号 / API / 芯片 / 核数。
        assertEquals(
            "Xiaomi 26048RP6AC / API 37 / MT8910 / 8 核",
            AccelerationMeasure.deviceFingerprint("Xiaomi", "26048RP6AC", 37, "MT8910", 8)
        )
        // API 31 以下取不到 SOC_MODEL，或系统返回 unknown —— 都跳过这一段，不用占位符。
        assertEquals(
            "Generic Device / API 29 / 4 核",
            AccelerationMeasure.deviceFingerprint("Generic", "Device", 29, null, 4)
        )
        assertEquals(
            "Generic Device / API 33 / 8 核",
            AccelerationMeasure.deviceFingerprint("Generic", "Device", 33, "unknown", 8)
        )
        assertEquals(
            "Generic Device / API 33 / 8 核",
            AccelerationMeasure.deviceFingerprint("Generic", "Device", 33, "  ", 8)
        )
    }

    @Test
    fun `LiteRT 加速器档位映射到 CPU GPU NPU`() {
        assertEquals(Accelerator.CPU, InferenceProvider.LITERT_ACCEL_CPU.litertAccelerator)
        assertEquals(Accelerator.GPU, InferenceProvider.LITERT_GPU.litertAccelerator)
        assertEquals(Accelerator.GPU, InferenceProvider.LITERT_GPU_FP16.litertAccelerator)
        assertEquals(Accelerator.NPU, InferenceProvider.LITERT_NPU.litertAccelerator)

        // 走老 Interpreter 的 LiteRT CPU 档不指定加速器。
        assertTrue(InferenceProvider.LITERT_CPU4.litertAccelerator == null)
        assertTrue(InferenceProvider.LITERT_CPU4.usesLitertAcceleratorApi.not())

        // GPU 档位精度：默认档不覆盖精度，FP16 档显式要求 FP16。
        assertTrue(InferenceProvider.LITERT_GPU.gpuPrecision == null)
        assertEquals(
            CompiledModel.GpuOptions.Precision.FP16,
            InferenceProvider.LITERT_GPU_FP16.gpuPrecision
        )

        // 只有 GPU / NPU 算硬件加速；加速器 API 的 CPU 档只是同 API 的基线。
        assertTrue(InferenceProvider.LITERT_GPU.usesAccelerator)
        assertTrue(InferenceProvider.LITERT_NPU.usesAccelerator)
        assertTrue(InferenceProvider.LITERT_ACCEL_CPU.usesAccelerator.not())
        assertTrue(InferenceProvider.LITERT_ACCEL_CPU.usesLitertAcceleratorApi)
    }

    @Test
    fun `LiteRT 加速档只与 LiteRT 的 CPU 基线比较`() {
        val report = AccelerationMeasure.Report(
            device = "d",
            results = listOf(
                result(InferenceProvider.ONNX_CPU4, medianMs = 100.0),
                result(InferenceProvider.LITERT_CPU8, medianMs = 200.0),
                result(InferenceProvider.LITERT_GPU, medianMs = 50.0)
            ),
            failures = emptyList()
        )
        val text = report.lines().joinToString("\n")

        // 50 / 200 = 0.25 达标；绝不能拿 ONNX CPU4=100 去比（那会得到未达标的错误结论）。
        assertTrue(text.contains("LiteRT · GPU 加速器 / LiteRT · CPU 8 线程 = 0.25×（门槛 ≤ 0.70×，达标）"))
        assertTrue(!text.contains("LiteRT · GPU 加速器 / ONNX · CPU 4 线程"))
    }

    @Test
    fun `没有 CPU 基线时不给结论`() {
        val report = AccelerationMeasure.Report(
            device = "d",
            results = listOf(result(InferenceProvider.ONNX_NNAPI_DEFAULT, medianMs = 5.0)),
            failures = emptyList()
        )
        assertTrue(report.lines().none { it.contains("×") })
    }

    private fun result(provider: InferenceProvider, medianMs: Double) = AccelerationMeasure.Result(
        model = "yolo26n_320",
        provider = provider,
        runs = 3,
        medianMs = medianMs,
        minMs = medianMs,
        maxMs = medianMs
    )
}
