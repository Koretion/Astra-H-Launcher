package com.ts10.inspector.vehicle.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SustainedRefuelDetectorTest {
    private fun stoppedDetector(): SustainedRefuelDetector = SustainedRefuelDetector().also {
        for (time in 0L..30_000L step 1_000L) assertNull(it.accept(null, 148, false, time))
    }

    @Test fun `rejects short sender slosh`() {
        val detector = stoppedDetector()
        assertNull(detector.accept(148, 131, false, 31_000))
        for (time in 32_000L..45_000L step 1_000L) assertNull(detector.accept(null, 131, false, time))
        assertNull(detector.accept(null, 148, false, 46_000))
        for (time in 47_000L..160_000L step 1_000L) assertNull(detector.accept(null, 131, false, time))
    }

    @Test fun `confirms a sustained increase while stopped`() {
        val detector = stoppedDetector()
        assertNull(detector.accept(148, 131, false, 31_000))
        for (time in 32_000L..150_000L step 1_000L) assertNull(detector.accept(null, 132, false, time))
        assertEquals(148, detector.accept(null, 132, false, 151_000))
    }

    @Test fun `movement cancels a candidate`() {
        val detector = stoppedDetector()
        assertNull(detector.accept(148, 131, false, 31_000))
        assertNull(detector.accept(null, 131, true, 32_000))
        for (time in 33_000L..160_000L step 1_000L) assertNull(detector.accept(null, 131, false, time))
    }

    @Test fun `a capture gap is not evidence of continuous refueling`() {
        val detector = stoppedDetector()
        assertNull(detector.accept(148, 131, false, 31_000))
        assertNull(detector.accept(null, 131, false, 151_000))
    }

    @Test fun `sender recovering just after braking cannot start a candidate`() {
        val detector = SustainedRefuelDetector()
        assertNull(detector.accept(148, 131, false, 0))
        for (time in 1_000L..160_000L step 1_000L) assertNull(detector.accept(null, 131, false, time))
    }
}
