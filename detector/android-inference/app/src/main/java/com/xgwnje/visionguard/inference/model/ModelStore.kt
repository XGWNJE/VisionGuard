package com.xgwnje.visionguard.inference.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Android 推理端的模型清单：**两条后端路线 × 两个尺寸 = 4 档**。
 *
 * - GPU 档 `<键>`（`yolo26m_320` / `yolo26m_640`）：只有截断图 `<键>_gpu.tflite`，**不下 ONNX 本体**；
 * - CPU 档 `<键>_int8`（`yolo26m_320_int8` / `yolo26m_640_int8`）：只有量化版 `<键>.onnx`。
 *
 * 为什么 CPU 不跑 FP32：同尺寸 INT8 在 CPU 上快 1.5–2.0 倍、体积小 3–4 倍；FP32 只作为 GPU
 * 截断图的来源存在。所以 GPU 用不了时回落到**同尺寸 INT8**，不是回落 FP32。
 *
 * 为什么 320 也要 GPU 档：加速路线上性能不足时按尺寸降级，与 CPU 路线的 320 INT8 对应。
 * 后端随档位绑定，用户只选档位，不再单独选 CPU / GPU。
 */
object ModelKeys {
    /** 量化版后缀，也是 CPU 档的键名后缀。 */
    const val INT8_SUFFIX = "_int8"

    /** GPU 档：只分发截断图。 */
    val gpuKeys: List<String> = listOf("yolo26m_320", "yolo26m_640")

    /** CPU 档：只分发量化版本体。 */
    val cpuKeys: List<String> = gpuKeys.map { it + INT8_SUFFIX }

    /** 界面顺序：同一尺寸的 GPU 与 CPU 成对出现。 */
    val keys: List<String> = gpuKeys.flatMap { listOf(it, it + INT8_SUFFIX) }

    /** 新装设备的默认档：最小、CPU 上一定能跑。 */
    val defaultKey: String = cpuKeys.first()

    fun isSupported(key: String): Boolean = keys.contains(key)

    fun supportsGpu(key: String): Boolean = gpuKeys.contains(key)

    /** 是否为 CPU（量化）档。 */
    fun isQuantized(key: String): Boolean = cpuKeys.contains(key)

    /** 去掉量化后缀，得到尺寸基名（`yolo26m_320`）。 */
    fun baseOf(key: String): String = key.removeSuffix(INT8_SUFFIX)

    /** 该键的实际产物形态：GPU 档走截断图，CPU 档走量化本体。 */
    fun formatOf(key: String): ModelFormat = if (supportsGpu(key)) ModelFormat.GPU_TFLITE else ModelFormat.ONNX

    /**
     * 同尺寸的 CPU 档键；本身就是 CPU 档时返回自己。
     *
     * GPU 用不了时按同尺寸降级到它，而不是换尺寸——尺寸是用户对性能与精度的选择。
     */
    fun cpuTierOf(key: String): String? = (baseOf(key) + INT8_SUFFIX).takeIf { cpuKeys.contains(it) }

    /**
     * 模型文件名。
     *
     * 服务端 `/models` 按文件名静态分发：CPU 档天然对应 `<键>.onnx`，GPU 档对应 `<基名>_gpu.tflite`，
     * 都不需要额外映射。
     */
    fun fileName(key: String, format: ModelFormat = formatOf(key)): String = when (format) {
        ModelFormat.ONNX -> "$key.onnx"
        ModelFormat.GPU_TFLITE -> "${baseOf(key)}_gpu.tflite"
    }

    /** 本端清单内的全部文件名，用于盘点并清理清单外的残留。 */
    fun fileNameSet(): Set<String> = keys.map { fileName(it) }.toSet()

    /**
     * 界面展示名，例如 `YOLO26M · 320 GPU`、`YOLO26M · 320 CPU`。
     *
     * 后端写在名字里：用户选档位就是选后端，不再有第二个开关。无法识别的键原样返回，不猜。
     */
    fun displayName(key: String): String {
        val parts = baseOf(key).split('_')
        if (parts.size != 2 || !parts[0].startsWith("yolo26")) return key
        val size = parts[1]
        val variant = parts[0].removePrefix("yolo26").uppercase()
        return "YOLO26$variant · $size " + if (supportsGpu(key)) "GPU" else "CPU"
    }
}

/**
 * 模型按需下载与本地缓存。
 *
 * 与 Windows `Utils/ModelManager.cs` 一致：模型不入版本控制、不随安装包分发，
 * 从统一服务的 `/models/<文件名>` 静态端点按需下载；下载先写 `.tmp`，校验声明长度后
 * 再替换正式文件，长度不符时删除临时文件并报可读原因。清单外的历史文件由
 * [cleanupUnknownFiles] 盘点清理，避免旧档位一直占着磁盘。
 */
class ModelStore(private val modelsDir: File, private val serverUrl: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun file(key: String, format: ModelFormat = ModelKeys.formatOf(key)): File =
        File(modelsDir, ModelKeys.fileName(key, format))

    fun isDownloaded(key: String): Boolean {
        val format = ModelKeys.formatOf(key)
        val target = file(key, format)
        return target.isFile && target.length() >= format.minBytes
    }

    /** 已下载的档位键，按清单顺序。 */
    fun downloadedKeys(): List<String> = ModelKeys.keys.filter { isDownloaded(it) }

    /** GPU 档是否就绪：有产物定义且截断图已下载。 */
    fun isGpuReady(key: String): Boolean = ModelKeys.supportsGpu(key) && isDownloaded(key)

    /** GPU 档的截断图文件；该键不是 GPU 档时返回 null。 */
    fun gpuFile(key: String): File? =
        if (ModelKeys.supportsGpu(key)) file(key, ModelFormat.GPU_TFLITE) else null

    /** CPU 引擎要加载的 ONNX 文件：GPU 档取同尺寸 CPU 档，CPU 档取自己；取不到返回 null。 */
    fun cpuFile(key: String): File? =
        ModelKeys.cpuTierOf(key)?.let { file(it, ModelFormat.ONNX) }

    /**
     * 下载指定档位的产物；成功返回 null，失败返回可读原因。
     * [onProgress] 参数为已接收字节与声明总字节（未知时为 -1）。
     */
    suspend fun download(
        key: String,
        format: ModelFormat = ModelKeys.formatOf(key),
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): String? = withContext(Dispatchers.IO) {
        if (!ModelKeys.isSupported(key)) return@withContext "本端不支持模型 $key"
        if (format == ModelFormat.GPU_TFLITE && !ModelKeys.supportsGpu(key)) {
            return@withContext "模型 $key 没有 GPU 版本"
        }
        // GPU 档不下 ONNX 本体：CPU 路线的正路是同尺寸 INT8，本体只会白占一倍磁盘。
        if (format == ModelFormat.ONNX && ModelKeys.supportsGpu(key)) {
            return@withContext "模型 $key 是 GPU 档，只下载截断图"
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

    /** 删除已下载的档位产物，用于释放空间；不删除正在使用的模型由调用方保证。 */
    suspend fun delete(key: String, format: ModelFormat = ModelKeys.formatOf(key)): Boolean =
        withContext(Dispatchers.IO) { file(key, format).delete() }

    /**
     * 删除清单外的残留文件（旧档位模型、半截 `.tmp`），返回（文件数, 字节数）。
     *
     * 档位收敛后设备上会留下 `yolo26n_*` / `yolo26s_*` 与旧 GPU 产物；它们已经不可能被选中，
     * 留着只是占空间。只动 `files/models/` 这一个目录，不碰账号、配置与业务数据。
     */
    suspend fun cleanupUnknownFiles(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val known = ModelKeys.fileNameSet()
        var files = 0
        var bytes = 0L
        modelsDir.listFiles()?.forEach { candidate ->
            if (!candidate.isFile || candidate.name in known) return@forEach
            val size = candidate.length()
            if (candidate.delete()) {
                files += 1
                bytes += size
            }
        }
        files to bytes
    }

    companion object {
        private const val DOWNLOAD_TIMEOUT_SECONDS = 600L
    }
}
