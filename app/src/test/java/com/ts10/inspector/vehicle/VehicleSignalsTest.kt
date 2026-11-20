package com.ts10.inspector.vehicle

import com.ts10.inspector.vehicle.can.Esp32CanState
import com.ts10.inspector.vehicle.obd.Obd2State
import com.ts10.inspector.vehicle.obd.ObdConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleSignalsTest {
    private val connectedObd = Obd2State(
        connection = ObdConnectionState.CONNECTED,
        rpm = 900,
        speedKmh = 48,
        coolantCelsius = 85,
        fuelRateLitersPerHour = 1.2f,
    )

    @Test
    fun `direct CAN wins over OBD2`() {
        val signals = fuseVehicleSignals(
            Esp32CanState(hsEngineRpm = 2100, hsVehicleSpeedKmh = 50f, hsCoolantCelsius = 90),
            connectedObd,
        )

        assertEquals(VehicleSignal(2100, VehicleSignalSource.HS_CAN), signals.engineRpm)
        assertEquals(VehicleSignal(50f, VehicleSignalSource.HS_CAN), signals.busSpeedKmh)
        assertEquals(VehicleSignal(90, VehicleSignalSource.HS_CAN), signals.coolantCelsius)
    }

    @Test
    fun `MS-CAN fills in when HS-CAN is silent`() {
        val signals = fuseVehicleSignals(Esp32CanState(msEngineRpm = 1500, msVehicleSpeedKmh = 30f), Obd2State())

        assertEquals(VehicleSignal(1500, VehicleSignalSource.MS_CAN), signals.engineRpm)
        assertEquals(VehicleSignal(30f, VehicleSignalSource.MS_CAN), signals.busSpeedKmh)
    }

    @Test
    fun `car with only an ELM327 still gets engine values`() {
        val signals = fuseVehicleSignals(Esp32CanState(), connectedObd)

        assertEquals(VehicleSignal(900, VehicleSignalSource.OBD2), signals.engineRpm)
        assertEquals(VehicleSignal(48f, VehicleSignalSource.OBD2), signals.busSpeedKmh)
        assertEquals(VehicleSignal(85, VehicleSignalSource.OBD2), signals.coolantCelsius)
        assertEquals(VehicleSignal(1.2f, VehicleSignalSource.OBD2), signals.fuelRateLitersPerHour)
    }

    @Test
    fun `stale OBD2 readings are ignored while disconnected`() {
        val signals = fuseVehicleSignals(
            Esp32CanState(),
            connectedObd.copy(connection = ObdConnectionState.CONNECTING),
        )

        assertNull(signals.engineRpm)
        assertNull(signals.busSpeedKmh)
        assertNull(signals.coolantCelsius)
        assertNull(signals.fuelRateLitersPerHour)
    }
}
