package com.xgwnje.visionguard.inference.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Android 推理端的模型清单。
 *
 * 与 Windows modern 档同一套 YOLO26 模型（`[1,300,6]`，模型内含 NMS）：Android 的
 * ONNX Runtime 属于现代档，不需要 Win7 legacy 的 YOLOv5 模型。
 *
 * 每个键最多有两种产物；清单里每个基名两项，合起来 12 项：
 * - `<键>.onnx` FP32 本体：图内自带 NMS，走 ONNX Runtime CPU；有 GPU 产物的键可以改用 GPU；
 * - `<键>_int8.onnx` 量化版：与本体并存、键名带 `_int8`，**只走 ONNX Runtime CPU**
 *   （量化版没有 GPU 产物，也不会被送去 LiteRT——LiteRT 读 `.tflite`，量化版只有 `.onnx`）。
 *
 * 产物与路线一一对应，别混：
 * - CPU 正式档 = ONNX Runtime · CPU 4 线程 + int8 或 FP32 本体；
 * - GPU 档 = LiteRT GPU + `<基名>_gpu.tflite`（截断图，图尾 int64 后处理被截掉，否则整图编译失败），
 *   NMS 移到端侧解析，只有 [gpuKeys] 里的键才有，且只在 640 及以上值得用。
 */
object ModelKeys {
    /** 原始（FP32）模型键，顺序即默认选择顺序。 */
    val baseKeys: List<String> = listOf(
        "yolo26n_320", "yolo26n_640",
        "yolo26s_320", "yolo26s_640",
        "yolo26m_320", "yolo26m_640"
    )

    /** 量化版后缀。 */
    const val INT8_SUFFIX = "_int8"

    /** 当前可选键：本体与量化版交替（12 项）。 */
    val keys: List<String> = derivedKeys()

    /**
     * 有 GPU 产物、允许走加速的键。
     *
     * 只放实测有效的组合：GPU 在 320 上反而不如 CPU（14.4 对 7.4 ms），所以列的全是 640；
     * 服务端磁盘余量有限，先投放 `yolo26m_640`（同轮 67.5 对 255.7 ms，收益最大）。
     * 新增键前必须先补测，否则端侧会拿一个没量过的产物去跑正式链路。
     */
    val gpuKeys: List<String> = listOf("yolo26m_640")

    /**
     * 本体与量化版交替的全量清单（`本体、量化版、本体、量化版…`）。
     *
     * 顺序不能随意调整：[InferenceRuntime.defaultModelKey] 取「第一个已下载的键」，
     * 量化版排在各自本体之后，新装设备的默认模型才仍是 FP32。
     */
    fun derivedKeys(): List<String> = baseKeys.flatMap { listOf(it, it + INT8_SUFFIX) }

    val defaultKey: String = baseKeys.first()

    fun isSupported(key: String): Boolean = keys.contains(key)

    /**
     * 该键是否有 GPU 产物（控制下载入口与是否尝试加速）。
     *
     * 量化版一律 false：这些量化产物的边界是 float32、图内 int8，端侧没有它们的边界量化登记表，
     * 送去 LiteRT 会在写缓冲时报尺寸不符；走 ONNX Runtime CPU 才是它们的正路。
     */
    fun supportsGpu(key: String): Boolean =
        keys.contains(key) && !isQuantized(key) && gpuKeys.contains(key)

    /** 是否为量化版键。 */
    fun isQuantized(key: String): Boolean = key.endsWith(INT8_SUFFIX)

    /** 量化版键对应的本体键。 */
    fun baseOf(key: String): String = key.removeSuffix(INT8_SUFFIX)

    /**
     * 模型文件名。
     *
     * 服务端 `/models` 按文件名静态分发，因此量化版键天然对应 `<键>.onnx`，不需要额外映射；
     * GPU 产物是同键的另一个文件名，也不引入服务端映射。
     */
    fun fileName(key: String, format: ModelFormat = ModelFormat.ONNX): String = when (format) {
        ModelFormat.ONNX -> "$key.onnx"
        ModelFormat.GPU_TFLITE -> "${key}_gpu.tflite"
    }

    /**
     * 界面展示名，例如 `YOLO26n · 320`、`YOLO26n · 320 INT8`。
     *
     * 无法识别的键原样返回，不做猜测。
     */
    fun displayName(key: String): String {
        val quantized = isQuantized(key)
        val parts = baseOf(key).split('_')
        if (parts.size != 2 || !parts[0].startsWith("yolo26")) return key
        val size = parts[1]
        val variant = parts[0].removePrefix("yolo26").uppercase()
        return "YOLO26$variant · $size" + if (quantized) " INT8" else ""
    }
}

/**
 * 模型按需下载与本地缓存。
 *
 * 与 Windows `Utils/ModelManager.cs` 一致：模型不入版本控制、不随安装包分发，
 * 从统一服务的 `/models/<文件名>` 静态端点按需下载；下载先写 `.tmp`，校验声明长度后
 * 再替换正式文件，长度不符时删除临时文件并报可读原因。
 */
class ModelStore(private val modelsDir: File, private val serverUrl: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun file(key: String, format: ModelFormat = ModelFormat.ONNX): File =
        File(modelsDir, ModelKeys.fileName(key, format))

    fun isDownloaded(key: String, format: ModelFormat = ModelFormat.ONNX): Boolean {
        val target = file(key, format)
        return target.isFile && target.length() >= format.minBytes
    }

    /** 已下载的模型键，按清单顺序（只算本体）。 */
    fun downloadedKeys(): List<String> = ModelKeys.keys.filter { isDownloaded(it) }

    /** 该键是否可用 GPU：有产物定义且 GPU 产物已下载。 */
    fun isGpuReady(key: String): Boolean = ModelKeys.supportsGpu(key) && isDownloaded(key, ModelFormat.GPU_TFLITE)

    /** GPU 产物文件；该键没有 GPU 产物时返回 null。 */
    fun gpuFile(key: String): File? =
        if (ModelKeys.supportsGpu(key)) file(key, ModelFormat.GPU_TFLITE) else null

    /**
     * 下载指定模型的指定产物；成功返回 null，失败返回可读原因。
     * [onProgress] 参数为已接收字节与声明总字节（未知时为 -1）。
     */
    suspend fun download(
        key: String,
        format: ModelFormat = ModelFormat.ONNX,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): String? = withContext(Dispatchers.IO) {
        if (!ModelKeys.isSupported(key)) return@withContext "本端不支持模型 $key"
        if (format == ModelFormat.GPU_TFLITE && !ModelKeys.supportsGpu(key)) {
            return@withContext "模型 $key 没有 GPU 版本"
        }
        modelsDir.mkdirs()
        val fileName = ModelKeys.fileName(key, format)
        val target = File(modelsDir, fileName)
        val temporary = File(modelsDir, "$fileName.tmp")
        val request = Request.Builder().url("$serverUrl/models/$fileName").get().build()

        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "下载失败：HTTP ${response.code}"
                val body = response.body ?: return@withContext "下载失败：响应为空"
                val declared = body.contentLength()
                var received = 0L
                body.byteStream().use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            received += read
                            onProgress(received, declared)
                        }
                    }
                }
                if (declared > 0 && received != declared) {
                    temporary.delete()
                    return@withContext "下载不完整（$received/$declared 字节）"
                }
                if (received < format.minBytes) {
                    temporary.delete()
                    return@withContext "下载内容异常（仅 $received 字节）"
                }
            }
        } catch (error: Exception) {
            temporary.delete()
            return@withContext "下载失败：${error.message ?: error::class.java.simpleName}"
        }

        // 与 Windows 一致：替换前先删旧文件；目标已存在时 File.renameTo 会直接失败。
        if (target.exists() && !target.delete()) {
            temporary.delete()
            return@withContext "无法替换已有模型文件"
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            return@withContext "无法保存模型文件"
        }
        null
    }

    /** 删除已下载模型产物，用于释放空间；不删除正在使用的模型由调用方保证。 */
    suspend fun delete(key: String, format: ModelFormat = ModelFormat.ONNX): Boolean =
        withContext(Dispatchers.IO) { file(key, format).delete() }

    companion object {
        private const val DOWNLOAD_TIMEOUT_SECONDS = 600L
    }
}
