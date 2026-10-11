package com.xgwnje.visionguard.inference.model

/**
 * 同一个模型键下的产物形态。
 *
 * 两种产物是两个运行时的两种图，不能互替：
 * - [ONNX]：正式档（ONNX Runtime CPU）用，图内自带 NMS，输出 `[1,300,6]`；
 * - [GPU_TFLITE]：GPU 加速用，图尾的 int64 后处理被截掉（否则整图编译失败），
 *   输出 `[1,84,N]` 锚点，NMS 在端侧解析。
 */
enum class ModelFormat(
    /** 文件名后缀，出现在服务端 `/models/<键><后缀>` 上。 */
    val suffix: String,
    /** 低于该字节数视为未下载完成，防止半截文件当模型用。 */
    val minBytes: Long
) {
    ONNX("_onnx", 1L * 1024 * 1024),
    GPU_TFLITE("_gpu", 1L * 1024 * 1024)
}
