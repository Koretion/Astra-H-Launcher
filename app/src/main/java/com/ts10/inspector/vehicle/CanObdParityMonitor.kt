package com.ts10.inspector.vehicle

import com.ts10.inspector.vehicle.can.Esp32CanState
import com.ts10.inspector.vehicle.fuel.FuelRangeState
import com.ts10.inspector.vehicle.obd.Obd2State
import com.ts10.inspector.vehicle.tires.TireMonitoringSource
import com.ts10.inspector.vehicle.tires.TireMonitoringState
import java.util.Locale

internal data class CanObdParityItem(
    val label: String,
    val obdValue: String,
    val canValue: String,
    val status: String,
)

internal data class CanObdParityReport(
    val items: List<CanObdParityItem>,
    val validatedDirectCanCount: Int,
    val totalRequired: Int,
) {
    val elmCanBeRemoved: Boolean get() = totalRequired > 0 && validatedDirectCanCount == totalRequired
}

/**
 * Live migration checklist. A CAN value is deliberately counted only after its decoder has been
 * validated; merely finding a correlated byte must never displace a working OBD-II sensor.
 */
internal fun buildCanObdParityReport(
    obd: Obd2State,
    tires: TireMonitoringState,
    can: Esp32CanState = Esp32CanState(),
    fuel: FuelRangeState = FuelRangeState(),
): CanObdParityReport {
    val hsWheelSpeeds = tires.wheelSpeeds.takeIf {
        tires.source == TireMonitoringSource.DDS_HS_CAN
    }
    val directHsSpeed = can.hsVehicleSpeedKmh ?: hsWheelSpeeds?.values()?.average()?.toFloat()
    val speedComparison = if (obd.speedKmh != null && directHsSpeed != null) {
        val difference = kotlin.math.abs(obd.speedKmh - directHsSpeed)
        if (difference <= 3f) "Matches (Δ %.1f km/h)".format(Locale.US, difference)
        else "Check (Δ %.1f km/h)".format(Locale.US, difference)
    } else {
        "Waiting for a validated HS/MS frame"
    }
    val items = listOf(
        CanObdParityItem("Speed", obd.speedKmh.value("km/h"), directHsSpeed?.let { "%.1f km/h".format(Locale.US, it) } ?: "--", speedComparison),
        CanObdParityItem("RPM", obd.rpm.value("rpm"), can.hsEngineRpm.value("rpm"), if (can.hsEngineRpm != null) "HS-CAN validated" else "No frame"),
        CanObdParityItem("Coolant", obd.coolantCelsius.value("°C"), can.hsCoolantCelsius.value("°C"), if (can.hsCoolantCelsius != null) "HS-CAN validated" else "No frame"),
        CanObdParityItem("Oil", obd.oilCelsius.value("°C"), "--", "Not identified yet"),
        CanObdParityItem(
            "Fuel",
            obd.fuelPercent.value("%"),
            fuel.fuelPercent.value("%"),
            if (fuel.fuelPercent != null) "CAN fuel sender validated; estimated conversion" else "No frame",
        ),
        CanObdParityItem("Air flow", obd.massAirFlowGps.value("g/s"), "--", "Not identified yet"),
        CanObdParityItem("Fuel flow", obd.fuelRateLitersPerHour.value("L/h"), "--", "Not identified yet"),
        CanObdParityItem("Consumption", obd.consumptionLitersPer100Km.value("L/100"), "--", "Derived; needs CAN speed and flow"),
    )
    val validated = listOf(directHsSpeed, can.hsEngineRpm, can.hsCoolantCelsius, fuel.fuelPercent).count { it != null }
    return CanObdParityReport(items, validated, items.size)
}

private fun Number?.value(unit: String): String = this?.let { "$it $unit" } ?: "--"
