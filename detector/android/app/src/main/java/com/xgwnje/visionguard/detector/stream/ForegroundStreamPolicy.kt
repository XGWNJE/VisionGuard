package com.xgwnje.visionguard.detector.stream

/** Returning to the foreground never restores a previous camera session. */
class ForegroundStreamPolicy {
    var foreground: Boolean = false
        private set
    var streaming: Boolean = false
        private set
    var permissionStartPending: Boolean = false
        private set
    fun resumed() { foreground = true }
    fun requestPermission() { permissionStartPending = foreground && !streaming }
    fun cancelPermissionRequest() { permissionStartPending = false }
    fun start(): Boolean {
        if (!foreground) return false
        permissionStartPending = false
        streaming = true
        return true
    }
    fun stop() { streaming = false; cancelPermissionRequest() }
    fun leftForeground() { foreground = false; streaming = false }
}
