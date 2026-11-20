package com.ts10.inspector.vehicle.can

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MsCanVehicleDecoderTest {
    @Test
    fun `decodes motion frame captured at idle`() {
        val decoder = MsCanVehicleDecoder()

        assertEquals(MsCanVehicleUpdate.MOTION, decoder.accept(frame(0x4E8, "460F0C7C000001")))
        assertEquals(799, decoder.signals.engineRpm)
        assertEquals(0f, decoder.signals.vehicleSpeedKmh)
        assertEquals(false, decoder.signals.reverse)
        assertEquals(0x01, decoder.signals.motionState)
    }

    @Test
    fun `decodes speed and reverse bit`() {
        val decoder = MsCanVehicleDecoder()

        decoder.accept(frame(0x4E8, "460F0FA01E0004"))

        assertEquals(1000, decoder.signals.engineRpm)
        assertEquals(60f, decoder.signals.vehicleSpeedKmh)
        assertEquals(true, decoder.signals.reverse)
    }

    @Test
    fun `reassembles climate message captured on 9 September`() {
        val decoder = MsCanVehicleDecoder()
        val frames = listOf(
            "101AC000170A2303",
            "210032003500B024",
            "2201E05925010020",
            "232602E050004120",
        )

        frames.dropLast(1).forEach { assertNull(decoder.accept(frame(0x6C8, it))) }
        assertEquals(MsCanVehicleUpdate.CLIMATE, decoder.accept(frame(0x6C8, frames.last())))
        assertEquals(
            MsCanClimateSignals("25\u00b0", automatic = true, cooling = true, fanLevel = null, airDirection = "AUTO"),
            decoder.signals.climate,
        )
    }

    @Test
    fun `drops climate assembly when sequence is broken`() {
        val decoder = MsCanVehicleDecoder()

        decoder.accept(frame(0x6C8, "101AC000170A2303"))
        assertNull(decoder.accept(frame(0x6C8, "2201E05925010020")))
        assertNull(decoder.signals.climate)
    }

    private fun frame(identifier: Long, hex: String): Esp32CanFrame {
        val data = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return Esp32CanFrame(
            sequence = 1,
            timestampUs = 1,
            identifier = identifier,
            flags = 0,
            dlc = data.size,
            data = data,
            bus = Esp32CanBus.MS_CAN,
        )
    }
}
