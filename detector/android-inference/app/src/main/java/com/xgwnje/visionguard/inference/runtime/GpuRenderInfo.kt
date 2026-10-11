package com.xgwnje.visionguard.inference.runtime

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import com.xgwnje.visionguard.inference.model.AccelerationMeasure

/**
 * GPU 渲染器 / 厂商 / 驱动版本：加速档读数跨设备归因用的指纹。
 *
 * 为什么是 GL 字符串：Android 没有公开的「GPU 型号 + 驱动版本」接口，但 ML Drift（LiteRT GPU）
 * 走的 `libLiteRtClGlAccelerator.so` 用的正是这套厂商 GL/OpenCL 驱动，`GL_RENDERER` 与
 * `GL_VERSION` 就是驱动自己报的型号与版本（Mali 形如 `Mali-G57 MC2` / `OpenGL ES 3.2
 * v1.r38p1-01eac0…`，Adreno 形如 `Adreno (TM) 650` / `OpenGL ES 3.2 V@0530.0`）。
 *
 * 用一个 1×1 的离屏 pbuffer 建上下文，不占窗口、不需要在前台，拿完立刻释放。任何一步失败都
 * 返回 null——报告里少一行，好过写一个编造的型号。
 */
object GpuRenderInfo {

    private const val TAG = "VGInferenceBench"

    /** 取得形如 `Mali-G57 MC2（ARM，OpenGL ES 3.2 v1.r38p1-01eac0）` 的指纹；拿不到返回 null。 */
    fun fingerprint(): String? = runCatching {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay 失败" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize 失败" }

        var config: EGLConfig? = null
        var context: EGLContext? = null
        var surface: EGLSurface? = null
        try {
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(
                EGL14.eglChooseConfig(
                    display,
                    intArrayOf(
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_NONE
                    ),
                    0, configs, 0, configs.size, count, 0
                ) && count[0] > 0
            ) { "没有可用的 EGL 配置" }
            config = configs[0]

            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            check(context != null && context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext 失败" }

            surface = EGL14.eglCreatePbufferSurface(
                display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            check(surface != null && surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface 失败" }

            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent 失败" }

            AccelerationMeasure.gpuFingerprint(
                renderer = GLES20.glGetString(GLES20.GL_RENDERER),
                vendor = GLES20.glGetString(GLES20.GL_VENDOR),
                version = GLES20.glGetString(GLES20.GL_VERSION)
            )
        } finally {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (surface != null && surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != null && context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }.onFailure { Log.i(TAG, "取 GPU 指纹失败：${it.message}") }.getOrNull()
}
