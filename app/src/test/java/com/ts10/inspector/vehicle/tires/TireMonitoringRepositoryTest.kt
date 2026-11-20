package com.ts10.inspector.vehicle.tires

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TireMonitoringRepositoryTest {
    @Test
    fun `normalizes four straight-line wheel speeds`() {
        val ratios = normalizedWheelRatios(listOf(80f, 80f, 80f, 80f))
        assertEquals(listOf(1f, 1f, 1f, 1f), ratios)
    }

    @Test
    fun `rejects low speed and turning outliers`() {
        assertNull(normalizedWheelRatios(listOf(20f, 20f, 20f, 20f)))
        assertNull(normalizedWheelRatios(listOf(70f, 90f, 70f, 90f)))
    }

    @Test
    fun `positive relative deviation identifies faster underinflated wheel`() {
        val deviations = ddsRelativeDeviations(
            currentRatios = listOf(1.03f, 0.99f, 0.99f, 0.99f),
            baselineRatios = listOf(1f, 1f, 1f, 1f),
        )
        assertEquals(3f, deviations[0], 0.001f)
        assertEquals(-1f, deviations[1], 0.001f)
    }
}
