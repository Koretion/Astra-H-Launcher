package com.ts10.inspector.vehicle

import kotlin.math.abs

/**
 * Estimates a manual gear from the fixed engine-RPM / road-speed ratios.
 *
 * A manual Astra H does not normally publish the selected forward gear on CAN. The ratios below
 * are the centres measured in the vehicle captures; reverse is still read directly from MS-CAN.
 * During clutch operation (or when the shafts are not coupled) no forward gear is reported.
 */
internal fun estimateManualGear(
    engineRpm: Int?,
    speedKmh: Float?,
    clutchPressed: Boolean?,
    reverse: Boolean?,
): Int? {
    if (reverse == true) return -1
    if (clutchPressed == true || engineRpm == null || speedKmh == null) return null
    if (engineRpm < MIN_COUPLED_RPM || speedKmh < MIN_COUPLED_SPEED_KMH) return null

    val ratio = engineRpm / speedKmh
    val closest = FORWARD_GEAR_RATIOS.minByOrNull { abs(ratio - it.rpmPerKmh) } ?: return null
    val relativeError = abs(ratio - closest.rpmPerKmh) / closest.rpmPerKmh
    return closest.gear.takeIf { relativeError <= MAX_RATIO_ERROR }
}

/** Fill for the F1-style rev lights, calibrated for the petrol engine's usable range. */
internal fun f1RpmFill(engineRpm: Int?): Float = when (engineRpm) {
    null -> 0f
    else -> ((engineRpm - IDLE_RPM) / (TACHOMETER_MAX_RPM - IDLE_RPM)).coerceIn(0f, 1f)
}

internal enum class F1RpmBand { BLUE, GREEN, AMBER, RED }

internal fun f1RpmBand(engineRpm: Float): F1RpmBand = when {
    engineRpm < 2_500f -> F1RpmBand.BLUE
    engineRpm < 5_500f -> F1RpmBand.GREEN
    engineRpm < REDLINE_RPM -> F1RpmBand.AMBER
    else -> F1RpmBand.RED
}

internal fun shouldRecommendEconomicUpshift(engineRpm: Int?, gear: Int?): Boolean =
    engineRpm != null && engineRpm >= ECONOMIC_UPSHIFT_RPM && gear in 1 until TOP_GEAR

private data class GearRatio(val gear: Int, val rpmPerKmh: Float)

private val FORWARD_GEAR_RATIOS = listOf(
    GearRatio(1, 128f),
    GearRatio(2, 74f),
    GearRatio(3, 48f),
    GearRatio(4, 37f),
    GearRatio(5, 30f),
    GearRatio(6, 24f),
)

private const val MIN_COUPLED_RPM = 700
internal const val IDLE_RPM = 700f
internal const val REDLINE_RPM = 6_500f
internal const val TACHOMETER_MAX_RPM = 7_000f
private const val MIN_COUPLED_SPEED_KMH = 7f
private const val MAX_RATIO_ERROR = 0.115f
private const val ECONOMIC_UPSHIFT_RPM = 2_100
private const val TOP_GEAR = 6
