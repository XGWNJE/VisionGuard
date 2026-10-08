package com.xgwnje.visionguard.notifier.node

/** Ignore registration repeats and a previous route's late onLost after a network switch. */
internal class DefaultNetworkChanges<T>(initial: T?) {
    private var current = initial
    fun available(network: T): Boolean {
        if (current == network) return false
        current = network
        return true
    }
    fun lost(network: T): Boolean {
        if (current != network) return false
        current = null
        return true
    }
}
