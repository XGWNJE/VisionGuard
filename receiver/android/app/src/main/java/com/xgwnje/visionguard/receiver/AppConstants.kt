package com.xgwnje.visionguard.receiver

/** Build version and default service only. Accounts and sessions are configured at runtime. */
object AppConstants {
    const val VERSION = "0.6.5"
    val SERVER_URL: String = BuildConfig.SERVER_URL
}
