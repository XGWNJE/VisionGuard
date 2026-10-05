package com.xgwnje.visionguard.notifier.node

import org.junit.Assert.*
import org.junit.Test

class ConnectionRecoveryTest {
    @Test fun silenceRequiresProbeThenANewFailedAttempt() {
        val policy = ConnectionRecovery(); policy.attempt(0); policy.responded(0)
        for (now in 0L..42_000L step 3000) assertEquals(ConnectionRecovery.Action.NONE, policy.tick(now, true, true))
        assertEquals(ConnectionRecovery.Action.PROBE, policy.tick(45_000, true, true))
        for (now in 48_000L..54_000L step 3000) assertEquals(ConnectionRecovery.Action.NONE, policy.tick(now, true, true))
        assertEquals(ConnectionRecovery.Action.RECONNECT, policy.tick(57_000, true, true))
        policy.attempt(58_000)
        for (now in 60_000L..69_000L step 3000) assertEquals(ConnectionRecovery.Action.NONE, policy.tick(now, true, false))
        assertEquals(ConnectionRecovery.Action.CONFIRM_FAILURE, policy.tick(72_000, true, false))
    }
    @Test fun sleepAndDelayedHandlerNeverConfirmFailureAtResume() {
        val policy = ConnectionRecovery(); policy.attempt(0); policy.responded(0); policy.tick(0, true, true)
        assertEquals(ConnectionRecovery.Action.PROBE, policy.tick(600_000, true, true))
        assertEquals(ConnectionRecovery.Action.PROBE, policy.tick(1_200_000, true, true))
        policy.responded(1_200_001)
        assertEquals(ConnectionRecovery.Action.NONE, policy.tick(1_203_000, true, true))
        policy.failed(1_204_000); policy.attempt(1_204_000)
        assertEquals(ConnectionRecovery.Action.NONE, policy.tick(2_000_000, true, false))
        assertEquals(ConnectionRecovery.Action.NONE, policy.tick(2_003_000, true, false))
    }
    @Test fun oldSilenceCannotCancelNewConnectionAndRecoveryResetsVerification() {
        val policy = ConnectionRecovery(); policy.attempt(0); policy.responded(0)
        assertEquals(ConnectionRecovery.Action.RECONNECT, policy.failed(90_000))
        policy.attempt(90_000)
        assertEquals(ConnectionRecovery.Action.NONE, policy.tick(90_001, true, false))
        policy.responded(90_002)
        assertEquals(ConnectionRecovery.Action.RECONNECT, policy.failed(90_003))
        policy.attempt(90_003)
        assertEquals(ConnectionRecovery.Action.CONFIRM_FAILURE, policy.failed(90_004))
        assertEquals(ConnectionRecovery.Action.RECONNECT, policy.failed(90_005))
    }
    @Test fun aFailureCallbackQueuedDuringSleepRequiresANewVerificationAttempt() {
        val policy = ConnectionRecovery(); policy.attempt(0); policy.responded(0); policy.tick(0, true, true)
        policy.failed(1); policy.attempt(2)
        assertEquals(ConnectionRecovery.Action.RECONNECT, policy.failed(600_000))
        policy.attempt(600_001)
        assertEquals(ConnectionRecovery.Action.CONFIRM_FAILURE, policy.failed(600_002))
    }
}
