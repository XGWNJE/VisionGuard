package com.xgwnje.visionguard.detector.util

// ┌─────────────────────────────────────────────────────────┐
// │ NotificationHelper.kt                                   │
// │ 角色：通知渠道注册 + 前台服务通知构建                    │
// │ 渠道：ALERT_CHANNEL（HIGH）+ FOREGROUND_CHANNEL（LOW）   │
// │ 对外 API：createChannels(), buildForegroundNotification() │
// └─────────────────────────────────────────────────────────┘

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import com.xgwnje.visionguard.detector.MainActivity
import com.xgwnje.visionguard.detector.R

object NotificationHelper {

    const val ALERT_CHANNEL_ID      = "vg_alert"
    const val FOREGROUND_CHANNEL_ID = "vg_foreground"
    const val FOREGROUND_NOTIF_ID   = 1

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 报警通知：HIGH 优先级，声音+振动+呼吸灯
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "检测报警",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "${context.getString(R.string.app_name)}目标告警"
            enableVibration(true)
            enableLights(true)  // 呼吸灯
        }

        // 前台服务常驻通知：LOW 优先级，静默
        val fgChannel = NotificationChannel(
            FOREGROUND_CHANNEL_ID,
            "检测服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "${context.getString(R.string.app_name)}运行状态"
        }

        nm.createNotificationChannels(listOf(alertChannel, fgChannel))
    }

    fun buildForegroundNotification(context: Context, stateText: String): Notification {
        val openIntent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, FOREGROUND_CHANNEL_ID)
        setSmallAppIcon(builder, context)
        builder.setContentTitle(context.getString(R.string.app_name))
            .setContentText(stateText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pi)
        return builder.build()
    }

    /** 将 smallIcon 设为完整应用图标（24dp 标准通知尺寸） */
    private fun setSmallAppIcon(builder: NotificationCompat.Builder, context: Context) {
        val bitmap = getAppIconBitmap(context, sizeDp = 24)
        if (bitmap != null) {
            builder.setSmallIcon(IconCompat.createWithBitmap(bitmap))
        } else {
            builder.setSmallIcon(R.mipmap.ic_launcher_foreground)
        }
    }

    /**
     * 获取应用图标 Bitmap（自适应图标也会合成完整图像）。
     * @param sizeDp 目标尺寸（dp），通知 smallIcon 标准 24dp，原图传 0。
     */
    private fun getAppIconBitmap(context: Context, sizeDp: Int = 0): Bitmap? {
        return try {
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            val density = context.resources.displayMetrics.density
            val w: Int
            val h: Int
            if (sizeDp > 0) {
                w = (sizeDp * density).toInt().coerceAtLeast(1)
                h = w
            } else {
                w = drawable.intrinsicWidth.coerceAtLeast(1)
                h = drawable.intrinsicHeight.coerceAtLeast(1)
            }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (_: Exception) { null }
    }

}
