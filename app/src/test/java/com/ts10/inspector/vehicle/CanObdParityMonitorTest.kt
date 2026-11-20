package com.ts10.inspector.vehicle

import com.ts10.inspector.vehicle.can.Esp32CanState
import com.ts10.inspector.vehicle.fuel.FuelRangeState
import com.ts10.inspector.vehicle.obd.Obd2State
import com.ts10.inspector.vehicle.tires.TireMonitoringSource
import com.ts10.inspector.vehicle.tires.TireMonitoringState
import com.ts10.inspector.vehicle.tires.WheelSpeeds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CanObdParityMonitorTest {
    @Test
    fun `ELM remains required while direct CAN mappings are pending`() {
        val report = buildCanObdParityReport(
            Obd2State(speedKmh = 50, rpm = 2_000, coolantCelsius = 90),
            TireMonitoringState(),
        )
        assertEquals(0, report.validatedDirectCanCount)
        assertFalse(report.elmCanBeRemoved)
    }

    @Test
    fun `compares ELM speed with validated HS wheel average`() {
        val report = buildCanObdParityReport(
            Obd2State(speedKmh = 50),
            TireMonitoringState(
                source = TireMonitoringSource.DDS_HS_CAN,
                wheelSpeeds = WheelSpeeds(49f, 50f, 50f, 49f),
            ),
        )
        assertEquals(1, report.validatedDirectCanCount)
        assertEquals("Matches (Δ 0.5 km/h)", report.items.first().status)
    }

    @Test
    fun `prefers validated HS vehicle speed over stale wheel state`() {
        val report = buildCanObdParityReport(
            Obd2State(speedKmh = 0),
            TireMonitoringState(
                source = TireMonitoringSource.DDS_HS_CAN,
                wheelSpeeds = WheelSpeeds(26f, 26f, 26f, 26f),
            ),
            Esp32CanState(hsVehicleSpeedKmh = 0f),
        )

        assertEquals("0.0 km/h", report.items.first().canValue)
        assertEquals(0, report.items.first().status.indexOf("Matches"))
    }

    @Test
    fun `reports the validated CAN sender instead of leaving fuel pending`() {
        val report = buildCanObdParityReport(
            Obd2State(fuelPercent = 74),
            TireMonitoringState(),
            fuel = FuelRangeState(fuelPercent = 75),
        )

        val fuel = report.items.first { it.label == "Fuel" }
        assertEquals("75 %", fuel.canValue)
        assertEquals("CAN fuel sender validated; estimated conversion", fuel.status)
        assertEquals(1, report.validatedDirectCanCount)
    }
}
