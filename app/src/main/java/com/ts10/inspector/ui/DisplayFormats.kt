package com.ts10.inspector.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.settings.DashboardPreferences
import com.ts10.inspector.settings.DashboardSettings
import com.ts10.inspector.settings.DateFormatMode
import com.ts10.inspector.settings.SpeedUnit
import com.ts10.inspector.settings.TemperatureUnit
import com.ts10.inspector.settings.TimeFormatMode
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale
import kotlin.math.roundToInt

/** Unidades y formatos de "Pantalla" ya resueltos (AUTO convertido al valor concreto). */
@Immutable
internal data class DisplayFormats(
    val speedUnit: SpeedUnit = SpeedUnit.KMH,
    val temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    val use24Hour: Boolean = true,
    val dateStyle: DateFormatMode = DateFormatMode.SPANISH,
    val locale: Locale = Locale.getDefault(),
) {
    fun speed(kmh: Number): Int = when (speedUnit) {
        SpeedUnit.KMH -> kmh.toFloat().roundToInt()
        SpeedUnit.MPH -> (kmh.toFloat() * KMH_TO_MPH).roundToInt()
    }

    fun speedText(kmh: Number?, decimals: Int = 0): String =
        kmh?.let { "${number(convertSpeed(it.toFloat()), decimals)} ${speedUnit.label}" } ?: "-- ${speedUnit.label}"

    /** Límite para la señal: en mph se redondea a múltiplos de 5, como las señales reales. */
    fun speedLimit(kmh: Int): Int = when (speedUnit) {
        SpeedUnit.KMH -> kmh
        SpeedUnit.MPH -> ((kmh * KMH_TO_MPH) / 5f).roundToInt() * 5
    }

    fun temperature(celsius: Float): Float = when (temperatureUnit) {
        TemperatureUnit.CELSIUS -> celsius
        TemperatureUnit.FAHRENHEIT -> celsius * 9f / 5f + 32f
    }

    /** "21 °C" / "70 °F"; con [withUnit] = false solo "21°". */
    fun temperatureText(celsius: Number?, decimals: Int = 0, withUnit: Boolean = true): String {
        val suffix = if (withUnit) " ${temperatureUnit.label}" else "°"
        return celsius?.let { number(temperature(it.toFloat()), decimals) + suffix } ?: "--$suffix"
    }

    /**
     * Las temperaturas exteriores llegan ya como texto ("12°", "12.5 °C"): se extrae el número en
     * Celsius y se vuelve a formatear. Si no hay número se devuelve tal cual ("--°").
     */
    fun temperatureLabel(raw: String): String {
        if (temperatureUnit == TemperatureUnit.CELSIUS) return raw
        val celsius = TEMPERATURE_NUMBER.find(raw)?.value?.replace(',', '.')?.toFloatOrNull() ?: return raw
        return number(temperature(celsius), 0) + "°"
    }

    fun time(value: TemporalAccessor): String =
        DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale).format(value)

    /** Fecha numérica: 27/11/2026, 27 Nov 2026 o 11/27/2026. */
    fun date(value: TemporalAccessor, shortYear: Boolean = false): String {
        val year = if (shortYear) "yy" else "yyyy"
        val pattern = when (dateStyle) {
            DateFormatMode.ENGLISH -> "d MMM $year"
            DateFormatMode.AMERICAN -> "MM/dd/$year"
            else -> "dd/MM/$year"
        }
        return DateTimeFormatter.ofPattern(pattern, locale).format(value)
    }

    /** Día del panel principal: solo nombre y número del día ("Viernes 27"), para que se vea grande. */
    fun dashboardDate(value: TemporalAccessor): String =
        DateTimeFormatter.ofPattern("EEEE d", locale).format(value)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }

    /** Día de la semana con hora, para listas de viajes. */
    fun dayAndTime(value: TemporalAccessor): String {
        val day = when (dateStyle) {
            DateFormatMode.AMERICAN -> "EEE, MMM d"
            else -> "EEE d MMM"
        }
        return DateTimeFormatter.ofPattern(day, locale).format(value) + " · " + time(value)
    }

    /** Con mph todo pasa a imperial (coche americano): distancias en millas y consumo en mpg. */
    val imperial: Boolean get() = speedUnit == SpeedUnit.MPH

    val distanceUnit: String get() = if (imperial) "mi" else "km"

    fun distance(km: Number): Double = km.toDouble() * if (imperial) KM_TO_MILES else 1.0

    fun distanceText(km: Number?, decimals: Int = 1): String =
        km?.let { "${number(distance(it).toFloat(), decimals)} $distanceUnit" } ?: "-- $distanceUnit"

    /** L/100 km, o mpg (EE. UU.) en imperial. */
    fun consumption(litersPer100Km: Float): Float =
        if (imperial) (if (litersPer100Km > 0f) MPG_US_FACTOR / litersPer100Km else 0f) else litersPer100Km

    fun consumptionText(litersPer100Km: Number?, decimals: Int = 1): String =
        litersPer100Km?.let { "${number(consumption(it.toFloat()), decimals)} ${consumptionUnit()}" } ?: "--"

    fun consumptionUnit(): String = if (imperial) "mpg" else "L/100"

    /** Adapta textos traducidos que llevan "km" o "L/100" a las unidades elegidas. */
    fun unitText(text: String): String = if (!imperial) text else
        text.replace("L/100", "MPG").replace(KM_WORD, "mi")

    private fun convertSpeed(kmh: Float) = if (speedUnit == SpeedUnit.MPH) kmh * KMH_TO_MPH else kmh

    private fun number(value: Float, decimals: Int) = String.format(locale, "%.${decimals}f", value)

    companion object {
        const val KMH_TO_MPH = 0.621371f
        const val KM_TO_MILES = 0.621371
        const val MPG_US_FACTOR = 235.215f
        private val KM_WORD = Regex("\\bkm\\b")
        private val TEMPERATURE_NUMBER = Regex("-?\\d+(?:[.,]\\d+)?")

        fun resolve(settings: DashboardSettings, locale: Locale, system24Hour: Boolean) = DisplayFormats(
            speedUnit = settings.speedUnit,
            temperatureUnit = settings.temperatureUnit,
            use24Hour = when (settings.timeFormat) {
                TimeFormatMode.AUTO -> system24Hour
                TimeFormatMode.H12 -> false
                TimeFormatMode.H24 -> true
            },
            dateStyle = when (settings.dateFormat) {
                DateFormatMode.AUTO -> when {
                    locale.country == "US" -> DateFormatMode.AMERICAN
                    locale.language == "en" -> DateFormatMode.ENGLISH
                    else -> DateFormatMode.SPANISH
                }
                else -> settings.dateFormat
            },
            locale = locale,
        )

        fun current(context: Context): DisplayFormats = resolve(
            DashboardPreferences.current(context),
            context.resources.configuration.locales[0],
            android.text.format.DateFormat.is24HourFormat(context),
        )
    }
}

@Composable
internal fun rememberDisplayFormats(): DisplayFormats {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val settings by remember(context) { DashboardPreferences.get(context) }.state.collectAsStateWithLifecycle()
    val system24Hour = android.text.format.DateFormat.is24HourFormat(context)
    return remember(settings.speedUnit, settings.temperatureUnit, settings.timeFormat, settings.dateFormat, locale, system24Hour) {
        DisplayFormats.resolve(settings, locale, system24Hour)
    }
}
