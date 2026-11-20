package com.ts10.inspector.vehicle

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedWarningControllerTest {
    @Test
    fun reminderIsDisabledAndSixtySecondsByDefault() {
        val settings = SpeedWarningSettings()
        assertEquals(false, settings.repeatEnabled)
        assertEquals(60, settings.repeatIntervalSeconds)
    }

    @Test
    fun fixedMarginAppliesThroughOneHundred() {
        assertEquals(53, speedWarningThresholdKmh(50))
        assertEquals(103, speedWarningThresholdKmh(100))
    }

    @Test
    fun threePercentMarginAppliesAboveOneHundred() {
        assertEquals(104, speedWarningThresholdKmh(101))
        assertEquals(123, speedWarningThresholdKmh(120))
    }
}
