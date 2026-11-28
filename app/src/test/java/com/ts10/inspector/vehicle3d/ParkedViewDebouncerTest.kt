package com.ts10.inspector.vehicle3d

import org.junit.Assert.assertEquals
import org.junit.Test

class ParkedViewDebouncerTest {
    private fun driving(debouncer: ParkedViewDebouncer) =
        debouncer.update(ParkedViewSignals(speedKmh = 40f), 0L)

    @Test
    fun startsParkedBeforeDriving() {
        assertEquals(true to null, ParkedViewDebouncer().update(ParkedViewSignals(0f), 0L))
    }

    @Test
    fun shortStopKeepsDrivingView() {
        val debouncer = ParkedViewDebouncer()
        driving(debouncer)
        assertEquals(false to 11_000L, debouncer.update(ParkedViewSignals(0f), 1_000L))
        assertEquals(false to 11_000L, debouncer.update(ParkedViewSignals(0f), 10_999L))
        assertEquals(false to null, debouncer.update(ParkedViewSignals(20f), 10_999L))
    }

    @Test
    fun longStopParksAfterTenSeconds() {
        val debouncer = ParkedViewDebouncer()
        driving(debouncer)
        debouncer.update(ParkedViewSignals(0f), 1_000L)
        assertEquals(true to null, debouncer.update(ParkedViewSignals(0f), 11_000L))
    }

    @Test
    fun redLightWaitsThirtySeconds() {
        val debouncer = ParkedViewDebouncer()
        driving(debouncer)
        assertEquals(false to 30_000L, debouncer.update(ParkedViewSignals(0f, redLightAhead = true), 0L))
        // El semáforo deja de verse pero la espera larga se mantiene durante esta detención.
        assertEquals(false to 30_000L, debouncer.update(ParkedViewSignals(0f), 15_000L))
        assertEquals(true to null, debouncer.update(ParkedViewSignals(0f), 30_000L))
    }

    @Test
    fun handbrakeOrDoorParksImmediately() {
        val handbrake = ParkedViewDebouncer().also(::driving)
        assertEquals(true to null, handbrake.update(ParkedViewSignals(0f, handbrake = true), 500L))
        val door = ParkedViewDebouncer().also(::driving)
        door.update(ParkedViewSignals(0f), 0L)
        assertEquals(true to null, door.update(ParkedViewSignals(0f, anyOpening = true), 2_000L))
    }
}
