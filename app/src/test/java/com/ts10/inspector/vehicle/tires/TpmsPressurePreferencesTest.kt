package com.ts10.inspector.vehicle.tires

import org.junit.Assert.assertEquals
import org.junit.Test

class TpmsPressurePreferencesTest {
    @Test
    fun `does not classify pressure until manufacturer values are confirmed`() {
        assertEquals(
            TpmsPressureLevel.NOT_CONFIGURED,
            tpmsPressureLevel(1.7f, 2.3f, 0.3f, 0.4f, configured = false),
        )
    }

    @Test
    fun `classifies pressure against independent low and high tolerances`() {
        assertEquals(TpmsPressureLevel.LOW, tpmsPressureLevel(1.9f, 2.3f, 0.3f, 0.4f, true))
        assertEquals(TpmsPressureLevel.NORMAL, tpmsPressureLevel(2.0f, 2.3f, 0.3f, 0.4f, true))
        assertEquals(TpmsPressureLevel.NORMAL, tpmsPressureLevel(2.7f, 2.3f, 0.3f, 0.4f, true))
        assertEquals(TpmsPressureLevel.HIGH, tpmsPressureLevel(2.8f, 2.3f, 0.3f, 0.4f, true))
    }
}
