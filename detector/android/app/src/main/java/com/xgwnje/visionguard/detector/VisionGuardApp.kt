package com.xgwnje.visionguard.detector

import android.app.Application
import com.xgwnje.visionguard.detector.util.NotificationHelper

class VisionGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
    }
}
