package com.ts10.inspector.vehicle.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelTrackingRepositoryTest {
    @Test
    fun distanceIsZeroForSamePoint() {
        assertEquals(0.0, distanceMeters(40.4, -3.7, 40.4, -3.7), 0.01)
    }

    @Test
    fun distanceIsPlausibleForOneDegreeOfLatitude() {
        assertTrue(distanceMeters(40.0, -3.0, 41.0, -3.0) in 110_000.0..112_000.0)
    }
}
