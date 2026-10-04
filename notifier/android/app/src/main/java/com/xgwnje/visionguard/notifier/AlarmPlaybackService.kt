// src/main/java/com/example/vg_notifier/AlarmPlaybackService.kt
package com.xgwnje.visionguard.notifier

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AlarmPlaybackService : Service() {
    private lateinit var sharedPreferencesHelper: SharedPreferencesHelper
    private var activeAlertKeyword: String? = null
    private var activeAlertSourceApp: String? = null
    private var activeAlertId: String? = null


    private var mediaPlayer: MediaPlayer? = null
    private var loopCompletionTimeout: Runnable? = null
    private var activePlayedLoops: Int = 0
    private var loopCycleId: Long = 0L
    private var loopCycleStartedAtMs: Long = 0L
    private var activeLoopDurationMs: Long = 0L
    private var loopCycleSettled: Boolean = true
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    companion object {
        private const val TAG = "VisionGuardListenerService"
        private const val WAKELOCK_TAG = "VisionGuard::KeywordAlertWakeLock"
        private const val FOREGROUND_NOTIFICATION_ID = 717
        private const val FOREGROUND_CHANNEL_ID = "vg_notifier_foreground_channel"
        private const val ALERT_CHANNEL_ID = "vg_notifier_active_alert_channel"
        private const val ALERT_PENDING_INTENT_REQUEST_CODE = 718
        private const val WAKELOCK_TIMEOUT_MS = 5 * 60 * 1000L  // 5 分钟，应对激进电池优化设备
        private const val PENDING_ALERT_TTL_MS = 30 * 60 * 1000L  // 未确认报警恢复窗口：30 分钟
        private const val UNKNOWN_DURATION_LOOP_TIMEOUT_MS = 60_000L
        private const val MIN_COMPLETION_PROGRESS_RATIO = 0.8

        const val ACTION_ALERT_CONFIRMED_FROM_UI = "com.xgwnje.visionguard.notifier.ACTION_ALERT_CONFIRMED_FROM_UI"

        const val ACTION_SHOW_ALERT_FROM_SERVICE = "com.xgwnje.visionguard.notifier.ACTION_SHOW_ALERT_FROM_SERVICE"
        const val EXTRA_ALERT_KEYWORD_FROM_SERVICE = "com.xgwnje.visionguard.notifier.EXTRA_ALERT_KEYWORD_FROM_SERVICE"
        const val EXTRA_ALERT_ID_FROM_SERVICE = "com.xgwnje.visionguard.notifier.EXTRA_ALERT_ID_FROM_SERVICE"
    }

    private val queueListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "alert_queue") handler.post { reconcileActiveAlert() }
    }
    private enum class PlayerState { IDLE, PREPARING, PLAYING, STOPPED }
    private var playerState = PlayerState.IDLE

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        sharedPreferencesHelper = SharedPreferencesHelper(this)
        createNotificationChannel()
        sharedPreferencesHelper.prefs.registerOnSharedPreferenceChangeListener(queueListener)
        updateForegroundNotification()
        recoverPendingAlertIfNeeded()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        reconcileActiveAlert()
        if (activeAlertId == null) stopSelf()
        return if (activeAlertId != null) START_STICKY else START_NOT_STICKY
    }
    override fun onDestroy() {
        sharedPreferencesHelper.prefs.unregisterOnSharedPreferenceChangeListener(queueListener)
        handler.removeCallbacksAndMessages(null)
        stopRingtoneAndLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun reconcileActiveAlert() {
        val persisted = sharedPreferencesHelper.getActiveAlert()
        if (activeAlertId == persisted?.id) return
        stopRingtoneAndLock()
        activeAlertId = null
        activeAlertKeyword = null
        activeAlertSourceApp = null
        if (persisted != null) startActiveAlert(persisted, persisted.snippet)
        else { emitAlertStateChanged(); stopSelf() }
    }

    private fun stopRingtoneAndLock() {
        NotificationLogger.i(applicationContext, TAG, "停止报警铃声并释放唤醒锁")
        stopRingtone()
        releaseWakeLock()
    }

    private fun recoverPendingAlertIfNeeded() {
        while (true) {
            val active = sharedPreferencesHelper.getActiveAlert() ?: return
            val expired = System.currentTimeMillis() - active.firstTriggeredAt > PENDING_ALERT_TTL_MS
            val exhausted = active.playedLoops >= active.loopLimit
            if (!expired && !exhausted) {
                NotificationLogger.w(applicationContext, TAG, "恢复报警队列 (keyword=${active.keyword}, 已播=${active.playedLoops}/${active.loopLimit}, queueSize=${sharedPreferencesHelper.getAlertQueue().size})")
                startActiveAlert(active, null)
                return
            }
            val reason = if (expired) "超过恢复窗口" else "循环次数已用完"
            NotificationLogger.w(applicationContext, TAG, "恢复时跳过队首报警: keyword=${active.keyword}, reason=$reason")
            val transition = sharedPreferencesHelper.finishActiveAlert(active.id, AlertEndType.AUTO)
            if (!transition.success) return
        }
    }

    private fun startActiveAlert(item: AlertQueueItem, snippet: String?) {
        activeAlertId = item.id
        activeAlertKeyword = item.keyword
        activeAlertSourceApp = item.sourceApp
        updateForegroundNotification()
        handler.post {
            if (sharedPreferencesHelper.getActiveAlert()?.id != item.id || activeAlertId != item.id) return@post
            acquireWakeLock()
            playRingtoneLooping(item.ringtoneUri, item.loopLimit, item.playedLoops)
            val queueSize = sharedPreferencesHelper.getAlertQueue().size
            serviceScope.launch {
                AlarmEventBus.keywordAlert.emit(
                    AlertEvent(item.id, item.keyword, item.sourceApp, item.snippet, queueSize, item.occurrenceCount)
                )
                AlarmEventBus.alertStateChanged.emit(Unit)
            }
            val activityIntent = Intent(applicationContext, MainActivity::class.java).apply {
                action = ACTION_SHOW_ALERT_FROM_SERVICE
                putExtra(EXTRA_ALERT_KEYWORD_FROM_SERVICE, item.keyword)
                putExtra(EXTRA_ALERT_ID_FROM_SERVICE, item.id)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            try {
                startActivity(activityIntent)
            } catch (e: Exception) {
                Log.e(TAG, "启动 MainActivity 时发生错误", e)
            }
        }
    }

    private fun emitAlertStateChanged() {
        serviceScope.launch { AlarmEventBus.alertStateChanged.emit(Unit) }
    }

    private fun updateForegroundNotification() {
        try {
            val notification = activeAlertKeyword?.let { keyword ->
                createActiveAlertNotification(keyword, activeAlertSourceApp)
            } ?: createForegroundServiceNotification()
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
            Log.d(TAG, "已更新前台服务通知 (activeAlert=${activeAlertKeyword != null})")
        } catch (e: Exception) {
            Log.e(TAG, "更新前台服务通知时出错", e)
        }
    }

    private fun playRingtoneLooping(
        preferredValue: String?,
        loopLimit: Int = SharedPreferencesHelper.DEFAULT_LOOP_COUNT,
        alreadyPlayed: Int = 0
    ) {
        if (preferredValue == RingtoneLibrary.SILENT_VALUE) {
            stopRingtoneAndLock()
            return
        }
        // 防止并发触发：正在准备或播放中则忽略新请求
        if (playerState == PlayerState.PREPARING || playerState == PlayerState.PLAYING) {
            Log.d(TAG, "playRingtoneLooping: 已在播放中 ($playerState)，忽略重复请求。")
            return
        }
        stopRingtone()
        val dataSource = RingtoneLibrary.resolve(applicationContext, preferredValue)
            // preferred 与默认铃声同值时跳过重复解析，避免回落警告打两遍
            ?: (if (sharedPreferencesHelper.getRingtoneValue() == preferredValue) null
                else RingtoneLibrary.resolve(applicationContext, sharedPreferencesHelper.getRingtoneValue()))
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.let { RingtoneLibrary.DataSource.ContentUri(it) }
        if (dataSource == null) {
            Log.e(TAG, "无法获取铃声数据源！")
            handlePlaybackFailure()
            return
        }
        val dataSourceDesc = when (dataSource) {
            is RingtoneLibrary.DataSource.ContentUri -> dataSource.uri.toString()
            is RingtoneLibrary.DataSource.LocalFile -> dataSource.file.absolutePath
            is RingtoneLibrary.DataSource.RawResource -> "preset:${dataSource.rawName}"
        }
        playerState = PlayerState.PREPARING
        val normalizedLoopLimit = loopLimit.coerceIn(
            SharedPreferencesHelper.MIN_LOOP_COUNT,
            SharedPreferencesHelper.MAX_LOOP_COUNT
        )
        activePlayedLoops = alreadyPlayed
        try {
            mediaPlayer = MediaPlayer().apply {
                when (dataSource) {
                    is RingtoneLibrary.DataSource.ContentUri -> setDataSource(applicationContext, dataSource.uri)
                    is RingtoneLibrary.DataSource.LocalFile -> setDataSource(dataSource.file.absolutePath)
                    is RingtoneLibrary.DataSource.RawResource -> {
                        // preset 用资源 fd 播放（android.resource:// URI 在部分平台 MediaPlayer 解析失败）
                        val afd = RingtoneLibrary.openPresetFd(applicationContext, dataSource.resId)
                            ?: throw RuntimeException("预设铃声资源打开失败: ${dataSource.rawName}")
                        try {
                            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        } finally {
                            afd.close()
                        }
                    }
                }
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setAudioAttributes(audioAttributes)
                isLooping = false
                prepareAsync()
                setOnPreparedListener { mp ->
                    if (mediaPlayer === mp && playerState == PlayerState.PREPARING) {
                        playerState = PlayerState.PLAYING
                        Log.i(TAG, "MediaPlayer 已准备好，开始播放。")
                        NotificationLogger.i(applicationContext, TAG, "报警铃声开始播放 (source=$dataSourceDesc, loopLimit=$normalizedLoopLimit, 已播=$alreadyPlayed)")
                        try {
                            startPlaybackCycle(mp, normalizedLoopLimit, seekToStart = false)
                        } catch (startEx: IllegalStateException) {
                            Log.e(TAG, "MediaPlayer 调用 start() 时出错", startEx)
                            playerState = PlayerState.STOPPED
                            handlePlaybackFailure()
                        }
                    } else {
                        // 在 prepare 期间已被取消，释放此孤立实例
                        Log.d(TAG, "onPrepared: 播放已取消 ($playerState)，释放孤立 MediaPlayer。")
                        mp.release()
                    }
                }
                setOnCompletionListener { mp ->
                    handleCompletedLoop(mp, normalizedLoopLimit, "onCompletion")
                }
                setOnErrorListener { failedPlayer, what, extra ->
                    if (mediaPlayer !== failedPlayer) return@setOnErrorListener true
                    Log.e(TAG, "MediaPlayer 播放错误: what=$what, extra=$extra, source: $dataSourceDesc")
                    NotificationLogger.e(applicationContext, TAG, "MediaPlayer 播放错误: what=$what, extra=$extra, source: $dataSourceDesc")
                    playerState = PlayerState.STOPPED
                    handlePlaybackFailure()
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "设置 MediaPlayer 数据源或准备时出错", e)
            playerState = PlayerState.STOPPED
            handlePlaybackFailure()
        }
    }

    private fun startPlaybackCycle(player: MediaPlayer, loopLimit: Int, seekToStart: Boolean) {
        cancelLoopCompletionTimeout()
        if (seekToStart) {
            player.seekTo(0)
        }
        loopCycleId++
        loopCycleSettled = false
        loopCycleStartedAtMs = SystemClock.elapsedRealtime()
        activeLoopDurationMs = player.duration.toLong().coerceAtLeast(0L)
        player.start()
        scheduleLoopCompletionTimeout(player, loopLimit, loopCycleId)
    }

    private fun scheduleLoopCompletionTimeout(player: MediaPlayer, loopLimit: Int, cycleId: Long) {
        val timeoutMs = activeLoopDurationMs.takeIf { it > 0L }
            ?: UNKNOWN_DURATION_LOOP_TIMEOUT_MS.also {
                Log.w(TAG, "无法读取铃声时长，使用 ${it}ms 有界兜底。")
            }
        loopCompletionTimeout = Runnable {
            if (mediaPlayer === player && playerState == PlayerState.PLAYING && loopCycleId == cycleId) {
                Log.w(TAG, "铃声时长已到但未收到 OnCompletion，按一次播放完成处理 (duration=${activeLoopDurationMs}ms)")
                handleCompletedLoop(player, loopLimit, "durationFallback", cycleId)
            }
        }.also { handler.postDelayed(it, timeoutMs) }
    }

    private fun cancelLoopCompletionTimeout() {
        loopCompletionTimeout?.let(handler::removeCallbacks)
        loopCompletionTimeout = null
    }

    private fun handleCompletedLoop(player: MediaPlayer, loopLimit: Int, source: String, cycleId: Long = loopCycleId) {
        if (mediaPlayer !== player || playerState != PlayerState.PLAYING || loopCycleId != cycleId) {
            Log.d(TAG, "$source: 播放已停止 ($playerState)，忽略。")
            return
        }
        if (loopCycleSettled) {
            Log.d(TAG, "$source: 当前播放轮次已结算，忽略重复回调。")
            return
        }
        if (source == "onCompletion" && activeLoopDurationMs > 0L) {
            val elapsedMs = SystemClock.elapsedRealtime() - loopCycleStartedAtMs
            val minimumValidElapsedMs = (activeLoopDurationMs * MIN_COMPLETION_PROGRESS_RATIO).toLong()
            if (elapsedMs < minimumValidElapsedMs) {
                Log.w(TAG, "$source: 收到上一轮迟到回调，忽略 (elapsed=${elapsedMs}ms, duration=${activeLoopDurationMs}ms)")
                return
            }
        }
        loopCycleSettled = true
        cancelLoopCompletionTimeout()
        activePlayedLoops++
        activeAlertId?.let { sharedPreferencesHelper.updateActiveAlertPlayedLoops(it, activePlayedLoops) }
        // 每次播完续期唤醒锁；后台存活仍受系统限制。
        releaseWakeLock()
        acquireWakeLock()
        if (activePlayedLoops >= loopLimit) {
            Log.i(TAG, "循环次数已用完 ($activePlayedLoops/$loopLimit)，自动结束报警。")
            activeAlertId?.let { finishCurrentAlert(it, AlertEndType.AUTO) }
            return
        }
        Log.d(TAG, "循环续播 ($activePlayedLoops/$loopLimit, source=$source)")
        try {
            startPlaybackCycle(player, loopLimit, seekToStart = true)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "循环续播失败", e)
            playerState = PlayerState.STOPPED
            handlePlaybackFailure()
        }
    }

    private fun handlePlaybackFailure() {
        val failedId = activeAlertId ?: return
        stopRingtoneAndLock()
        // 避免连续损坏文件递归启动队列。
        handler.post { if (activeAlertId == failedId) finishCurrentAlert(failedId, AlertEndType.ERROR) }
    }

    private fun finishCurrentAlert(expectedId: String, endType: AlertEndType) {
        val transition = sharedPreferencesHelper.finishActiveAlert(expectedId, endType)
        if (!transition.success) {
            NotificationLogger.w(applicationContext, TAG, "忽略迟到或持久化失败的结束请求: alertId=$expectedId, type=$endType")
            return
        }
        NotificationLogger.i(applicationContext, TAG, "报警结束: id=$expectedId, keyword=${transition.finished?.keyword}, type=$endType, remaining=${transition.remaining}")
        stopRingtoneAndLock()
        activeAlertId = null
        activeAlertKeyword = null
        activeAlertSourceApp = null
        val next = transition.next
        if (next != null) {
            startActiveAlert(next, null)
        } else {
            emitAlertStateChanged()
            stopSelf()
        }
    }

    private fun stopRingtone() {
        cancelLoopCompletionTimeout()
        loopCycleId++
        loopCycleSettled = true
        playerState = PlayerState.STOPPED
        mediaPlayer?.let {
            try {
                if (it.isPlaying) {
                    it.stop()
                }
                it.reset()
                it.release()
            } catch (e: Exception) {
                Log.e(TAG, "停止或释放 MediaPlayer 时出错", e)
            } finally {
                mediaPlayer = null
                playerState = PlayerState.IDLE
            }
        }
    }

    @SuppressLint("WakelockTimeout")

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) {
            Log.d(TAG, "唤醒锁已持有，无需重复获取。")
            return
        }
        releaseWakeLock()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKELOCK_TAG
        ).apply {
            try {
                acquire(WAKELOCK_TIMEOUT_MS)
                if (isHeld) {
                    Log.i(TAG, "唤醒锁已获取 (超时: ${WAKELOCK_TIMEOUT_MS / 1000}秒)。")
                } else {
                    Log.w(TAG, "调用 acquire 后，锁仍未持有？")
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取唤醒锁时出错", e)
                wakeLock = null
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                try {
                    it.release()
                    Log.i(TAG, "唤醒锁已释放。")
                } catch (e: Exception) {
                    Log.e(TAG, "释放唤醒锁时出错", e)
                }
            }
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 常规监听状态渠道：低打扰常驻。
            val foregroundChannelName = "监控服务状态"
            val foregroundChannelDesc = "VisionGuard服务运行状态通知"
            val foregroundChannel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                foregroundChannelName,
                NotificationManager.IMPORTANCE_LOW // 使用低重要性避免干扰用户
            ).apply {
                description = foregroundChannelDesc
                setShowBadge(false) // 不显示角标
            }

            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(foregroundChannel)

            // 报警进行中渠道：通知栏明显可见，但不额外发声或振动，铃声由 MediaPlayer 负责。
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "报警进行中",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "节点报警触发时提供快速进入应用的处理入口"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            notificationManager.createNotificationChannel(alertChannel)

            Log.d(TAG,"前台服务通知渠道已创建/更新")
        }
    }

    private fun createActiveAlertNotification(keyword: String, sourceApp: String?): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_SHOW_ALERT_FROM_SERVICE
            putExtra(EXTRA_ALERT_KEYWORD_FROM_SERVICE, keyword)
            activeAlertId?.let { putExtra(EXTRA_ALERT_ID_FROM_SERVICE, it) }
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            ALERT_PENDING_INTENT_REQUEST_CODE,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val detail = sourceApp?.let { "检测到“$keyword”，来自 $it；点击进入处理" }
            ?: "检测到“$keyword”；点击进入处理"
        val publicVersion = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setColor(ContextCompat.getColor(this, R.color.md_theme_error))
            .setContentTitle("VisionGuard 报警进行中")
            .setContentText("点击进入应用处理")
            .setSmallIcon(R.drawable.ic_lucide_bell_ring)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        return NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setColor(ContextCompat.getColor(this, R.color.md_theme_error))
            .setContentTitle("VisionGuard 报警进行中")
            .setContentText(detail)
            .setSmallIcon(R.drawable.ic_lucide_bell_ring)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createForegroundServiceNotification(): Notification {
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setColor(ContextCompat.getColor(this, R.color.md_theme_primary))
            .setContentTitle("通知节点").setContentText("恢复已保存的报警队列")
            .setSmallIcon(R.drawable.ic_lucide_bell).setContentIntent(launch).setOngoing(true).setSilent(true).build()
    }
}
