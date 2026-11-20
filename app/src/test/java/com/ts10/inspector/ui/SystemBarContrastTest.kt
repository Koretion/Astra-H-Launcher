package com.ts10.inspector.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemBarContrastTest {
    @Test
    fun `light dashboard uses a light system row with dark icons`() {
        assertTrue(useDarkStatusBarIcons(darkDashboard = false))
    }

    @Test
    fun `dark dashboard uses a dark system row with light icons`() {
        assertFalse(useDarkStatusBarIcons(darkDashboard = true))
    }

    @Test
    fun `status bar follows the dashboard theme`() {
        assertEquals(0xFFF4F2EF.toInt(), statusBarColor(darkDashboard = false))
        assertEquals(0xFF050607.toInt(), statusBarColor(darkDashboard = true))
        assertNotEquals(statusBarColor(false), statusBarColor(true))
    }
}
