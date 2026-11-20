package com.ts10.inspector.dashboard

import com.ts10.inspector.settings.showIndividualTurnIndicators
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehiclePanelSpeedTest {
    @Test
    fun `speed turns red only above a detected road limit`() {
        assertFalse(isAboveDetectedSpeedLimit(51, null))
        assertFalse(isAboveDetectedSpeedLimit(50, 50))
        assertTrue(isAboveDetectedSpeedLimit(51, 50))
    }

    @Test
    fun `body panel replaces individual indicators with dedicated hazard state`() {
        assertTrue(showIndividualTurnIndicators(hazardActive = false))
        assertFalse(showIndividualTurnIndicators(hazardActive = true))
    }
}
