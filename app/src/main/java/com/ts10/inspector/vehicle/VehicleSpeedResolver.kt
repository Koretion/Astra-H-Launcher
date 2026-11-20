package com.ts10.inspector.vehicle

internal data class ResolvedVehicleSpeed(
    val speedKmh: Int,
    val source: String,
)

/**
 * Single priority rule shared by the speedometer, vehicle animation and diagnostics.
 *
 * A positive QF value is preferred while its packets are fresh. QF zero is treated as "no useful
 * speed" on this CAN box, so a live OBD2 PID 0D value (including a valid zero while stopped) is
 * the safe source. GPS is only the final fallback when neither source is usable.
 */
internal fun resolveVehicleSpeed(
    directCanSpeedKmh: Int?,
    directCanAvailable: Boolean,
    qfSpeedKmh: Int,
    qfAvailable: Boolean,
    obdSpeedKmh: Int?,
    obdAvailable: Boolean,
    gpsSpeedKmh: Int,
): ResolvedVehicleSpeed = when {
    directCanAvailable && directCanSpeedKmh != null ->
        ResolvedVehicleSpeed(directCanSpeedKmh.coerceAtLeast(0), "GMLAN-USB HS-CAN")
    qfAvailable && qfSpeedKmh > 0 -> ResolvedVehicleSpeed(qfSpeedKmh, "QF")
    obdAvailable && obdSpeedKmh != null ->
        ResolvedVehicleSpeed(obdSpeedKmh.coerceAtLeast(0), "OBD2")
    else -> ResolvedVehicleSpeed(gpsSpeedKmh.coerceAtLeast(0), "GPS")
}
