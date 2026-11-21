package com.ts10.inspector.vehicle.can.profile

import com.ts10.inspector.vehicle.can.Esp32CanBus
import com.ts10.inspector.vehicle.can.Esp32CanFrame
import com.ts10.inspector.vehicle.can.HsCanVehicleDecoder
import com.ts10.inspector.vehicle.can.SwCanVehicleDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleCanProfileTest {
    @Test fun `bundled astra h profile only uses signals the launcher understands`() {
        val profile = VehicleCanProfiles.loadResource(VehicleCanProfiles.DEFAULT_RESOURCE)
        assertEquals("opel_astra_h_gtc", profile.id)
        val unknown = profile.signalNames - CanSignals.all
        assertTrue("Unknown signals: $unknown", unknown.isEmpty())
        assertTrue(profile.isCodeDecoderFrame(frame(Esp32CanBus.MS_CAN, 0x6C8, 0x10), CanSignals.CLIMATE_ISOTP_DECODER))
    }

    @Test fun `another car can move a signal to a different frame without code changes`() {
        val profile = VehicleCanProfiles.parse(
            """
            {
              "schema": 1, "id": "test_car",
              "frames": [
                { "bus": "SW", "id": "0x231", "signals": [
                  { "name": "driver_door_open", "type": "flag", "byte": 0, "mask": "0x01" },
                  { "name": "trunk_open", "type": "flag", "byte": 0, "mask": "0x02" }
                ] },
                { "bus": "HS", "id": "0x3E9", "signals": [
                  { "name": "vehicle_speed_kmh", "type": "unsigned", "byte": 0, "length": 2, "scale": 0.01 }
                ] }
              ]
            }
            """.trimIndent(),
        )
        val sw = SwCanVehicleDecoder(profile)
        sw.accept(frame(Esp32CanBus.SW_CAN, 0x230, 0x00, 0x00, 0x40))
        assertNull(sw.signals.driverDoorOpen)

        sw.accept(frame(Esp32CanBus.SW_CAN, 0x231, 0x03))
        assertEquals(true, sw.signals.driverDoorOpen)
        assertEquals(true, sw.signals.trunkOpen)
        // A signal the profile does not define stays unknown instead of guessing.
        assertNull(sw.signals.passengerDoorOpen)

        val hs = HsCanVehicleDecoder(profile)
        hs.accept(frame(Esp32CanBus.HS_CAN, 0x3E9, 0x0C, 0x1C))
        assertEquals(31f, hs.signals.vehicleSpeedKmh!!, 0.001f)
    }

    @Test fun `switching profile forgets what the previous one decoded`() {
        val decoder = SwCanVehicleDecoder(VehicleCanProfiles.builtIn)
        decoder.accept(frame(Esp32CanBus.SW_CAN, 0x230, 0x00, 0x00, 0x40))
        assertEquals(true, decoder.signals.driverDoorOpen)

        decoder.profile = VehicleCanProfiles.parse("""{ "schema": 1, "id": "empty" }""")
        assertNull(decoder.signals.driverDoorOpen)
        decoder.accept(frame(Esp32CanBus.SW_CAN, 0x230, 0x00, 0x00, 0x40))
        assertNull(decoder.signals.driverDoorOpen)
    }

    @Test fun `out of range values are cleared only when the profile asks for it`() {
        val decoder = SwCanVehicleDecoder(VehicleCanProfiles.active)
        decoder.accept(frame(Esp32CanBus.SW_CAN, 0x425, 0x02))
        assertEquals(2, decoder.signals.ignitionKeyIndex)
        decoder.accept(frame(Esp32CanBus.SW_CAN, 0x425, 0x07))
        assertNull(decoder.signals.ignitionKeyIndex)
    }

    @Test fun `signed values honour the declared length`() {
        val profile = VehicleCanProfiles.parse(
            """{ "schema": 1, "id": "t", "frames": [ { "bus": "HS", "id": 1, "signals": [
                 { "name": "steering_angle_deg", "type": "signed", "byte": 0, "length": 2, "scale": 0.0625 } ] } ] }""",
        )
        val decoded = profile.decode(frame(Esp32CanBus.HS_CAN, 1, 0xFA, 0xA8))
        assertEquals(-85.5f, decoded.float(CanSignals.STEERING_ANGLE_DEG, null)!!, 0.001f)
    }

    @Test fun `invalid profiles are rejected with the offending frame`() {
        fun error(json: String): String = runCatching { VehicleCanProfiles.parse(json) }.exceptionOrNull()?.message.orEmpty()

        assertTrue(error("""{ "schema": 2, "id": "t" }""").contains("schema"))
        assertTrue(
            error("""{ "schema": 1, "id": "t", "frames": [ { "bus": "SW", "id": "0x260", "signals": [
                 { "name": "turn_lamps", "type": "enum", "byte": 0 } ] } ] }""").contains("SW-CAN 0x260"),
        )
        assertTrue(
            error("""{ "schema": 1, "id": "t", "frames": [ { "bus": "SW", "id": "0x260" }, { "bus": "SW", "id": "0x260" } ] }""")
                .contains("twice"),
        )
    }

    private fun frame(bus: Esp32CanBus, id: Long, vararg bytes: Int) = Esp32CanFrame(
        sequence = 1,
        timestampUs = 1,
        identifier = id,
        flags = 0,
        dlc = bytes.size,
        data = ByteArray(bytes.size) { bytes[it].toByte() },
        bus = bus,
    )
}
