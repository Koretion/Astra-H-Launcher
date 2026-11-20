package com.ts10.inspector.vehicle.can

/** Lighting state validated against the Astra H automatic day/night transition. */
internal class MsCanLightingDecoder {
    var exteriorLightsOn: Boolean? = null
        private set

    fun reset() {
        exteriorLightsOn = null
    }

    fun accept(frame: Esp32CanFrame): Boolean? {
        if (frame.bus != Esp32CanBus.MS_CAN || frame.extended || frame.rtr ||
            frame.identifier != LIGHTING_ID || frame.data.size < 4 ||
            (frame.data[0].toInt() and 0xff) != 0x46 ||
            (frame.data[1].toInt() and 0xff) != 0x07 ||
            (frame.data[2].toInt() and 0xff) !in LIGHTING_VARIANTS
        ) return exteriorLightsOn
        exteriorLightsOn = (frame.data[3].toInt() and 0xff) in NIGHT_PAYLOADS
        return exteriorLightsOn
    }

    private companion object {
        const val LIGHTING_ID = 0x450L
        val LIGHTING_VARIANTS = setOf(0x04, 0x06)
        // 0x80 is the value reproduced by the 2026-09-19 automatic-light captures. Keep 0x99
        // for compatibility with the earlier firmware/capture on which the first decoder relied.
        val NIGHT_PAYLOADS = setOf(0x80, 0x99)
    }
}
