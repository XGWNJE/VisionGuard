package com.xgwnje.visionguard.receiver

// ┌─────────────────────────────────────────────────────────┐
// │ VisionGuardApp.kt                                       │
// │ 角色：Application 类，初始化通知渠道                      │
// └─────────────────────────────────────────────────────────┘

import android.app.Application
import com.xgwnje.visionguard.receiver.util.NotificationHelper
import com.xgwnje.visionguard.receiver.util.NtpSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VisionGuardApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        appScope.launch { NtpSync.sync() }
    }
}
