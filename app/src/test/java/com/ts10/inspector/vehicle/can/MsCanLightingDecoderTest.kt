package com.ts10.inspector.vehicle.can

import org.junit.Assert.assertEquals
import org.junit.Test

class MsCanLightingDecoderTest {
    @Test
    fun `decodes day and night payloads`() {
        val decoder = MsCanLightingDecoder()
        assertEquals(false, decoder.accept(frame(0x00)))
        assertEquals(true, decoder.accept(frame(0x80)))
        assertEquals(true, decoder.accept(frame(0x99)))
    }

    private fun frame(state: Int) = Esp32CanFrame(
        sequence = 1,
        timestampUs = 1,
        identifier = 0x450,
        flags = 0,
        dlc = 4,
        data = byteArrayOf(0x46, 0x07, 0x06, state.toByte()),
        bus = Esp32CanBus.MS_CAN,
    )
}
