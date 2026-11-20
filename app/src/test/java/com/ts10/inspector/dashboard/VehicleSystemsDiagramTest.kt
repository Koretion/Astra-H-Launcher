package com.ts10.inspector.dashboard

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleSystemsDiagramTest {
    @Test
    fun `engine color distinguishes cold normal hot and critical`() {
        assertEquals(Color(0xFF279BE8), engineVisualColor(null))
        assertEquals(Color(0xFF279BE8), engineVisualColor(69))
        assertEquals(Color(0xFF31C967), engineVisualColor(90))
        assertEquals(Color(0xFFF0A126), engineVisualColor(108))
        assertEquals(Color(0xFFE33B43), engineVisualColor(110))
    }

    @Test
    fun `fuel color distinguishes unknown low reserve and normal`() {
        assertEquals(Color(0xFF279BE8), fuelVisualColor(null))
        assertEquals(Color(0xFFE33B43), fuelVisualColor(10))
        assertEquals(Color(0xFFF0A126), fuelVisualColor(25))
        assertEquals(Color(0xFF31C967), fuelVisualColor(50))
    }
}
