package com.ts10.inspector.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardThemeModeTest {
    @Test fun `android dark mode always wins`() =
        assertTrue(automaticDashboardDark(systemDark = true, hourOfDay = 12))

    @Test fun `night fallback covers delayed TS10 mode`() {
        assertTrue(automaticDashboardDark(systemDark = false, hourOfDay = 18))
        assertTrue(automaticDashboardDark(systemDark = false, hourOfDay = 6))
    }

    @Test fun `day stays light when android is light`() =
        assertFalse(automaticDashboardDark(systemDark = false, hourOfDay = 12))
}
