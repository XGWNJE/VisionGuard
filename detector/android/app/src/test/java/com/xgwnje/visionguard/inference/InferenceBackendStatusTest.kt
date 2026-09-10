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

    @Test fun nnapiRequestUsesNnapiWhenRuntimeIsAvailable() {
        val status = AndroidInferenceBackendPolicy.resolve(
            InferenceBackend.NNAPI,
            nnapiAvailable = true
        )
        assertEquals(InferenceBackend.NNAPI, status.requested)
        assertEquals(InferenceBackend.NNAPI, status.active)
        assertEquals("", status.fallbackReason)
    }

    @Test fun nnapiRequestFallsBackOnOldAndroid() {
        val status = AndroidInferenceBackendPolicy.resolve(
            InferenceBackend.NNAPI,
            nnapiAvailable = false
        )
        assertEquals(InferenceBackend.CPU, status.active)
        assertTrue(status.fallbackReason.contains("API 29"))
    }

    @Test fun cpuRequestHasNoFallbackReason() {
        val status = AndroidInferenceBackendPolicy.resolve(InferenceBackend.CPU)
        assertEquals(InferenceBackend.CPU, status.active)
        assertEquals("", status.fallbackReason)
    }
}
