package com.ts10.inspector.vehicle.fuel

import com.ts10.inspector.vehicle.can.profile.CanSignals
import com.ts10.inspector.vehicle.can.profile.VehicleCanProfiles
import com.ts10.inspector.ui.UiText
import com.ts10.inspector.R
import android.content.Context
import android.os.SystemClock
import com.ts10.inspector.vehicle.VehicleMileageTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val FUEL_TANK_CAPACITY_LITERS = 42f
internal const val INITIAL_CONSUMPTION_L_PER_100_KM = 7f

internal data class FuelRangeState(
    val rangeKm: Int? = null,
    val fuelLiters: Float? = null,
    val fuelPercent: Int? = null,
    val fuelSource: UiText = UiText.of(R.string.fuelsrc_unconfirmed),
    val hsFuelRawByte: Int? = null,
    val hsFuelSenderLevel: Int? = null,
    val hsLowFuel: Boolean? = null,
    val senderFaultSuspected: Boolean = false,
    val calibrationPointCount: Int = 0,
    val lastRefuelLiters: Float? = null,
    val lastRefuelWasFull: Boolean = false,
    val averageLitersPer100Km: Float = INITIAL_CONSUMPTION_L_PER_100_KM,
    val learnedDistanceKm: Float = 0f,
    val lowRange: Boolean = false,
    val criticalRange: Boolean = false,
    val popupVisible: Boolean = false,
)

/**
 * Learns a distance-weighted consumption average and keeps it until a refuel is detected.
 *
 * CAN litres take precedence over the standard OBD fuel-level percentage. A sender increase must
 * remain present for two minutes after settling while stopped before suggesting a refuel.
 * Only a user-recorded fill resets the learned average and distance since refuel.
 */
internal object FuelRangeEstimator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(FuelRangeState())
    val state: StateFlow<FuelRangeState> = mutableState.asStateFlow()

    private var context: Context? = null
    private val hsFuelFilter = HsFuelGaugeFilter()
    private val sustainedRefuelDetector = SustainedRefuelDetector()
    private val reserveConfirmation = FuelReserveConfirmation()
    private var lastCanSpeedKmh: Int? = null
    private var lastCanSpeedElapsedMs: Long? = null
    private var lastCanFuelElapsedMs: Long? = null
    private var lastObdFuelElapsedMs: Long? = null
    private var lastFuelLiters: Float? = null
    private var lastPersistedFuelLiters: Float? = null
    private var lastSampleElapsedMs: Long? = null
    private var bucketDistanceKm = 0f
    private var bucketFuelLiters = 0f
    private var learnedDistanceKm = 0f
    private var averageLitersPer100Km = INITIAL_CONSUMPTION_L_PER_100_KM
    private var distanceAtLastSaveKm = 0f
    private var lowWarningIssued = false
    private var hsFuelRawByte: Int? = null
    private var hsFuelSenderLevel: Int? = null
    private var hsLowFuel: Boolean? = null
    private var senderFaultSuspected = false
    private var fuelAnchorLiters: Float? = null
    private var fuelAnchorOdometerKm: Double? = null
    private var calibrationCurve = FuelCalibrationCurve.initial(FUEL_TANK_CAPACITY_LITERS)
    private var previousSessionSenderLevel: Int? = null
    private var pendingPreRefuelSenderLevel: Int? = null
    private var lastSenderPersistElapsedMs = 0L
    private var lastRefuelLiters: Float? = null
    private var lastRefuelWasFull = false
    private var lastVehicleSpeedKmh: Int? = null
    private var lastSpeedElapsedMs: Long? = null
    private var popupJob: Job? = null

    @Synchronized
    fun start(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        preferences()?.let { preferences ->
            averageLitersPer100Km = preferences
                .getFloat(KEY_AVERAGE_CONSUMPTION, INITIAL_CONSUMPTION_L_PER_100_KM)
                .takeIf { it in MIN_PLAUSIBLE_CONSUMPTION..MAX_PLAUSIBLE_CONSUMPTION }
                ?: INITIAL_CONSUMPTION_L_PER_100_KM
            learnedDistanceKm = preferences.getFloat(KEY_LEARNED_DISTANCE, 0f).coerceAtLeast(0f)
            bucketDistanceKm = preferences.getFloat(KEY_BUCKET_DISTANCE, 0f).coerceAtLeast(0f)
            bucketFuelLiters = preferences.getFloat(KEY_BUCKET_FUEL, 0f).coerceAtLeast(0f)
            lastFuelLiters = preferences.getFloat(KEY_LAST_FUEL_LITERS, -1f).takeIf { it >= 0f }
            lastPersistedFuelLiters = lastFuelLiters
            // Sin ancla guardada se crea una nueva con la primera lectura del aforador.
            fuelAnchorOdometerKm = preferences.readDouble(KEY_FUEL_ANCHOR_ODOMETER_KM)
            fuelAnchorLiters = preferences.getFloat(KEY_FUEL_ANCHOR_LITERS, -1f)
                .takeIf { it >= 0f && fuelAnchorOdometerKm != null }
            calibrationCurve = FuelCalibrationCurve.decode(
                preferences.getString(KEY_CALIBRATION_POINTS, null),
                FUEL_TANK_CAPACITY_LITERS,
            )
            previousSessionSenderLevel = preferences.getInt(KEY_LAST_HS_SENDER_LEVEL, -1)
                .takeIf { it in 0..0xff }
            lastRefuelLiters = preferences.getFloat(KEY_LAST_REFUEL_LITERS, -1f).takeIf { it > 0f }
            lastRefuelWasFull = preferences.getBoolean(KEY_LAST_REFUEL_WAS_FULL, false)
            distanceAtLastSaveKm = learnedDistanceKm + bucketDistanceKm
        }
        publish(lastFuelLiters, if (lastFuelLiters == null) UiText.of(R.string.fuelsrc_unconfirmed) else UiText.of(R.string.fuelsrc_saved))
    }

    @Synchronized
    fun updateCanFuelLiters(liters: Float) {
        if (liters !in MIN_VALID_FUEL_LITERS..MAX_TANK_READING_LITERS) return
        lastCanFuelElapsedMs = SystemClock.elapsedRealtime()
        updateFuelLiters(liters, UiText.Raw("CAN Box"), refreshAnchor = true)
    }

    @Synchronized
    fun updateObdFuelPercent(percent: Int) {
        if (percent !in 0..100) return
        val canAge = lastCanFuelElapsedMs?.let { SystemClock.elapsedRealtime() - it }
        if (canAge != null && canAge <= CAN_FUEL_PRIORITY_MS) return
        lastObdFuelElapsedMs = SystemClock.elapsedRealtime()
        updateFuelLiters(percent * FUEL_TANK_CAPACITY_LITERS / 100f, UiText.Raw("OBD2 PID 2F"), refreshAnchor = true)
    }

    /** Updates the sender mirrored in HS-CAN 0x381 byte 5 and SW-CAN 0x375 byte 1. */
    @Synchronized
    fun updateHsFuelRawByte(rawByte: Int) {
        if (rawByte !in MIN_VALID_HS_RAW..MAX_VALID_HS_RAW) return
        val reading = hsFuelFilter.accept(rawByte) ?: return
        val previousSenderLevel = hsFuelSenderLevel
        val senderBeforeRefuel = when {
            previousSenderLevel != null && previousSenderLevel - reading.senderLevel >= REFUEL_SENDER_JUMP ->
                previousSenderLevel
            // A persisted sender may have been saved in a bend or on a slope. It is not
            // evidence of a fill across process restarts.
            else -> null
        }
        hsFuelRawByte = reading.rawByte
        hsFuelSenderLevel = reading.senderLevel

        val now = SystemClock.elapsedRealtime()
        hsLowFuel = reserveConfirmation.accept(reading.senderLevel, now)
        val trustedFuelIsFresh = listOfNotNull(lastCanFuelElapsedMs, lastObdFuelElapsedMs)
            .any { now - it <= TRUSTED_FUEL_PRIORITY_MS }
        val estimatedLiters = calibrationCurve.estimate(reading.senderLevel)
        val distanceEstimate = estimateFuelFromDistance(
            anchorLiters = fuelAnchorLiters,
            anchorOdometerKm = fuelAnchorOdometerKm,
            currentOdometerKm = VehicleMileageTracker.state.value.vehicleOdometerKm,
            averageLitersPer100Km = averageLitersPer100Km,
        )
        val confirmedPreRefuelSender = sustainedRefuelDetector.accept(
            possibleBefore = senderBeforeRefuel,
            current = reading.senderLevel,
            vehicleMoving = !isFuelVehicleStationary(
                if (lastCanSpeedElapsedMs?.let { now - it <= 3_000 } == true) lastCanSpeedKmh else lastVehicleSpeedKmh,
                (lastCanSpeedElapsedMs?.takeIf { now - it <= 3_000 } ?: lastSpeedElapsedMs)?.let { now - it },
            ),
            nowElapsedMs = now,
        )
        val detectedRefuelLiters = confirmedPreRefuelSender
            ?.let(calibrationCurve::estimate)
            ?.let { before -> estimatedLiters?.minus(before) }
            ?.takeIf { it >= REFUEL_INCREASE_LITERS }
        if (detectedRefuelLiters != null) {
            pendingPreRefuelSenderLevel = confirmedPreRefuelSender
            FuelTrackingRepository.onRefuelDetected(detectedRefuelLiters)
            // Only the user's confirmation may reset consumption and trip history.
            previousSessionSenderLevel = reading.senderLevel
        }
        if (lastSenderPersistElapsedMs == 0L || now - lastSenderPersistElapsedMs >= SENDER_SAVE_INTERVAL_MS) {
            preferences()?.edit()?.putInt(KEY_LAST_HS_SENDER_LEVEL, reading.senderLevel)?.apply()
            lastSenderPersistElapsedMs = now
            if (confirmedPreRefuelSender == null) previousSessionSenderLevel = reading.senderLevel
        }
        if (!trustedFuelIsFresh && estimatedLiters != null) {
            val implausibleDrop = distanceEstimate != null &&
                estimatedLiters < distanceEstimate - MAX_SENDER_DROP_BELOW_DISTANCE_ESTIMATE_LITERS
            senderFaultSuspected = implausibleDrop
            if (implausibleDrop) {
                updateFuelLiters(requireNotNull(distanceEstimate), DISTANCE_FUEL_SOURCE, refreshAnchor = false)
            } else {
                // Keep a long-lived distance anchor. Re-anchoring every sender frame would let a
                // slowly failing sender drag the estimate down while still appearing plausible.
                updateFuelLiters(
                    estimatedLiters,
                    UiText.of(R.string.fuelsrc_hs, VehicleCanProfiles.active.sourceLabel(CanSignals.FUEL_SENDER_RAW)),
                    refreshAnchor = fuelAnchorOdometerKm == null,
                )
            }
        } else {
            publish(lastFuelLiters, mutableState.value.fuelSource)
        }
    }

    @Synchronized
    fun resetHsFuelSamples() {
        hsFuelFilter.reset()
        sustainedRefuelDetector.reset()
        reserveConfirmation.reset()
        lastCanSpeedElapsedMs = null
        hsLowFuel = null
    }

    @Synchronized
    fun updateCanSpeed(speedKmh: Int) {
        lastCanSpeedKmh = speedKmh
        lastCanSpeedElapsedMs = SystemClock.elapsedRealtime()
        if (speedKmh > REFUEL_MAX_STATIONARY_SPEED_KMH) sustainedRefuelDetector.reset()
    }

    /** Records a user-observed fill and refreshes the full sender reference when appropriate. */
    @Synchronized
    fun recordRefuel(liters: Float, filledToFull: Boolean): Boolean {
        if (liters !in MIN_REFUEL_LITERS..MAX_REFUEL_LITERS) return false
        val postRefuelSender = hsFuelSenderLevel
        val preRefuelSender = pendingPreRefuelSenderLevel ?: previousSessionSenderLevel
        val estimatedPreRefuelLiters = preRefuelSender?.let(calibrationCurve::estimate)
        if (filledToFull) {
            preRefuelSender
                ?.takeIf { postRefuelSender == null || it - postRefuelSender >= REFUEL_SENDER_JUMP }
                ?.let {
                    calibrationCurve.add(
                        it,
                        (FUEL_TANK_CAPACITY_LITERS - liters).coerceAtLeast(0f),
                        FUEL_TANK_CAPACITY_LITERS,
                    )
                }
            postRefuelSender?.let {
                calibrationCurve.add(it, FUEL_TANK_CAPACITY_LITERS, FUEL_TANK_CAPACITY_LITERS)
            }
        } else if (postRefuelSender != null) {
            val calibrationBeforeLiters = preRefuelSender
                ?.takeIf { it - postRefuelSender >= REFUEL_SENDER_JUMP }
                ?.let(calibrationCurve::estimate)
            calibrationBeforeLiters?.let {
                calibrationCurve.add(
                    postRefuelSender,
                    (it + liters).coerceAtMost(FUEL_TANK_CAPACITY_LITERS),
                    FUEL_TANK_CAPACITY_LITERS,
                )
            }
        }
        pendingPreRefuelSenderLevel = null
        previousSessionSenderLevel = postRefuelSender
        lastRefuelLiters = liters
        lastRefuelWasFull = filledToFull
        hsLowFuel = if (filledToFull) false else hsLowFuel
        resetLearningAfterRefuel()
        VehicleMileageTracker.resetSinceRefuel()
        val updatedLiters = if (filledToFull) {
            FUEL_TANK_CAPACITY_LITERS
        } else {
            ((estimatedPreRefuelLiters ?: lastFuelLiters ?: 0f) + liters)
                .coerceAtMost(FUEL_TANK_CAPACITY_LITERS)
        }
        preferences()?.edit()
            ?.putString(KEY_CALIBRATION_POINTS, calibrationCurve.encode())
            ?.putInt(KEY_LAST_HS_SENDER_LEVEL, postRefuelSender ?: -1)
            ?.putFloat(KEY_LAST_REFUEL_LITERS, liters)
            ?.putBoolean(KEY_LAST_REFUEL_WAS_FULL, filledToFull)
            ?.apply()
        senderFaultSuspected = false
        updateFuelLiters(updatedLiters, USER_REFUEL_SOURCE, refreshAnchor = true)
        return true
    }

    @Synchronized
    fun addConsumptionSample(speedKmh: Int?, consumptionLitersPer100Km: Float?) {
        val now = SystemClock.elapsedRealtime()
        lastVehicleSpeedKmh = speedKmh
        lastSpeedElapsedMs = now
        val previous = lastSampleElapsedMs
        lastSampleElapsedMs = now
        if (previous == null || speedKmh == null || consumptionLitersPer100Km == null) return
        val elapsedMs = now - previous
        if (elapsedMs !in 1..MAX_SAMPLE_GAP_MS ||
            speedKmh < MIN_LEARNING_SPEED_KMH ||
            consumptionLitersPer100Km !in MIN_PLAUSIBLE_CONSUMPTION..MAX_PLAUSIBLE_CONSUMPTION
        ) {
            return
        }

        val distanceKm = speedKmh * elapsedMs / 3_600_000f
        bucketDistanceKm += distanceKm
        bucketFuelLiters += consumptionLitersPer100Km * distanceKm / 100f

        if (senderFaultSuspected) {
            estimateFuelFromDistance(
                anchorLiters = fuelAnchorLiters,
                anchorOdometerKm = fuelAnchorOdometerKm,
                currentOdometerKm = VehicleMileageTracker.state.value.vehicleOdometerKm,
                averageLitersPer100Km = averageLitersPer100Km,
            )?.let { estimated ->
                updateFuelLiters(estimated, DISTANCE_FUEL_SOURCE, refreshAnchor = false)
            }
        }

        if (bucketDistanceKm >= LEARNING_BLOCK_KM) {
            val blockAverage = bucketFuelLiters * 100f / bucketDistanceKm
            if (blockAverage in MIN_PLAUSIBLE_CONSUMPTION..MAX_PLAUSIBLE_CONSUMPTION) {
                averageLitersPer100Km =
                    averageLitersPer100Km * (1f - LEARNING_WEIGHT) + blockAverage * LEARNING_WEIGHT
                learnedDistanceKm += bucketDistanceKm
            }
            bucketDistanceKm = 0f
            bucketFuelLiters = 0f
            persist()
            distanceAtLastSaveKm = learnedDistanceKm
            publish(lastFuelLiters, mutableState.value.fuelSource)
        } else if (learnedDistanceKm + bucketDistanceKm - distanceAtLastSaveKm >= SAVE_INTERVAL_KM) {
            persist()
            distanceAtLastSaveKm = learnedDistanceKm + bucketDistanceKm
        }
    }

    @Synchronized
    private fun updateFuelLiters(liters: Float, source: UiText, refreshAnchor: Boolean = false) {
        // A change of source or a single OBD/CAN level jump cannot prove a refuel.
        lastFuelLiters = liters
        if (refreshAnchor) {
            val odometer = VehicleMileageTracker.state.value.vehicleOdometerKm
            fuelAnchorLiters = liters
            fuelAnchorOdometerKm = odometer
            preferences()?.edit()
                ?.putFloat(KEY_FUEL_ANCHOR_LITERS, liters)
                ?.putDouble(KEY_FUEL_ANCHOR_ODOMETER_KM, odometer)
                ?.apply()
        }
        if (lastPersistedFuelLiters?.let { abs(liters - it) >= FUEL_SAVE_DELTA_LITERS } != false) {
            preferences()?.edit()?.putFloat(KEY_LAST_FUEL_LITERS, liters)?.apply()
            lastPersistedFuelLiters = liters
        }
        publish(liters, source)
    }

    private fun resetLearningAfterRefuel() {
        averageLitersPer100Km = INITIAL_CONSUMPTION_L_PER_100_KM
        learnedDistanceKm = 0f
        bucketDistanceKm = 0f
        bucketFuelLiters = 0f
        distanceAtLastSaveKm = 0f
        lowWarningIssued = false
        persist()
    }

    private fun publish(fuelLiters: Float?, source: UiText) {
        val rangeKm = fuelLiters
            ?.let { (it * 100f / averageLitersPer100Km).roundToInt().coerceAtLeast(0) }
        // Reserve derived from the sender remains diagnostic: the sender can fail just like the
        // dashboard needle. Driver warnings follow the independently calculated range.
        val low = rangeKm?.let { it < LOW_RANGE_KM } == true
        val critical = rangeKm?.let { it < CRITICAL_RANGE_KM } == true

        if (!low && (rangeKm == null || rangeKm >= LOW_RANGE_REARM_KM)) lowWarningIssued = false
        mutableState.update {
            it.copy(
                rangeKm = rangeKm,
                fuelLiters = fuelLiters,
                fuelPercent = fuelLiters
                    ?.let { (it * 100f / FUEL_TANK_CAPACITY_LITERS).roundToInt().coerceIn(0, 100) },
                fuelSource = source,
                hsFuelRawByte = hsFuelRawByte,
                hsFuelSenderLevel = hsFuelSenderLevel,
                hsLowFuel = hsLowFuel,
                senderFaultSuspected = senderFaultSuspected,
                calibrationPointCount = calibrationCurve.points.size,
                lastRefuelLiters = lastRefuelLiters,
                lastRefuelWasFull = lastRefuelWasFull,
                averageLitersPer100Km = averageLitersPer100Km,
                learnedDistanceKm = learnedDistanceKm + bucketDistanceKm,
                lowRange = low,
                criticalRange = critical,
            )
        }
        if (low && !lowWarningIssued) showLowFuelPopup()
    }

    private fun showLowFuelPopup() {
        lowWarningIssued = true
        popupJob?.cancel()
        mutableState.update { it.copy(popupVisible = true) }
        popupJob = scope.launch {
            delay(LOW_FUEL_POPUP_DURATION_MS)
            mutableState.update { it.copy(popupVisible = false) }
        }
    }

    private fun persist() {
        preferences()?.edit()
            ?.putFloat(KEY_AVERAGE_CONSUMPTION, averageLitersPer100Km)
            ?.putFloat(KEY_LEARNED_DISTANCE, learnedDistanceKm)
            ?.putFloat(KEY_BUCKET_DISTANCE, bucketDistanceKm)
            ?.putFloat(KEY_BUCKET_FUEL, bucketFuelLiters)
            ?.apply()
    }

    private fun preferences() =
        context?.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)

    private const val PREFERENCES_FILE = "fuel_range_preferences"
    private const val KEY_AVERAGE_CONSUMPTION = "average_consumption"
    private const val KEY_LEARNED_DISTANCE = "learned_distance"
    private const val KEY_BUCKET_DISTANCE = "bucket_distance"
    private const val KEY_BUCKET_FUEL = "bucket_fuel"
    private const val KEY_LAST_FUEL_LITERS = "last_fuel_liters"
    private const val KEY_FUEL_ANCHOR_LITERS = "fuel_anchor_liters"
    private const val KEY_FUEL_ANCHOR_ODOMETER_KM = "fuel_anchor_odometer_km"
    private const val KEY_CALIBRATION_POINTS = "hs_calibration_points"
    private const val KEY_LAST_HS_SENDER_LEVEL = "last_hs_sender_level"
    private const val KEY_LAST_REFUEL_LITERS = "last_refuel_liters"
    private const val KEY_LAST_REFUEL_WAS_FULL = "last_refuel_was_full"
    private const val LEARNING_BLOCK_KM = 5f
    private const val SAVE_INTERVAL_KM = 1f
    private const val LEARNING_WEIGHT = 0.35f
    private const val REFUEL_INCREASE_LITERS = 2f
    private const val MIN_VALID_FUEL_LITERS = 0.1f
    private const val MAX_TANK_READING_LITERS = 50f
    private const val FUEL_SAVE_DELTA_LITERS = 0.1f
    private const val CAN_FUEL_PRIORITY_MS = 10_000L
    private const val TRUSTED_FUEL_PRIORITY_MS = 10_000L
    private const val MIN_LEARNING_SPEED_KMH = 5
    private const val MIN_PLAUSIBLE_CONSUMPTION = 1f
    private const val MAX_PLAUSIBLE_CONSUMPTION = 40f
    private const val MAX_SAMPLE_GAP_MS = 10_000L
    private const val LOW_RANGE_KM = 50
    private const val LOW_RANGE_REARM_KM = 55
    private const val CRITICAL_RANGE_KM = 10
    private const val LOW_FUEL_POPUP_DURATION_MS = 6_000L
    private const val MIN_VALID_HS_RAW = 0x20
    private const val MAX_VALID_HS_RAW = 0xF0
    private const val MIN_REFUEL_LITERS = 0.1f
    private const val MAX_REFUEL_LITERS = 80f
    private const val REFUEL_SENDER_JUMP = 5
    private const val REFUEL_MAX_STATIONARY_SPEED_KMH = 3
    private const val SENDER_SAVE_INTERVAL_MS = 60_000L
    private const val MAX_SENDER_DROP_BELOW_DISTANCE_ESTIMATE_LITERS = 3f
    private val DISTANCE_FUEL_SOURCE = UiText.of(R.string.fuelsrc_distance)
    private val USER_REFUEL_SOURCE = UiText.of(R.string.fuelsrc_refuel)

    private fun android.content.SharedPreferences.readDouble(key: String): Double? =
        if (contains(key)) Double.fromBits(getLong(key, 0L)).takeIf(Double::isFinite) else null

    private fun android.content.SharedPreferences.Editor.putDouble(key: String, value: Double?) =
        if (value == null) remove(key) else putLong(key, value.toBits())
}

internal fun estimateFuelFromDistance(
    anchorLiters: Float?,
    anchorOdometerKm: Double?,
    currentOdometerKm: Double?,
    averageLitersPer100Km: Float,
): Float? {
    if (anchorLiters == null || anchorLiters !in 0f..FUEL_TANK_CAPACITY_LITERS ||
        anchorOdometerKm == null || currentOdometerKm == null ||
        !anchorOdometerKm.isFinite() || !currentOdometerKm.isFinite() ||
        currentOdometerKm < anchorOdometerKm || averageLitersPer100Km <= 0f
    ) return null
    val consumed = ((currentOdometerKm - anchorOdometerKm) * averageLitersPer100Km / 100.0).toFloat()
    return (anchorLiters - consumed).coerceIn(0f, FUEL_TANK_CAPACITY_LITERS)
}
