package com.xgwnje.visionguard.inference.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xgwnje.visionguard.inference.MainActivity
import com.xgwnje.visionguard.inference.R
import com.xgwnje.visionguard.inference.VisionGuardInferenceApp
import com.xgwnje.visionguard.inference.runtime.InferenceRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 推理运行的前台服务：让本机相机与远程镜头的推理在界面退到后台、锁屏后继续运行。
 *
 * 与 Windows 的“后台驻留”职责对应但形态不同：Android 用前台服务保持进程与相机绑定，
 * 语言上仍是推理节点的一部分，不新增组件名。服务不改变运行意图，只负责保活与状态展示；
 * 进程被系统回收后以 `START_STICKY` 重建，并按已保存的运行意图恢复来源。
 */
class InferenceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runtime: InferenceRuntime
    private var lastText: String = ""

    override fun onCreate() {
        super.onCreate()
        runtime = InferenceRuntime.get(this)
        startForeground(NOTIFICATION_ID, buildNotification("正在准备推理"))
        scope.launch {
            runtime.state.collect { state ->
                val text = when {
                    state.runningSourceCount > 0 -> "正在推理 · ${state.runningSourceCount} 路来源"
                    state.sources.isNotEmpty() -> "已停止 · ${state.sources.size} 路来源"
                    else -> "未配置来源"
                }
                if (text != lastText) {
                    lastText = text
                    notificationManager().notify(NOTIFICATION_ID, buildNotification(text))
                }
            }
        }
        scope.launch {
            // 退出登录后没有可推理的账号，保留空转的前台服务只会占着通知。
            runtime.account.session.collect { session -> if (session == null) stopSelf() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val content = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, VisionGuardInferenceApp.INFERENCE_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_lucide_monitor)
            .setOngoing(true)
            .setContentIntent(content)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notificationManager() = getSystemService(android.app.NotificationManager::class.java)

    companion object {
        private const val NOTIFICATION_ID = 4101

        /**
         * 幂等启动：已在运行时不会重复创建服务。
         * 返回是否成功发起启动；应用在后台时系统会拒绝启动前台服务（Android 12+），
         * 这里必须返回失败而不是抛异常打挂界面线程。
         */
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, InferenceService::class.java))
            true
        }.getOrDefault(false)

        fun stop(context: Context) {
            context.stopService(Intent(context, InferenceService::class.java))
        }
    }
}
