package com.ts10.inspector.vehicle.can

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwCanVehicleDecoderTest {
    private val decoder = SwCanVehicleDecoder()

    @Test
    fun `decodes confirmed door and trunk masks independently`() {
        accept(0x230, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00)
        assertTrue(decoder.signals.driverDoorOpen!!)
        assertFalse(decoder.signals.passengerDoorOpen!!)
        assertFalse(decoder.signals.trunkOpen!!)

        accept(0x230, 0x00, 0x00, 0x54, 0x00, 0x00, 0x00, 0x00)
        assertTrue(decoder.signals.driverDoorOpen!!)
        assertTrue(decoder.signals.passengerDoorOpen!!)
        assertTrue(decoder.signals.trunkOpen!!)
    }

    @Test
    fun `decodes sustained central lock state without confusing it with a window command`() {
        accept(0x230, 0x05, 0x00, 0x00, 0x00, 0x00, 0x09, 0x00)
        assertEquals(true, decoder.signals.centralLocked)
        assertEquals(null, decoder.signals.windowAction)

        accept(0x230, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertEquals(false, decoder.signals.centralLocked)
    }

    @Test
    fun `decodes confirmed central lock and unlock pulses`() {
        accept(0x135, 0xBC, 0x00, 0xEA, 0xA1)
        assertEquals(SwCentralLockAction.LOCKING, decoder.signals.centralLockAction)

        accept(0x135, 0x3C, 0x00, 0xEA, 0xA1)
        assertEquals(SwCentralLockAction.LOCKING, decoder.signals.centralLockAction)

        accept(0x135, 0x7C, 0x00, 0xEA, 0xA1)
        assertEquals(SwCentralLockAction.UNLOCKING, decoder.signals.centralLockAction)
    }

    @Test
    fun `decodes dedicated sustained hazard state`() {
        accept(0x260, 0x1F, 0x43, 0x7F)
        assertTrue(decoder.signals.hazard)
        assertTrue(decoder.signals.leftIndicator)
        assertTrue(decoder.signals.rightIndicator)

        accept(0x260, 0x00, 0x00, 0x00)
        assertFalse(decoder.signals.hazard)
    }

    @Test
    fun `decodes right indicator hyperflash as a lamp fault and clears it after repair`() {
        accept(0x260, 0x3A, 0x21, 0x7C)
        assertTrue(decoder.signals.rightIndicator)
        assertEquals(SwLampFault.RIGHT_INDICATOR, decoder.signals.lampFault)

        accept(0x260, 0x00, 0x00, 0x00)
        assertEquals(SwLampFault.RIGHT_INDICATOR, decoder.signals.lampFault)

        accept(0x260, 0x3A, 0x43, 0x7F)
        assertTrue(decoder.signals.rightIndicator)
        assertEquals(null, decoder.signals.lampFault)
    }

    @Test
    fun `decodes wipers window events and labelled central controls`() {
        accept(0x175, 0x00, 0xD0, 0x00, 0x0C, 0x00, 0x00, 0x00, 0x00)
        assertEquals(SwWiperMode.SPEED_3, decoder.signals.wiperMode)
        accept(0x160, 0x01, 0x10, 0x9D, 0x14)
        assertEquals(SwWindowAction.OPENING, decoder.signals.windowAction)
        accept(0x305, 0x00, 0x00, 0x00, 0x00, 0x3A, 0x51, 0x00)
        assertEquals(SwCentralControlAction.SPORT, decoder.signals.centralControlAction)
        accept(0x305, 0x00, 0x00, 0x00, 0x08, 0x3B, 0x51, 0x00)
        assertEquals(SwCentralControlAction.ESP, decoder.signals.centralControlAction)
    }

    @Test
    fun `decodes odometer fuel temperature and voltage`() {
        accept(0x190, 0x00, 0x00, 0x78, 0x90, 0x00, 0x03, 0xFF)
        accept(0x375, 0x00, 0x78)
        accept(0x445, 0x00, 0x7A)
        accept(0x500, 0x00, 0x6E)

        assertEquals(123456.0, decoder.signals.odometerKm!!, 0.001)
        assertEquals(0x78, decoder.signals.fuelRawByte)
        assertEquals(21f, decoder.signals.outdoorTemperatureCelsius!!, 0.001f)
        assertEquals(13.75f, decoder.signals.batteryVoltage!!, 0.001f)
    }

    @Test
    fun `decodes the stable ignition key index`() {
        accept(0x425, 0x01)
        assertEquals(1, decoder.signals.ignitionKeyIndex)
        accept(0x425, 0x02)
        assertEquals(2, decoder.signals.ignitionKeyIndex)
        accept(0x425, 0x7f)
        assertEquals(null, decoder.signals.ignitionKeyIndex)
    }

    @Test
    fun `decodes service brake and clutch independently`() {
        accept(0x360, 0x00, 0x00, 0x40)
        assertEquals(true, decoder.signals.serviceBrakePressed)
        accept(0x360, 0x00, 0x00, 0x00)
        assertEquals(false, decoder.signals.serviceBrakePressed)

        accept(0x115, 0x0c)
        assertEquals(false, decoder.signals.clutchPressed)
        assertEquals(0x0c, decoder.signals.pedalSwitchRaw)
        accept(0x115, 0x0e)
        assertEquals(true, decoder.signals.clutchPressed)
        assertEquals(0x0e, decoder.signals.pedalSwitchRaw)
    }

    @Test
    fun `decodes front and rear fog lamps from the 2026-11-15 capture`() {
        accept(0x350, 0x06, 0x01, 0xDC, 0x01)
        assertEquals(false, decoder.signals.frontFogOn)
        accept(0x350, 0x26, 0x01, 0xDC, 0x01)
        assertEquals(true, decoder.signals.frontFogOn)

        accept(0x370, 0x00, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertEquals(true, decoder.signals.rearFogOn)
        assertEquals(false, decoder.signals.handbrake)
        accept(0x370, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertEquals(false, decoder.signals.rearFogOn)
        assertEquals(true, decoder.signals.handbrake)
    }

    private fun accept(identifier: Long, vararg data: Int) {
        decoder.accept(
            Esp32CanFrame(
                sequence = 0,
                timestampUs = 0,
                identifier = identifier,
                flags = 0,
                dlc = data.size,
                data = ByteArray(data.size) { data[it].toByte() },
                bus = Esp32CanBus.SW_CAN,
            ),
        )
    }
}
