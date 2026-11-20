package com.ts10.inspector.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualGearEstimatorTest {
    @Test fun `detects ratios observed in vehicle logs`() {
        assertEquals(1, estimateManualGear(1_280, 10f, false, false))
        assertEquals(2, estimateManualGear(1_480, 20f, false, false))
        assertEquals(3, estimateManualGear(1_920, 40f, false, false))
        assertEquals(5, estimateManualGear(2_100, 70f, false, false))
        assertEquals(6, estimateManualGear(1_920, 80f, false, false))
    }

    @Test fun `does not invent a forward gear while clutch slips or ratio is implausible`() {
        assertNull(estimateManualGear(1_500, 20f, true, false))
        assertNull(estimateManualGear(1_500, 2f, false, false))
        assertNull(estimateManualGear(1_500, 25f, false, false))
    }

    @Test fun `reverse comes from CAN and takes precedence`() {
        assertEquals(-1, estimateManualGear(1_200, 10f, true, true))
    }

    @Test fun `economic recommendation excludes top gear`() {
        assertTrue(shouldRecommendEconomicUpshift(2_100, 3))
        assertFalse(shouldRecommendEconomicUpshift(2_099, 3))
        assertFalse(shouldRecommendEconomicUpshift(2_400, 6))
        assertFalse(shouldRecommendEconomicUpshift(2_400, null))
    }

    @Test fun `F1 rev lights use the petrol engine range`() {
        assertEquals(0f, f1RpmFill(null), 0f)
        assertEquals(0f, f1RpmFill(700), 0f)
        assertEquals((2_000f - 700f) / (7_000f - 700f), f1RpmFill(2_000), 0.0001f)
        assertEquals(1f, f1RpmFill(7_000), 0f)
        assertEquals(1f, f1RpmFill(8_000), 0f)
    }

    @Test fun `F1 rev lights enter red only at 6500 rpm`() {
        assertEquals(F1RpmBand.BLUE, f1RpmBand(2_000f))
        assertEquals(F1RpmBand.GREEN, f1RpmBand(5_000f))
        assertEquals(F1RpmBand.AMBER, f1RpmBand(6_499f))
        assertEquals(F1RpmBand.RED, f1RpmBand(6_500f))
    }
}
