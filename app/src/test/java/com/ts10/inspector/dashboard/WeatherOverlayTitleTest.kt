package com.ts10.inspector.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherOverlayTitleTest {
    private fun title(location: String?) = weatherOverlayTitle(location, "El tiempo") { "El tiempo en $it" }

    @Test
    fun `title includes resolved locality`() {
        assertEquals("El tiempo en Madrid", title("Madrid"))
    }

    @Test
    fun `title falls back when locality is unavailable`() {
        assertEquals("El tiempo", title(null))
        assertEquals("El tiempo", title("  "))
    }
}
