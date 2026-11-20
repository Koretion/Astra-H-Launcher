package com.ts10.inspector.vehicle

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class SpeedWarningSettings(
    val enabled: Boolean = false,
    val minimumSpeedKmh: Int = 50,
    val repeatEnabled: Boolean = false,
    val repeatIntervalSeconds: Int = 60,
)

internal class SpeedWarningPreferences private constructor(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(read())
    val state: StateFlow<SpeedWarningSettings> = mutableState.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
        mutableState.value = read()
    }

    fun setMinimumSpeedKmh(value: Int) {
        preferences.edit().putInt(KEY_MINIMUM_SPEED, value.coerceIn(0, 130)).apply()
        mutableState.value = read()
    }

    fun setRepeatEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_REPEAT_ENABLED, enabled).apply()
        mutableState.value = read()
    }

    fun setRepeatIntervalSeconds(value: Int) {
        preferences.edit().putInt(KEY_REPEAT_INTERVAL_SECONDS, value.coerceIn(10, 600)).apply()
        mutableState.value = read()
    }

    private fun read() = SpeedWarningSettings(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        minimumSpeedKmh = preferences.getInt(KEY_MINIMUM_SPEED, 50).coerceIn(0, 130),
        repeatEnabled = preferences.getBoolean(KEY_REPEAT_ENABLED, false),
        repeatIntervalSeconds = preferences.getInt(KEY_REPEAT_INTERVAL_SECONDS, 60).coerceIn(10, 600),
    )

    companion object {
        const val FILE_NAME = "speed_warning_preferences"
        const val KEY_ENABLED = "enabled"
        const val KEY_MINIMUM_SPEED = "minimum_speed_kmh"
        const val KEY_REPEAT_ENABLED = "repeat_enabled"
        const val KEY_REPEAT_INTERVAL_SECONDS = "repeat_interval_seconds"

        @Volatile private var instance: SpeedWarningPreferences? = null

        fun get(context: Context): SpeedWarningPreferences = instance ?: synchronized(this) {
            instance ?: SpeedWarningPreferences(context.applicationContext).also { instance = it }
        }
    }
}

internal fun speedWarningThresholdKmh(speedLimitKmh: Int): Int =
    if (speedLimitKmh <= 100) speedLimitKmh + 3
    else (speedLimitKmh * 1.03).toInt()

/** Emits one short warning on entry and repeats it at a restrained interval while speeding. */
internal object SpeedWarningController {
    private var context: Context? = null
    private var toneGenerator: ToneGenerator? = null
    private var warningActive = false
    private var lastBeepElapsedMs = 0L

    fun start(context: Context) {
        this.context = context.applicationContext
    }

    @Synchronized
    fun evaluate(speedKmh: Int, speedLimitKmh: Int?, nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        val appContext = context ?: return
        val settings = SpeedWarningPreferences.get(appContext).state.value
        val threshold = speedLimitKmh?.let(::speedWarningThresholdKmh)
        val exceeding = settings.enabled && threshold != null &&
            speedKmh >= settings.minimumSpeedKmh && speedKmh > threshold
        if (!exceeding) {
            warningActive = false
            return
        }
        val repeatDue = settings.repeatEnabled &&
            nowElapsedMs - lastBeepElapsedMs >= settings.repeatIntervalSeconds * 1_000L
        if (!warningActive || repeatDue) {
            warningActive = true
            lastBeepElapsedMs = nowElapsedMs
            // STREAM_NOTIFICATION is commonly muted or attenuated by TS10 ROMs. The launcher
            // warning must follow the same audible stream as navigation and media.
            val tone = toneGenerator ?: ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME).also {
                toneGenerator = it
            }
            runCatching { tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, TONE_DURATION_MS) }
        }
    }

    private const val TONE_DURATION_MS = 420
    private const val TONE_VOLUME = 100
}
