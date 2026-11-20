package com.ts10.inspector.vehicle

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryMaintenanceTest {
    @Test
    fun `defaults warn three months before four year replacement`() {
        val installed = LocalDate.of(2022, 6, 15)
        val state = calculateBatteryMaintenance(
            installationDate = installed,
            lifetimeYears = 4,
            warningMonths = 3,
            today = LocalDate.of(2026, 3, 15),
        )

        assertEquals(LocalDate.of(2026, 6, 15), state.replacementDate)
        assertEquals(LocalDate.of(2026, 3, 15), state.warningDate)
        assertEquals(BatteryMaintenanceLevel.WARNING, state.level)
    }

    @Test
    fun `replacement date is overdue from that date onward`() {
        val state = calculateBatteryMaintenance(
            installationDate = LocalDate.of(2020, 1, 10),
            lifetimeYears = 4,
            warningMonths = 3,
            today = LocalDate.of(2024, 1, 10),
        )

        assertEquals(BatteryMaintenanceLevel.OVERDUE, state.level)
        assertEquals(0L, state.daysUntilReplacement)
    }
}
