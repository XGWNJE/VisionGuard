package com.xgwnje.visionguard.inference.model

/**
 * 各测量模型的边界量化参数表（由 `.local/model-quant/gen-quant-table.py` 生成，请勿手改）。
 *
 * 为什么是常量表而不是运行时解析：`CompiledModel` 的 `TensorType` 只有 elementType 与
 * layout，拿不到 scale / zero point；而统一量化模型必须按 scale 把 float 换算成整数
 * 才能写进缓冲（否则报 `TensorBuffer host memory buffer size is smaller than ...`）。
 * 参数是产物的固有属性，重造产物后用同一个脚本重新生成即可。
 *
 * 表里没有的模型一律按 float32 处理——不会静默用错 scale。
 */
object TfliteQuantizationTable {

    val entries: Map<String, TfliteQuantization.Entry> = mapOf(
        "yolo26m_640_anchor_float32.tflite" to TfliteQuantization.Entry(
            inputType = "FLOAT32",
            inputScales = listOf(),
            inputZeroPoints = listOf(),
            outputType = "FLOAT32",
            outputScales = listOf(),
            outputZeroPoints = listOf()
        ),
        "yolo26m_640_anchor_int8.tflite" to TfliteQuantization.Entry(
            inputType = "INT8",
            inputScales = listOf(0.003921568859368563f),
            inputZeroPoints = listOf(-128),
            outputType = "INT8",
            outputScales = listOf(3.0658726692199707f),
            outputZeroPoints = listOf(-98)
        ),
        "yolo26n_640_anchor_float32.tflite" to TfliteQuantization.Entry(
            inputType = "FLOAT32",
            inputScales = listOf(),
            inputZeroPoints = listOf(),
            outputType = "FLOAT32",
            outputScales = listOf(),
            outputZeroPoints = listOf()
        ),
        "yolo26n_640_anchor_int8.tflite" to TfliteQuantization.Entry(
            inputType = "INT8",
            inputScales = listOf(0.003921568859368563f),
            inputZeroPoints = listOf(-128),
            outputType = "INT8",
            outputScales = listOf(3.628429889678955f),
            outputZeroPoints = listOf(-109)
        ),
        "yolo26s_640_anchor_float32.tflite" to TfliteQuantization.Entry(
            inputType = "FLOAT32",
            inputScales = listOf(),
            inputZeroPoints = listOf(),
            outputType = "FLOAT32",
            outputScales = listOf(),
            outputZeroPoints = listOf()
        ),
        "yolo26s_640_anchor_int8.tflite" to TfliteQuantization.Entry(
            inputType = "INT8",
            inputScales = listOf(0.003921568859368563f),
            inputZeroPoints = listOf(-128),
            outputType = "INT8",
            outputScales = listOf(3.0435192584991455f),
            outputZeroPoints = listOf(-95)
        ),
    )
}
