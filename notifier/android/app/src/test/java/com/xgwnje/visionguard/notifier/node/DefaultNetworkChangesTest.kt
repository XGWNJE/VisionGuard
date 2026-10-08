package com.xgwnje.visionguard.notifier.node

import org.junit.Assert.*
import org.junit.Test

class DefaultNetworkChangesTest {
    @Test fun registrationAndDuplicateCallbacksKeepTheCurrentConnection() {
        val changes = DefaultNetworkChanges("wifi")
        assertFalse(changes.available("wifi"))
        assertFalse(changes.available("wifi"))
    }
    @Test fun offlineStartupAndReconnectToTheSameRouteBothTriggerRecovery() {
        val changes = DefaultNetworkChanges<String>(null)
        assertTrue(changes.available("wifi"))
        assertTrue(changes.lost("wifi"))
        assertTrue(changes.available("wifi"))
        assertFalse(changes.available("wifi"))
    }
    @Test fun lateOldRouteLossCannotResetTheNewRouteOrCauseDuplicateReconnects() {
        val changes = DefaultNetworkChanges("cellular")
        assertTrue(changes.available("wifi"))
        assertFalse(changes.lost("cellular"))
        assertFalse(changes.available("wifi"))
        assertTrue(changes.lost("wifi"))
        assertTrue(changes.available("cellular"))
    }
}
