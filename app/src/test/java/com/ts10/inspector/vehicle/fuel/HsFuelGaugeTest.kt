package com.ts10.inspector.vehicle.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HsFuelGaugeTest {
    @Test
    fun `keeps bit seven as sender data instead of a reserve flag`() {
        val filter = HsFuelGaugeFilter(levelWindowSize = 3, reserveWindowSize = 3, minimumSamples = 3)

        filter.accept(0x7F)
        filter.accept(0x80)
        val reading = requireNotNull(filter.accept(0x81))

        assertEquals(0x80, reading.senderLevel)
        assertFalse(reading.lowFuel)
    }

    @Test
    fun `initial curve estimates current capture between full and reserve`() {
        val curve = FuelCalibrationCurve.initial(42f)

        assertEquals(33.66f, curve.estimate(0x78)!!, 0.02f)
    }

    @Test
    fun `initial curve includes photographed half tank reference`() {
        val curve = FuelCalibrationCurve.initial(42f)

        assertEquals(21f, curve.estimate(0xA1)!!, 0.001f)
    }

    @Test
    fun `rejects legacy seven bit calibration saved with the wrong direction`() {
        val curve = FuelCalibrationCurve.decode("58:5;96:42", 42f)

        assertEquals(42f, curve.estimate(93)!!, 0.001f)
        assertEquals(5f, curve.estimate(187)!!, 0.001f)
    }

    @Test
    fun `waits for enough samples and detects reserve by majority`() {
        val filter = HsFuelGaugeFilter(levelWindowSize = 5, reserveWindowSize = 3, minimumSamples = 3)

        assertNull(filter.accept(0xBA))
        assertNull(filter.accept(0xBB))
        val reading = filter.accept(0x60)!!

        assertEquals(0xBA, reading.rawByte)
        assertEquals(0xBA, reading.senderLevel)
        assertTrue(reading.lowFuel)
    }

    @Test
    fun `clears reserve after a sustained full tank family`() {
        val filter = HsFuelGaugeFilter(levelWindowSize = 5, reserveWindowSize = 3, minimumSamples = 3)
        repeat(3) { filter.accept(0xBA) }
        repeat(3) { filter.accept(0x60) }

        assertFalse(filter.accept(0x61)!!.lowFuel)
    }

    @Test
    fun `maps empirical full and reserve references to configured capacity`() {
        assertEquals(42f, estimateHsFuelLiters(0x5D, 0xBB.toFloat(), 0x5D.toFloat(), 42f)!!, 0.001f)
        assertEquals(5f, estimateHsFuelLiters(0xBB, 0xBB.toFloat(), 0x5D.toFloat(), 42f)!!, 0.001f)
    }

    @Test
    fun `calibration curve learns and persists known refill points`() {
        val curve = FuelCalibrationCurve.initial(42f)
        curve.add(senderLevel = 140, liters = 20f, capacityLiters = 42f)

        assertEquals(20f, curve.estimate(140)!!, 0.001f)
        assertTrue(curve.estimate(120)!! > 20f)

        val restored = FuelCalibrationCurve.decode(curve.encode(), 42f)
        assertEquals(curve.points, restored.points)
    }

    @Test
    fun `distance estimate subtracts learned consumption from last reliable level`() {
        assertEquals(
            15.61f,
            estimateFuelFromDistance(21f, 100_286.0, 100_363.0, 7f)!!,
            0.01f,
        )
    }

    @Test
    fun `distance estimate rejects backwards or incomplete odometer data`() {
        assertNull(estimateFuelFromDistance(21f, 100_300.0, 100_299.0, 7f))
        assertNull(estimateFuelFromDistance(21f, null, 100_363.0, 7f))
    }
}
