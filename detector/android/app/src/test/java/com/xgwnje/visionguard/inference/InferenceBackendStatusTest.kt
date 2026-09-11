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

    @Test fun nnapiProviderEvidenceDoesNotClaimUnknownHardware() {
        val status = InferenceBackendStatus(
            requested = InferenceBackend.NNAPI,
            active = InferenceBackend.NNAPI,
            actualProvider = "NnapiExecutionProvider"
        )
        assertTrue(status.providerExecutionConfirmed)
        assertEquals(false, status.hardwareExecutionConfirmed)
    }

    @Test fun nnapiReferenceDeviceIsNotHardwareAcceleration() {
        val status = InferenceBackendStatus(
            requested = InferenceBackend.NNAPI,
            active = InferenceBackend.NNAPI,
            actualProvider = "NnapiExecutionProvider",
            executionDevice = "nnapi-reference"
        )
        assertTrue(status.providerExecutionConfirmed)
        assertEquals(false, status.hardwareExecutionConfirmed)
    }

    @Test fun nonCpuNnapiDeviceCanConfirmHardwareExecution() {
        val status = InferenceBackendStatus(
            requested = InferenceBackend.NNAPI,
            active = InferenceBackend.NNAPI,
            actualProvider = "NnapiExecutionProvider",
            executionDevice = "qti-default"
        )
        assertTrue(status.hardwareExecutionConfirmed)
    }
}
