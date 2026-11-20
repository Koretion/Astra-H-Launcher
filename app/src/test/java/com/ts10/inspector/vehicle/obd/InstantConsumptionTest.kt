package com.ts10.inspector.vehicle.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstantConsumptionTest {
    @Test
    fun `uses unified vehicle speed with measured or estimated fuel flow`() {
        assertEquals(6.0f, calculateConsumptionLitersPer100Km(4.8f, 80)!!, 0.001f)
        assertEquals(
            5.0f,
            calculateConsumptionLitersPer100Km(
                effectiveFuelRateLitersPerHour(Obd2State(estimatedFuelRateLitersPerHour = 3f)),
                60,
            )!!,
            0.001f,
        )
    }

    @Test
    fun `reports fuel flow instead of an unstable distance value near standstill`() {
        assertNull(calculateConsumptionLitersPer100Km(0.8f, 0))
        assertNull(calculateConsumptionLitersPer100Km(0.8f, 2))
    }
}
