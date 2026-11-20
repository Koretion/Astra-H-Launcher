package com.ts10.inspector.vehicle3d

import org.junit.Assert.assertEquals
import org.junit.Test

class Vehicle3dPerformanceLogTest {
    @Test
    fun `process cpu reports one core and normalized device load`() {
        val cpu = calculateVehicle3dCpuPercent(
            elapsedMilliseconds = 1_000L,
            processCpuMilliseconds = 1_000L,
            processors = 8,
        )

        assertEquals(100f, cpu!!.first, 0.001f)
        assertEquals(12.5f, cpu.second, 0.001f)
    }

    @Test
    fun `process cpu rejects samples without an elapsed interval`() {
        assertEquals(null, calculateVehicle3dCpuPercent(0L, 10L, 8))
        assertEquals(null, calculateVehicle3dCpuPercent(10L, -1L, 8))
        assertEquals(null, calculateVehicle3dCpuPercent(10L, 1L, 0))
    }

}
