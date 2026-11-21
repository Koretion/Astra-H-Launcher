package com.ts10.inspector.vehicle.can

import com.ts10.inspector.vehicle.can.profile.CanSignals
import com.ts10.inspector.vehicle.can.profile.VehicleCanProfile
import com.ts10.inspector.vehicle.can.profile.VehicleCanProfiles

/** Lighting state validated against the Astra H automatic day/night transition (see the profile). */
internal class MsCanLightingDecoder(profile: VehicleCanProfile = VehicleCanProfiles.active) {
    /** Al cambiar de perfil se olvida lo decodificado con el anterior. */
    var profile: VehicleCanProfile = profile
        set(value) {
            field = value
            reset()
        }

    var exteriorLightsOn: Boolean? = null
        private set

    fun reset() {
        exteriorLightsOn = null
    }

    fun accept(frame: Esp32CanFrame): Boolean? {
        if (frame.bus != Esp32CanBus.MS_CAN) return exteriorLightsOn
        exteriorLightsOn = profile.decode(frame).flag(CanSignals.EXTERIOR_LIGHTS_ON, exteriorLightsOn)
        return exteriorLightsOn
    }
}
