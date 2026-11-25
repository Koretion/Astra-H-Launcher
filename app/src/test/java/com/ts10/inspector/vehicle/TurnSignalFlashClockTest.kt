package com.ts10.inspector.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnSignalFlashClockTest {
    @Test
    fun `lamp follows each real flash frame with the cluster on and off times`() {
        val clock = TurnSignalFlashClock()
        clock.onFlashFrame(espTimestampUs = 10_000_000, arrivalElapsedMs = 50_000)
        assertTrue(clock.lampOn(true, 50_000))
        assertTrue(clock.lampOn(true, 50_379))
        assertFalse(clock.lampOn(true, 50_380))
        assertFalse(clock.lampOn(true, 50_679))
        assertEquals(1L, clock.msUntilChange(50_679))
        assertFalse(clock.lampOn(false, 50_100))
    }

    @Test
    fun `late USB delivery does not delay the edge once the fastest delivery is known`() {
        val clock = TurnSignalFlashClock()
        clock.onFlashFrame(10_000_000, 50_000)
        // Next flash 681 ms later on the ESP32 clock, delivered 60 ms late by USB.
        clock.onFlashFrame(10_681_000, 50_741)
        // The edge lands at 50 681 (+1 ms of drift allowance), not at the 50 741 arrival.
        assertTrue(clock.lampOn(true, 50_685))
        assertFalse(clock.lampOn(true, 50_675))
    }

    @Test
    fun `without frames the lamp free-runs from the moment the indicator was switched on`() {
        val clock = TurnSignalFlashClock()
        assertTrue(clock.lampOn(true, 1_000))
        assertFalse(clock.lampOn(true, 1_400))
        assertTrue(clock.lampOn(true, 1_680))
    }

    @Test
    fun `an ESP32 restart resynchronises the clock offset`() {
        val clock = TurnSignalFlashClock()
        clock.onFlashFrame(900_000_000, 950_000)
        clock.onFlashFrame(2_000_000, 960_000)
        assertTrue(clock.lampOn(true, 960_000))
        assertFalse(clock.lampOn(true, 960_400))
    }
}
