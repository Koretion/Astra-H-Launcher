package com.ts10.inspector.vehicle.can

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HsCanVehicleDecoderTest {
    private val decoder = HsCanVehicleDecoder()

    @Test
    fun `decodes signed steering angle at one sixteenth degree`() {
        decoder.accept(frame(0x180, 0x00, 0x00, 0x05, 0x40))
        assertEquals(84f, decoder.signals.steeringAngleDegrees!!, 0.001f)

        decoder.accept(frame(0x180, 0x00, 0x00, 0xFA, 0xA8))
        assertEquals(-85.5f, decoder.signals.steeringAngleDegrees!!, 0.001f)
    }

    @Test
    fun `ignores the not initialised steering frame sent after ignition`() {
        decoder.accept(frame(0x180, 0xA4, 0x62, 0x00, 0x48))
        assertEquals(4.5f, decoder.signals.steeringAngleDegrees!!, 0.001f)
        // Real captures: 64 04 7F FF E0 00 until the wheel is first turned.
        decoder.accept(frame(0x180, 0x64, 0x04, 0x7F, 0xFF))
        assertEquals(4.5f, decoder.signals.steeringAngleDegrees!!, 0.001f)
        decoder.accept(frame(0x180, 0x64, 0x04, 0x00, 0x00))
        assertEquals(4.5f, decoder.signals.steeringAngleDegrees!!, 0.001f)
    }

    @Test
    fun `decodes isolated left and right indicator states`() {
        decoder.accept(frame(0x381, 0x62, 0, 0, 0, 0x12))
        assertTrue(decoder.signals.leftIndicator)
        assertFalse(decoder.signals.rightIndicator)

        decoder.accept(frame(0x381, 0x82, 0, 0, 0, 0x12))
        assertFalse(decoder.signals.leftIndicator)
        assertTrue(decoder.signals.rightIndicator)
    }

    @Test
    fun `decodes exterior light candidate and high beam`() {
        decoder.accept(frame(0x381, 0x0A, 0, 0, 0, 0x92))
        assertTrue(decoder.signals.exteriorLightsCandidate)
        assertTrue(decoder.signals.highBeam)
    }

    @Test
    fun `exposes provisional fuel sender byte`() {
        decoder.accept(frame(0x381, 0x02, 0x18, 0x07, 0xFF, 0x22, 0xBA, 0, 4))

        assertEquals(0xBA, decoder.signals.fuelRawByte)
    }

    @Test
    fun `decodes isolated handbrake bit`() {
        decoder.accept(frame(0x380, 0xE0, 0xB6, 0x7A, 0, 0, 0xFB, 0, 0x80))
        assertEquals(true, decoder.signals.handbrake)

        decoder.accept(frame(0x380, 0x30, 0xB6, 0x7A, 0, 0, 0xFB, 0, 0x00))
        assertEquals(false, decoder.signals.handbrake)
    }

    @Test
    fun `decodes four wheel speeds`() {
        decoder.accept(frame(0x280, 0x04, 0x00, 0x04, 0x10, 0x03, 0xF0, 0x04, 0x20))
        val speeds = decoder.signals.wheelSpeeds!!
        assertEquals(30f, speeds.frontLeftKmh, 0.001f)
        assertEquals(30.46875f, speeds.frontRightKmh, 0.001f)
        assertEquals(29.53125f, speeds.rearLeftKmh, 0.001f)
        assertEquals(30.9375f, speeds.rearRightKmh, 0.001f)
    }

    @Test
    fun `decodes vehicle speed at one hundred twenty eighth kmh`() {
        decoder.accept(frame(0x410, 0x02, 0x0F, 0x80, 0, 0, 0, 0))

        assertEquals(31f, decoder.signals.vehicleSpeedKmh!!, 0.001f)
    }

    @Test
    fun `decodes rpm and coolant`() {
        decoder.accept(frame(0x110, 0x02, 0x0C, 0x80))
        decoder.accept(frame(0x510, 0x80, 0x7C))
        assertEquals(800, decoder.signals.engineRpm)
        assertEquals(84, decoder.signals.coolantCelsius)
    }

    @Test
    fun `decodes photographed total odometer at one sixty fourth kilometre`() {
        decoder.accept(frame(0x4A0, 0x00, 0x00, 0x61, 0xBE, 0x80, 0x00))

        assertEquals(100_090.0, decoder.signals.odometerKm!!, 0.0001)
    }

    private fun frame(id: Long, vararg bytes: Int) = Esp32CanFrame(
        sequence = 1,
        timestampUs = 1,
        identifier = id,
        flags = 0,
        dlc = bytes.size,
        data = bytes.map(Int::toByte).toByteArray(),
        bus = Esp32CanBus.HS_CAN,
    )
}
