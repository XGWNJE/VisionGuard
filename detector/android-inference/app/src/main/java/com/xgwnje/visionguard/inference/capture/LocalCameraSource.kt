package com.xgwnje.visionguard.inference.capture

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.xgwnje.visionguard.inference.detect.ArgbImage
import com.xgwnje.visionguard.inference.source.SourceConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 本机相机采集状态。 */
data class LocalCameraStatus(
    val running: Boolean = false,
    val error: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val rotation: Int = 0
)

/**
 * 本机相机来源：CameraX `ImageAnalysis` 直接输出 RGBA 画面。
 *
 * 与相机推流节点不同，推理端不把画面发给服务；这里只取最新一帧给推理循环，因此使用
 * `STRATEGY_KEEP_ONLY_LATEST` 且不做帧节流——采样节拍由来源自身按目标帧率控制。
 *
 * 采集绑定自身 [LifecycleOwner]，因此应用退到后台、界面销毁后仍能继续推理（前台服务持有）。
 */
class LocalCameraSource {

    private val mutableStatus = MutableStateFlow(LocalCameraStatus())
    val status = mutableStatus.asStateFlow()

    private val owner = object : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun create() {
            registry.currentState = Lifecycle.State.CREATED
            registry.currentState = Lifecycle.State.RESUMED
        }
        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private var executor: ExecutorService? = null
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var generation = 0

    @Volatile
    private var latest: ArgbImage? = null

    /** 取最新一帧；没有新画面时返回同一对象，由来源侧按引用判断是否是新帧。 */
    fun latestFrame(): ArgbImage? = latest

    fun start(context: Context, lensFacing: Int): String? {
        if (analysis != null) return null
        val appContext = context.applicationContext
        val own = ++generation
        val worker = Executors.newSingleThreadExecutor()
        executor = worker
        owner.create()

        val future = ProcessCameraProvider.getInstance(appContext)
        // 绑定必须在主线程发起，用主线程执行器回调。
        future.addListener({
            if (own != generation) return@addListener
            val resolved = runCatching { future.get() }.getOrElse { error ->
                mutableStatus.value = LocalCameraStatus(error = "相机初始化失败：${error.message ?: "未知原因"}")
                stopInternal()
                return@addListener
            }
            val cameraAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(TARGET_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        )
                        .build()
                )
                .build()

            cameraAnalysis.setAnalyzer(worker) { image -> handleImage(image) }
            val selector = if (lensFacing == SourceConfig.LENS_FRONT) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            try {
                resolved.unbindAll()
                resolved.bindToLifecycle(owner, selector, cameraAnalysis)
                provider = resolved
                analysis = cameraAnalysis
                mutableStatus.value = LocalCameraStatus(running = true)
            } catch (error: Exception) {
                runCatching { cameraAnalysis.clearAnalyzer() }
                mutableStatus.value = LocalCameraStatus(error = "无法绑定相机：${error.message ?: "未知原因"}")
                stopInternal()
            }
        }, ContextCompat.getMainExecutor(appContext))
        return null
    }

    fun stop() {
        stopInternal()
        mutableStatus.value = LocalCameraStatus()
    }

    private fun stopInternal() {
        ++generation
        runCatching { analysis?.clearAnalyzer() }
        analysis = null
        runCatching { provider?.unbindAll() }
        provider = null
        latest = null
        runCatching { owner.destroy() }
        executor?.shutdown()
        executor = null
    }

    private fun handleImage(image: ImageProxy) {
        try {
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val width = image.width
            val height = image.height
            val pixels = IntArray(width * height)

            buffer.rewind()
            val row = ByteArray(rowStride)
            for (y in 0 until height) {
                val remaining = buffer.remaining()
                if (remaining <= 0) break
                val toRead = minOf(rowStride, remaining)
                buffer.get(row, 0, toRead)
                var sourceIndex = 0
                var targetIndex = y * width
                for (x in 0 until width) {
                    val r = row[sourceIndex].toInt() and 0xFF
                    val g = row[sourceIndex + 1].toInt() and 0xFF
                    val b = row[sourceIndex + 2].toInt() and 0xFF
                    val a = row[sourceIndex + 3].toInt() and 0xFF
                    pixels[targetIndex] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    sourceIndex += pixelStride
                    targetIndex++
                }
            }

            val rotated = ArgbImage(width, height, pixels).rotate(image.imageInfo.rotationDegrees)
            latest = rotated
            val current = mutableStatus.value
            if (!current.running || current.width != rotated.width || current.height != rotated.height) {
                mutableStatus.value = LocalCameraStatus(
                    running = true,
                    width = rotated.width,
                    height = rotated.height,
                    rotation = image.imageInfo.rotationDegrees
                )
            }
        } catch (error: Exception) {
            mutableStatus.value = LocalCameraStatus(error = "画面转换失败：${error.message ?: "未知原因"}")
        } finally {
            image.close()
        }
    }

    companion object {
        /** 推理输入最长边 640、短边 480 足够，避免把高分辨率画面搬进内存。 */
        private val TARGET_SIZE = android.util.Size(640, 480)
    }
}
