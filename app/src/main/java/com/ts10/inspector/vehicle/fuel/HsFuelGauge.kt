package com.ts10.inspector.vehicle.fuel

/** A filtered observation of the fuel sender mirrored by HS 0x381 and SW 0x375. */
internal data class HsFuelGaugeReading(
    val rawByte: Int,
    val senderLevel: Int,
    val lowFuel: Boolean,
)

/** Requires a sender increase to remain present while stopped before calling it a refuel. */
internal class SustainedRefuelDetector(
    private val minimumSenderJump: Int = 5,
    private val confirmationDurationMs: Long = 120_000L,
) {
    private var candidateBefore: Int? = null
    private var candidateSinceMs = 0L
    private var stationarySinceMs: Long? = null
    private var lastSampleMs: Long? = null

    fun accept(
        possibleBefore: Int?,
        current: Int,
        vehicleMoving: Boolean,
        nowElapsedMs: Long,
    ): Int? {
        if (lastSampleMs?.let { nowElapsedMs - it !in 0..5_000 } == true) reset()
        lastSampleMs = nowElapsedMs
        if (vehicleMoving) {
            reset()
            return null
        }
        val stationarySince = stationarySinceMs ?: nowElapsedMs.also { stationarySinceMs = it }
        // Let the 30-second sender window settle after braking before arming a fill.
        if (nowElapsedMs - stationarySince < 30_000) return null
        val before = candidateBefore ?: possibleBefore
        if (before == null || before - current < minimumSenderJump) {
            candidateBefore = null
            candidateSinceMs = 0L
            return null
        }
        if (candidateBefore == null) {
            candidateBefore = before
            candidateSinceMs = nowElapsedMs
            return null
        }
        if (nowElapsedMs - candidateSinceMs < confirmationDurationMs) return null
        reset()
        return before
    }

    fun reset() {
        candidateBefore = null
        candidateSinceMs = 0L
        stationarySinceMs = null
        lastSampleMs = null
    }
}

internal const val INITIAL_HS_FULL_SENDER_REFERENCE = 93f // 0x5D after the 42 L fill.
internal const val INITIAL_HS_HALF_SENDER_REFERENCE = 161f // 0xA1 with the dashboard needle at 1/2.
internal const val INITIAL_HS_RESERVE_SENDER_REFERENCE = 187f // 0xBB in the reserve capture.

internal data class FuelCalibrationPoint(val senderLevel: Int, val liters: Float)

/** Piecewise calibration that can gain a new point whenever a known refuel is recorded. */
internal class FuelCalibrationCurve private constructor(
    points: List<FuelCalibrationPoint>,
) {
    private val mutablePoints = points.toMutableList()
    val points: List<FuelCalibrationPoint> get() = mutablePoints.sortedBy { it.senderLevel }

    fun estimate(senderLevel: Int): Float? {
        if (senderLevel !in 0..0xff || mutablePoints.size < 2) return null
        val sorted = points
        if (senderLevel <= sorted.first().senderLevel) return sorted.first().liters
        if (senderLevel >= sorted.last().senderLevel) return sorted.last().liters
        val upperIndex = sorted.indexOfFirst { it.senderLevel >= senderLevel }
        val lower = sorted[upperIndex - 1]
        val upper = sorted[upperIndex]
        val fraction = (senderLevel - lower.senderLevel).toFloat() /
            (upper.senderLevel - lower.senderLevel).coerceAtLeast(1)
        return lower.liters + fraction * (upper.liters - lower.liters)
    }

    fun add(senderLevel: Int, liters: Float, capacityLiters: Float) {
        if (senderLevel !in 0..0xff || liters !in 0f..capacityLiters) return
        mutablePoints.removeAll { kotlin.math.abs(it.senderLevel - senderLevel) <= 1 }
        mutablePoints += FuelCalibrationPoint(senderLevel, liters)
    }

    fun encode(): String = points.joinToString(";") { "${it.senderLevel}:${it.liters}" }

    companion object {
        fun initial(capacityLiters: Float): FuelCalibrationCurve = FuelCalibrationCurve(
            listOf(
                FuelCalibrationPoint(INITIAL_HS_FULL_SENDER_REFERENCE.toInt(), capacityLiters),
                // 2026-10-10: the photographed gauge was at its 1/2 mark
                // while HS 0x381 reported 0xA1 (SW 0x375 mirrored 0x9F).
                FuelCalibrationPoint(INITIAL_HS_HALF_SENDER_REFERENCE.toInt(), capacityLiters / 2f),
                FuelCalibrationPoint(INITIAL_HS_RESERVE_SENDER_REFERENCE.toInt(), PROVISIONAL_RESERVE_LITERS),
            ),
        )

        fun decode(value: String?, capacityLiters: Float): FuelCalibrationCurve {
            val decoded = value
                ?.split(';')
                ?.mapNotNull { encoded ->
                    val fields = encoded.split(':', limit = 2)
                    val sender = fields.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                    val liters = fields.getOrNull(1)?.toFloatOrNull() ?: return@mapNotNull null
                    FuelCalibrationPoint(sender, liters)
                        .takeIf { sender in 0..0xff && liters in 0f..capacityLiters }
                }
                .orEmpty()
            val sorted = decoded.sortedBy { it.senderLevel }
            // Legacy calibration masked bit 0x80 and therefore increased litres with the raw
            // value. Discard it automatically: the complete byte increases as the tank empties.
            val validDirection = sorted.size >= 2 && sorted.first().liters > sorted.last().liters
            return if (validDirection) FuelCalibrationCurve(decoded) else initial(capacityLiters)
        }
    }
}

/**
 * Rejects fuel slosh while preserving the complete 8-bit sender value.
 *
 * Captures around the 2026-09-12 refuel contain roughly ten 0x381 frames per second. The
 * sender moves considerably while driving, so level uses a 30-second median. Captures made after
 * the known fill cross 0x80 during slosh, proving that bit is data rather than a reserve flag.
 */
internal class HsFuelGaugeFilter(
    private val levelWindowSize: Int = 301,
    private val reserveWindowSize: Int = levelWindowSize,
    private val minimumSamples: Int = 31,
) {
    private val samples = ArrayDeque<Int>()

    fun reset() = samples.clear()

    fun accept(rawByte: Int): HsFuelGaugeReading? {
        if (rawByte !in 0..0xff) return null
        samples.addLast(rawByte)
        while (samples.size > levelWindowSize) samples.removeFirst()
        if (samples.size < minimumSamples) return null

        val filteredRaw = samples.sorted()[samples.size / 2]
        val reserveSamples = samples.takeLast(reserveWindowSize)
        val lowFuel = reserveSamples.count { it >= PROVISIONAL_LOW_FUEL_RAW_THRESHOLD } * 2 >
            reserveSamples.size
        val senderLevel = filteredRaw
        return HsFuelGaugeReading(filteredRaw, senderLevel, lowFuel)
    }

    private companion object {
        const val PROVISIONAL_LOW_FUEL_RAW_THRESHOLD = 0xB0
    }
}

/** Reserve is an estimate from a sloshing sender, not a confirmed dashboard warning. */
internal class FuelReserveConfirmation {
    private var candidateSince: Long? = null
    private var lastSample: Long? = null
    private var confirmed = false

    fun accept(sender: Int, nowMs: Long): Boolean {
        if (lastSample?.let { nowMs - it !in 0..5_000 } == true) {
            candidateSince = null
            confirmed = false
        }
        lastSample = nowMs
        if (sender < 0xA8) {
            candidateSince = null
            confirmed = false
        } else if (!confirmed) {
            if (sender < 0xB0) candidateSince = null
            else {
                val since = candidateSince ?: nowMs.also { candidateSince = it }
                if (nowMs - since >= 60_000) confirmed = true
            }
        }
        return confirmed
    }

    fun reset() {
        candidateSince = null
        lastSample = null
        confirmed = false
    }
}

internal fun isFuelVehicleStationary(speedKmh: Int?, sampleAgeMs: Long?): Boolean =
    speedKmh != null && speedKmh in 0..3 && sampleAgeMs != null && sampleAgeMs in 0..3_000

/**
 * Initial two-point calibration obtained from the known 42-litre fill on 2026-09-12.
 *
 * The tank shape and sender are not assumed to be linear forever. These references are persisted
 * and the full point can be refreshed after later fills while LS-CAN remains unavailable.
 */
internal fun estimateHsFuelLiters(
    senderLevel: Int,
    reserveReference: Float,
    fullReference: Float,
    capacityLiters: Float,
    reserveLiters: Float = PROVISIONAL_RESERVE_LITERS,
): Float? {
    val span = reserveReference - fullReference
    if (senderLevel !in 0..0xff || span < 5f || capacityLiters <= 0f ||
        reserveLiters !in 0f..<capacityLiters
    ) return null
    val fraction = ((reserveReference - senderLevel) / span).coerceIn(0f, 1f)
    return reserveLiters + fraction * (capacityLiters - reserveLiters)
}

// Temporary anchor until repeated known fills or LS-CAN provide the actual litres at reserve.
internal const val PROVISIONAL_RESERVE_LITERS = 5f
