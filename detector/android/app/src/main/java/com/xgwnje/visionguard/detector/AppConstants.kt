package com.xgwnje.visionguard.detector

/** Build version and default service only. Accounts and sessions are configured at runtime. */
object AppConstants {
    const val VERSION = "0.6.6"
    val SERVER_URL: String = BuildConfig.SERVER_URL
}
