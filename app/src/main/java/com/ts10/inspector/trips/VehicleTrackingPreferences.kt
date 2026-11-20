package com.ts10.inspector.trips

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VehicleTrackingSettings(
    val enabled: Boolean = false,
    val scriptUrl: String = "",
    val apiKey: String = "",
    val deviceId: String = "RADIO-TS10",
    val sampleIntervalSeconds: Int = 5,
    val minDistanceMeters: Float = 5f,
    val uploadIntervalSeconds: Int = 15,
    val batchSize: Int = 10,
    val autoCloseMinutes: Int = 15,
) {
    val configured: Boolean
        get() = scriptUrl.startsWith("https://") && apiKey.isNotBlank() && deviceId.isNotBlank()
}

class VehicleTrackingPreferences private constructor(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(read(preferences))
    val state: StateFlow<VehicleTrackingSettings> = mutableState.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
        mutableState.value = read(prefs)
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun save(settings: VehicleTrackingSettings) {
        val safe = settings.copy(
            scriptUrl = settings.scriptUrl.trim(),
            apiKey = settings.apiKey.trim(),
            deviceId = settings.deviceId.trim().ifBlank { "RADIO-TS10" },
            sampleIntervalSeconds = settings.sampleIntervalSeconds.coerceIn(3, 300),
            minDistanceMeters = settings.minDistanceMeters.coerceIn(2f, 1_000f),
            uploadIntervalSeconds = settings.uploadIntervalSeconds.coerceIn(5, 3_600),
            batchSize = settings.batchSize.coerceIn(1, 50),
            autoCloseMinutes = settings.autoCloseMinutes.coerceIn(1, 1_440),
        )
        preferences.edit()
            .putBoolean(KEY_ENABLED, safe.enabled)
            .putString(KEY_SCRIPT_URL, safe.scriptUrl)
            .putString(KEY_API_KEY, safe.apiKey)
            .putString(KEY_DEVICE_ID, safe.deviceId)
            .putInt(KEY_SAMPLE_SECONDS, safe.sampleIntervalSeconds)
            .putFloat(KEY_MIN_DISTANCE_METERS, safe.minDistanceMeters)
            .putInt(KEY_UPLOAD_SECONDS, safe.uploadIntervalSeconds)
            .putInt(KEY_BATCH_SIZE, safe.batchSize)
            .putInt(KEY_AUTO_CLOSE_MINUTES, safe.autoCloseMinutes)
            .apply()
    }

    companion object {
        private const val FILE_NAME = "vehicle_tracking_preferences"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SCRIPT_URL = "script_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_SAMPLE_SECONDS = "sample_seconds"
        private const val KEY_MIN_DISTANCE_METERS = "min_distance_meters"
        private const val KEY_UPLOAD_SECONDS = "upload_seconds"
        private const val KEY_BATCH_SIZE = "batch_size"
        private const val KEY_AUTO_CLOSE_MINUTES = "auto_close_minutes"

        @Volatile
        private var instance: VehicleTrackingPreferences? = null

        fun get(context: Context): VehicleTrackingPreferences = instance ?: synchronized(this) {
            instance ?: VehicleTrackingPreferences(context.applicationContext).also { instance = it }
        }

        fun current(context: Context): VehicleTrackingSettings = get(context).state.value

        private fun read(preferences: SharedPreferences) = VehicleTrackingSettings(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            scriptUrl = preferences.getString(KEY_SCRIPT_URL, "").orEmpty(),
            apiKey = preferences.getString(KEY_API_KEY, "").orEmpty(),
            deviceId = preferences.getString(KEY_DEVICE_ID, "RADIO-TS10").orEmpty(),
            sampleIntervalSeconds = preferences.getInt(KEY_SAMPLE_SECONDS, 5),
            minDistanceMeters = preferences.getFloat(KEY_MIN_DISTANCE_METERS, 5f),
            uploadIntervalSeconds = preferences.getInt(KEY_UPLOAD_SECONDS, 15),
            batchSize = preferences.getInt(KEY_BATCH_SIZE, 10),
            autoCloseMinutes = preferences.getInt(KEY_AUTO_CLOSE_MINUTES, 15),
        )
    }
}
