package com.xgwnje.visionguard.notifier

import org.junit.Assert.*
import org.junit.Test

class DetectedObjectTest {
    @Test fun mixedObjectsChooseScoreRatherThanFirstOrSupportedClass() {
        assertEquals("car", highestConfidenceObject(listOf(DetectedObject("person", .72), DetectedObject("car", .94)))?.label)
        assertEquals("bicycle", highestConfidenceObject(listOf(DetectedObject("person", .72), DetectedObject("bicycle", .99)))?.label)
    }
    @Test fun invalidCandidatesDoNotReplaceRealObjects() {
        val valid = DetectedObject("person", .82)
        assertEquals(valid, highestConfidenceObject(listOf(DetectedObject("car", Double.NaN), DetectedObject("car", 1.01),
            DetectedObject("car", -.01), DetectedObject("", 1.0), DetectedObject("x".repeat(65), 1.0), valid)))
        assertNull(highestConfidenceObject(emptyList()))
    }
    @Test fun tiesKeepOriginalOrderAndBoundaryScoresAreLegal() {
        assertEquals("person", highestConfidenceObject(listOf(DetectedObject("person", 1.0), DetectedObject("car", 1.0)))?.label)
        assertEquals(DetectedObject("car", 0.0), highestConfidenceObject(listOf(DetectedObject("car", 0.0))))
        val maxLength = DetectedObject("x".repeat(64), 1.0)
        assertEquals(maxLength, highestConfidenceObject(listOf(maxLength)))
    }
    @Test fun artworkNamesDoNotGuessObjectsFromEventSummary() {
        assertEquals("人", DetectedObject("person", .9).displayName)
        assertEquals("汽车", DetectedObject("car", .9).displayName)
        assertEquals("bicycle", DetectedObject("bicycle", .9).displayName)
    }
}
