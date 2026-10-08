package com.xgwnje.visionguard.notifier.node

/** All deadlines use elapsed time. A delayed scheduler must give a fresh probe a chance to respond. */
class ConnectionRecovery {
    enum class Action { NONE, PROBE, RECONNECT, CONFIRM_FAILURE }
    private enum class Phase { CONNECTING, HEALTHY, PROBING, VERIFYING }
    private var phase = Phase.CONNECTING
    private var attemptAt = 0L
    private var responseAt = 0L
    private var probeAt = 0L
    private var lastTick: Long? = null
    private var verificationStarted = false
    val probing: Boolean get() = phase == Phase.PROBING

    fun networkChanged(now: Long) {
        phase = Phase.CONNECTING
        verificationStarted = false
        lastTick = now
        attempt(now)
    }

    fun attempt(now: Long) {
        attemptAt = now
        responseAt = now
        verificationStarted = phase == Phase.VERIFYING
    }
    fun responded(now: Long) {
        responseAt = now
        phase = Phase.HEALTHY
        verificationStarted = false
    }
    fun failed(now: Long): Action {
        val delayed = lastTick?.let { now < it || now - it > 10_000 } ?: false
        lastTick = now
        val confirmed = !delayed && phase == Phase.VERIFYING && verificationStarted
        phase = Phase.VERIFYING
        verificationStarted = false
        return if (confirmed) Action.CONFIRM_FAILURE else Action.RECONNECT
    }
    fun tick(now: Long, hasSocket: Boolean, authenticated: Boolean): Action {
        val delayed = lastTick?.let { now < it || now - it > 10_000 } ?: false
        lastTick = now
        if (!hasSocket) return Action.NONE
        if (delayed) {
            attemptAt = now
            if (authenticated) { phase = Phase.PROBING; probeAt = now; return Action.PROBE }
            return Action.NONE
        }
        if (!authenticated) {
            if (now - attemptAt >= 12_000) return failed(now)
            return Action.NONE
        }
        if (phase == Phase.PROBING && now - probeAt >= 12_000) return failed(now)
        if (phase == Phase.HEALTHY && now - responseAt >= 45_000) {
            phase = Phase.PROBING; probeAt = now
            return Action.PROBE
        }
        return Action.NONE
    }
}
