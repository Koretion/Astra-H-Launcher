package com.ts10.inspector.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualOdometerTest {
    @Test
    fun acceptsCommaAndPointAndFormatsForSpanishInput() {
        assertEquals(183_456.7, parseManualOdometerKm("183456,7")!!, 0.0)
        assertEquals(183_456.7, parseManualOdometerKm("183456.7")!!, 0.0)
        assertEquals("183456,7", formatManualOdometerKm(183_456.7))
    }

    @Test
    fun rejectsAmbiguousOrImplausibleValues() {
        assertNull(parseManualOdometerKm("183.456,7"))
        assertNull(parseManualOdometerKm("10000000"))
        assertNull(parseManualOdometerKm("12,3456"))
    }
}
