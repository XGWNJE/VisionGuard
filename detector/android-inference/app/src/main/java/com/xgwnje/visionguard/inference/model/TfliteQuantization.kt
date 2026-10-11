package com.xgwnje.visionguard.inference.model

import java.io.File

/**
 * 按模型文件名查边界量化参数（scale / zero point）。
 *
 * 为什么是查表而不是运行时解析：`CompiledModel` 的 `TensorType` 只暴露 elementType 与
 * layout，拿不到量化参数；而**统一量化模型必须按 scale 把 float 张量换算成整数**才能写进
 * 缓冲，否则报 `TensorBuffer host memory buffer size is smaller than the given data size`。
 * 自己手写 flatbuffer 解析曾反复踩字段偏移的坑，而量化参数是产物的固有属性——用离线脚本
 * `gen-quant-table.py`（走官方 schema）生成一次即可，确定性更好、也能离线核对。
 *
 * 表里没有的模型一律按 float32 处理：宁可少一次量化适配，也不要静默用错 scale。
 */
object TfliteQuantization {

    /** 一个边界张量的信息。 */
    data class TensorInfo(
        val typeName: String,
        val scales: List<Float>,
        val zeroPoints: List<Int>
    ) {
        val quantized: Boolean get() = typeName == "INT8" || typeName == "UINT8"
        val unsigned: Boolean get() = typeName == "UINT8"
    }

    /** 生成表里的条目结构（与 `gen-quant-table.py` 的输出一一对应）。 */
    class Entry(
        val inputType: String,
        val inputScales: List<Float>,
        val inputZeroPoints: List<Int>,
        val outputType: String,
        val outputScales: List<Float>,
        val outputZeroPoints: List<Int>
    )

    /** 查该文件名的输入 / 输出量化参数；未登记返回 null。 */
    fun read(file: File): Pair<TensorInfo, TensorInfo>? {
        val entry = TfliteQuantizationTable.entries[file.name] ?: return null
        return TensorInfo(entry.inputType, entry.inputScales, entry.inputZeroPoints) to
            TensorInfo(entry.outputType, entry.outputScales, entry.outputZeroPoints)
    }
}
