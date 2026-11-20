package com.ts10.inspector.vehicle

import android.content.Context
import android.location.Location
import android.os.SystemClock
import com.ts10.inspector.settings.DriverProfilePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class VehicleMileageState(
    val vehicleOdometerKm: Double? = null,
    val vehicleOdometerSource: String = "HS-CAN 0x4A0 · --",
    val recordedKm: Double = 0.0,
    val partialKm: Double = 0.0,
    val sinceRefuelKm: Double = 0.0,
    val currentTripKm: Double = 0.0,
    val currentTripStartOdometerKm: Double? = null,
    val currentTripStartedElapsedMs: Long = 0L,
    val currentTripStoppedElapsedMs: Long? = null,
    val currentTripActive: Boolean = true,
    val distanceSource: String = "GPS",
    val activeProfileId: String = "driver_1",
    val activeProfileName: String = "Conductor 1",
)

/**
 * Persistent software counters independent from cloud trip recording. The Astra odometer comes
 * from the validated HS-CAN 0x4A0 counter; it remains nullable until that frame is received.
 */
internal object VehicleMileageTracker {
    private val mutableState = MutableStateFlow(VehicleMileageState())
    val state: StateFlow<VehicleMileageState> = mutableState.asStateFlow()

    private var context: Context? = null
    private var lastLocation: Location? = null
    private var distanceSinceSaveKm = 0.0
    private var lastSaveElapsedMs = 0L

    @Synchronized
    fun start(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        val preferences = preferences() ?: return
        val profile = DriverProfilePreferences.get(context).state.value.activeProfile
        if (!preferences.getBoolean(KEY_PROFILE_MIGRATION_COMPLETE, false)) {
            val legacyRecordedKm = preferences.readDouble(KEY_RECORDED_KM).coerceAtLeast(0.0)
            preferences.edit()
                .putDouble(profileRecordedKey(profile.id), legacyRecordedKm)
                .putBoolean(KEY_PROFILE_MIGRATION_COMPLETE, true)
                .apply()
        }
        val now = SystemClock.elapsedRealtime()
        val savedOdometer = preferences.readDouble(KEY_VEHICLE_ODOMETER, -1.0).takeIf { it >= 0.0 }
        mutableState.value = VehicleMileageState(
            vehicleOdometerKm = savedOdometer,
            vehicleOdometerSource = preferences.getString(KEY_VEHICLE_ODOMETER_SOURCE, null)
                ?: "HS-CAN 0x4A0 · --",
            recordedKm = preferences.readDouble(profileRecordedKey(profile.id)).coerceAtLeast(0.0),
            partialKm = preferences.readDouble(KEY_PARTIAL_KM).coerceAtLeast(0.0),
            sinceRefuelKm = preferences.readDouble(KEY_SINCE_REFUEL_KM).coerceAtLeast(0.0),
            currentTripStartedElapsedMs = now,
            currentTripStartOdometerKm = savedOdometer,
            activeProfileId = profile.id,
            activeProfileName = profile.name,
        )
        lastSaveElapsedMs = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun onLocation(location: Location, vehicleSpeedKmh: Int) {
        context?.let { appContext ->
            val profile = DriverProfilePreferences.get(appContext).state.value.activeProfile
            if (profile.id != mutableState.value.activeProfileId) onProfileChanged(profile.id, profile.name)
        }
        if (!location.isMileageUsable()) {
            lastLocation = null
            return
        }
        val previous = lastLocation
        lastLocation = Location(location)
        if (previous == null) return

        val elapsedMs = elapsedMillis(previous, location)
        if (elapsedMs !in 1..MAX_LOCATION_GAP_MS) return
        val movementMeters = previous.distanceTo(location).toDouble()
        val impliedSpeedKmh = movementMeters * 3_600.0 / elapsedMs
        val gpsSpeedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
        val moving = vehicleSpeedKmh >= MIN_MOVING_SPEED_KMH || gpsSpeedKmh >= MIN_MOVING_SPEED_KMH
        if (!moving || movementMeters < MIN_MOVEMENT_METERS || impliedSpeedKmh > MAX_PLAUSIBLE_SPEED_KMH) return

        val incrementKm = movementMeters / 1_000.0
        mutableState.update {
            it.copy(
                recordedKm = it.recordedKm + incrementKm,
                partialKm = it.partialKm + incrementKm,
                sinceRefuelKm = it.sinceRefuelKm + incrementKm,
                currentTripKm = it.currentTripKm + incrementKm,
            )
        }
        distanceSinceSaveKm += incrementKm
        val now = SystemClock.elapsedRealtime()
        if (distanceSinceSaveKm >= SAVE_INTERVAL_KM || now - lastSaveElapsedMs >= SAVE_INTERVAL_MS) persist()
    }

    @Synchronized
    fun updateVehicleOdometer(kilometres: Double, source: String) {
        if (!kilometres.isFinite() || kilometres < 0.0) return
        mutableState.update {
            val canTripKm = it.currentTripStartOdometerKm
                ?.let { start -> (kilometres - start).coerceAtLeast(0.0) }
            it.copy(
                vehicleOdometerKm = kilometres,
                vehicleOdometerSource = source.ifBlank { "CAN" },
                currentTripKm = canTripKm?.let { distance -> maxOf(it.currentTripKm, distance) }
                    ?: it.currentTripKm,
                currentTripStartOdometerKm = it.currentTripStartOdometerKm ?: kilometres,
            )
        }
        persist()
    }

    @Synchronized
    fun onProfileChanged(profileId: String, profileName: String) {
        if (profileId == mutableState.value.activeProfileId) {
            mutableState.update { it.copy(activeProfileName = profileName) }
            return
        }
        persist()
        val recorded = preferences()?.readDouble(profileRecordedKey(profileId))?.coerceAtLeast(0.0) ?: 0.0
        mutableState.update {
            it.copy(
                recordedKm = recorded,
                activeProfileId = profileId,
                activeProfileName = profileName,
            )
        }
        distanceSinceSaveKm = 0.0
        lastSaveElapsedMs = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun resetPartial() {
        mutableState.update { it.copy(partialKm = 0.0) }
        persist()
    }

    @Synchronized
    fun resetSinceRefuel() {
        mutableState.update { it.copy(sinceRefuelKm = 0.0) }
        persist()
    }

    @Synchronized
    fun onAccessoryOff() {
        lastLocation = null
        val now = SystemClock.elapsedRealtime()
        mutableState.update {
            it.copy(
                currentTripStoppedElapsedMs = now,
                currentTripActive = false,
            )
        }
        persist()
    }

    @Synchronized
    fun onAccessoryOn() {
        lastLocation = null
        mutableState.update {
            it.copy(
                currentTripKm = 0.0,
                currentTripStartOdometerKm = it.vehicleOdometerKm,
                currentTripStartedElapsedMs = SystemClock.elapsedRealtime(),
                currentTripStoppedElapsedMs = null,
                currentTripActive = true,
            )
        }
    }

    private fun Location.isMileageUsable(): Boolean =
        latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            (!hasAccuracy() || accuracy <= MAX_ACCURACY_METERS)

    private fun elapsedMillis(previous: Location, current: Location): Long =
        if (previous.elapsedRealtimeNanos > 0L && current.elapsedRealtimeNanos > 0L) {
            (current.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000L
        } else {
            current.time - previous.time
        }

    private fun persist() {
        val snapshot = mutableState.value
        preferences()?.edit()
            ?.putDouble(KEY_VEHICLE_ODOMETER, snapshot.vehicleOdometerKm ?: -1.0)
            ?.putString(KEY_VEHICLE_ODOMETER_SOURCE, snapshot.vehicleOdometerSource)
            ?.putDouble(profileRecordedKey(snapshot.activeProfileId), snapshot.recordedKm)
            ?.putDouble(KEY_PARTIAL_KM, snapshot.partialKm)
            ?.putDouble(KEY_SINCE_REFUEL_KM, snapshot.sinceRefuelKm)
            ?.apply()
        distanceSinceSaveKm = 0.0
        lastSaveElapsedMs = SystemClock.elapsedRealtime()
    }

    private fun preferences() = context?.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)

    private fun android.content.SharedPreferences.readDouble(key: String, default: Double = 0.0): Double =
        if (contains(key)) Double.fromBits(getLong(key, 0L)) else default

    private fun android.content.SharedPreferences.Editor.putDouble(key: String, value: Double) =
        putLong(key, value.toBits())

    private fun profileRecordedKey(profileId: String) = "${KEY_RECORDED_KM}_$profileId"

    private const val PREFERENCES_FILE = "vehicle_mileage"
    private const val KEY_VEHICLE_ODOMETER = "vehicle_odometer_km"
    private const val KEY_VEHICLE_ODOMETER_SOURCE = "vehicle_odometer_source"
    private const val KEY_RECORDED_KM = "recorded_km"
    private const val KEY_PROFILE_MIGRATION_COMPLETE = "recorded_km_profiles_migrated"
    private const val KEY_PARTIAL_KM = "partial_km"
    private const val KEY_SINCE_REFUEL_KM = "since_refuel_km"
    private const val MAX_ACCURACY_METERS = 35f
    private const val MAX_LOCATION_GAP_MS = 120_000L
    private const val MIN_MOVING_SPEED_KMH = 4f
    private const val MIN_MOVEMENT_METERS = 2.0
    private const val MAX_PLAUSIBLE_SPEED_KMH = 220.0
    private const val SAVE_INTERVAL_KM = 0.25
    private const val SAVE_INTERVAL_MS = 60_000L
}
