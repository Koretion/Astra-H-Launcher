package com.ts10.inspector.vehicle

import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleSpeedResolverTest {
    @Test
    fun directCanHasHighestPriority() {
        assertEquals(
            ResolvedVehicleSpeed(71, "GMLAN-USB HS-CAN"),
            resolveVehicleSpeed(71, true, 72, true, 70, true, 68),
        )
    }

    @Test
    fun qfIsUsedWhenDirectCanIsUnavailable() {
        assertEquals(
            ResolvedVehicleSpeed(72, "QF"),
            resolveVehicleSpeed(null, false, 72, true, 70, true, 68),
        )
    }

    @Test
    fun obdIsUsedWhenQfIsUnavailable() {
        assertEquals(
            ResolvedVehicleSpeed(70, "OBD2"),
            resolveVehicleSpeed(null, false, 72, false, 70, true, 68),
        )
    }

    @Test
    fun obdIsTheSafeSourceWhenQfReturnsZero() {
        assertEquals(
            ResolvedVehicleSpeed(54, "OBD2"),
            resolveVehicleSpeed(null, false, 0, true, 54, true, 52),
        )
    }

    @Test
    fun validObdZeroWinsOverGpsDrift() {
        assertEquals(
            ResolvedVehicleSpeed(0, "OBD2"),
            resolveVehicleSpeed(null, false, 0, true, 0, true, 4),
        )
    }

    @Test
    fun gpsIsUsedWhenQfReturnsZeroAndObdIsDisconnected() {
        assertEquals(
            ResolvedVehicleSpeed(52, "GPS"),
            resolveVehicleSpeed(null, false, 0, true, null, false, 52),
        )
    }

    @Test
    fun gpsIsTheFinalFallback() {
        assertEquals(
            ResolvedVehicleSpeed(68, "GPS"),
            resolveVehicleSpeed(null, false, 72, false, 70, false, 68),
        )
    }
}
