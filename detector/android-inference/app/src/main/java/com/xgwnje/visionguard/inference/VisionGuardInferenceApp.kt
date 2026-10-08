package com.xgwnje.visionguard.inference

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

/** 应用入口：只创建长期存在的通知渠道，运行状态由推理服务维护。 */
class VisionGuardInferenceApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                INFERENCE_CHANNEL_ID,
                "推理运行",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "视觉推理节点后台检测与连接状态" }
        )
    }

    companion object {
        const val INFERENCE_CHANNEL_ID = "visionguard-inference"
    }
}
