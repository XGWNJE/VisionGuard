package com.xgwnje.visionguard.detector.stream

/** Returning to the foreground never restores a previous camera session. */
class ForegroundStreamPolicy {
    var foreground: Boolean = false
        private set
    var streaming: Boolean = false
        private set
    fun resumed() { foreground = true }
    fun start(): Boolean {
        if (!foreground) return false
        streaming = true
        return true
    }
    fun stop() { streaming = false }
    fun leftForeground() { foreground = false; streaming = false }
}
