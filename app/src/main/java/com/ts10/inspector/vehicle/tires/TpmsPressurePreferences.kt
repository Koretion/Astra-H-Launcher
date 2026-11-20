package com.ts10.inspector.vehicle.tires

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.round

internal data class TpmsPressureSettings(
    val frontTargetBar: Float = 2.3f,
    val rearTargetBar: Float = 2.1f,
    val lowToleranceBar: Float = 0.3f,
    val highToleranceBar: Float = 0.4f,
    val manufacturerValuesConfirmed: Boolean = true,
) {
    val frontMinimumBar: Float get() = roundedBar(frontTargetBar - lowToleranceBar)
    val frontMaximumBar: Float get() = roundedBar(frontTargetBar + highToleranceBar)
    val rearMinimumBar: Float get() = roundedBar(rearTargetBar - lowToleranceBar)
    val rearMaximumBar: Float get() = roundedBar(rearTargetBar + highToleranceBar)
}

internal enum class TpmsPressureLevel {
    NOT_CONFIGURED,
    LOW,
    NORMAL,
    HIGH,
}

internal fun tpmsPressureLevel(
    pressureBar: Float?,
    targetBar: Float,
    lowToleranceBar: Float,
    highToleranceBar: Float,
    configured: Boolean,
): TpmsPressureLevel = when {
    !configured || pressureBar == null -> TpmsPressureLevel.NOT_CONFIGURED
    pressureBar < targetBar - lowToleranceBar -> TpmsPressureLevel.LOW
    pressureBar > targetBar + highToleranceBar -> TpmsPressureLevel.HIGH
    else -> TpmsPressureLevel.NORMAL
}

internal class TpmsPressurePreferences private constructor(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).also { stored ->
        // Apply the photographed fuel-flap values once to upgraded installations too. Subsequent
        // driver edits remain respected because this migration marker is never cleared.
        if (!stored.getBoolean(KEY_FUEL_FLAP_2026_09_08_APPLIED, false)) {
            stored.edit()
                .putFloat(KEY_FRONT_TARGET, DEFAULT_FRONT_BAR)
                .putFloat(KEY_REAR_TARGET, DEFAULT_REAR_BAR)
                .putBoolean(KEY_CONFIRMED, true)
                .putBoolean(KEY_FUEL_FLAP_2026_09_08_APPLIED, true)
                .apply()
        }
    }
    private val mutableState = MutableStateFlow(read())
    val state: StateFlow<TpmsPressureSettings> = mutableState.asStateFlow()

    fun setFrontTargetBar(value: Float) = save(mutableState.value.copy(frontTargetBar = value.validPressure(), manufacturerValuesConfirmed = false))
    fun setRearTargetBar(value: Float) = save(mutableState.value.copy(rearTargetBar = value.validPressure(), manufacturerValuesConfirmed = false))
    fun setLowToleranceBar(value: Float) = save(mutableState.value.copy(lowToleranceBar = value.validTolerance(), manufacturerValuesConfirmed = false))
    fun setHighToleranceBar(value: Float) = save(mutableState.value.copy(highToleranceBar = value.validTolerance(), manufacturerValuesConfirmed = false))
    fun confirmManufacturerValues() = save(mutableState.value.copy(manufacturerValuesConfirmed = true))

    private fun save(settings: TpmsPressureSettings) {
        preferences.edit()
            .putFloat(KEY_FRONT_TARGET, settings.frontTargetBar)
            .putFloat(KEY_REAR_TARGET, settings.rearTargetBar)
            .putFloat(KEY_LOW_TOLERANCE, settings.lowToleranceBar)
            .putFloat(KEY_HIGH_TOLERANCE, settings.highToleranceBar)
            .putBoolean(KEY_CONFIRMED, settings.manufacturerValuesConfirmed)
            .apply()
        mutableState.value = settings
    }

    private fun read() = TpmsPressureSettings(
        frontTargetBar = preferences.getFloat(KEY_FRONT_TARGET, DEFAULT_FRONT_BAR).validPressure(),
        rearTargetBar = preferences.getFloat(KEY_REAR_TARGET, DEFAULT_REAR_BAR).validPressure(),
        lowToleranceBar = preferences.getFloat(KEY_LOW_TOLERANCE, DEFAULT_LOW_TOLERANCE).validTolerance(),
        highToleranceBar = preferences.getFloat(KEY_HIGH_TOLERANCE, DEFAULT_HIGH_TOLERANCE).validTolerance(),
        // The Astra H fuel-flap label was photographed and confirmed on 2026-09-08:
        // normal load, cold tyres: 230 kPa front and 210 kPa rear.
        manufacturerValuesConfirmed = preferences.getBoolean(KEY_CONFIRMED, true),
    )

    private fun Float.validPressure() = roundedBar(coerceIn(1.5f, 4.2f))
    private fun Float.validTolerance() = roundedBar(coerceIn(0.1f, 1.0f))

    companion object {
        private const val FILE_NAME = "tpms_pressure_preferences"
        private const val KEY_FRONT_TARGET = "front_target_bar"
        private const val KEY_REAR_TARGET = "rear_target_bar"
        private const val KEY_LOW_TOLERANCE = "low_tolerance_bar"
        private const val KEY_HIGH_TOLERANCE = "high_tolerance_bar"
        private const val KEY_CONFIRMED = "manufacturer_values_confirmed"
        private const val KEY_FUEL_FLAP_2026_09_08_APPLIED = "fuel_flap_2026_09_08_applied"
        private const val DEFAULT_FRONT_BAR = 2.3f
        private const val DEFAULT_REAR_BAR = 2.1f
        private const val DEFAULT_LOW_TOLERANCE = 0.3f
        private const val DEFAULT_HIGH_TOLERANCE = 0.4f

        @Volatile
        private var instance: TpmsPressurePreferences? = null

        fun get(context: Context): TpmsPressurePreferences = instance ?: synchronized(this) {
            instance ?: TpmsPressurePreferences(context.applicationContext).also { instance = it }
        }
    }
}

private fun roundedBar(value: Float): Float = round(value * 10f) / 10f
