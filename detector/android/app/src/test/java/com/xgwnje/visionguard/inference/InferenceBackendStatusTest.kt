package com.xgwnje.visionguard.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceBackendStatusTest {
    @Test fun qnnRequestFallsBackExplicitlyWhenBuildDoesNotContainQnn() {
        val status = AndroidInferenceBackendPolicy.resolve(InferenceBackend.QNN)
        assertEquals(InferenceBackend.QNN, status.requested)
        assertEquals(InferenceBackend.CPU, status.active)
        assertTrue(status.fallbackReason.contains("QNN"))
    }

    @Test fun cpuRequestHasNoFallbackReason() {
        val status = AndroidInferenceBackendPolicy.resolve(InferenceBackend.CPU)
        assertEquals(InferenceBackend.CPU, status.active)
        assertEquals("", status.fallbackReason)
    }
}
