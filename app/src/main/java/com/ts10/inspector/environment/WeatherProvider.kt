package com.ts10.inspector.environment

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.os.SystemClock
import com.ts10.inspector.settings.DashboardPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class WeatherForecastDay(
    val date: String,
    val code: Int,
    val maximumCelsius: Double,
    val minimumCelsius: Double,
    val precipitationProbability: Int,
)

data class CurrentWeather(
    val code: Int,
    val temperatureCelsius: Double,
    val locationName: String? = null,
    val apparentTemperatureCelsius: Double? = null,
    val humidityPercent: Int? = null,
    val windSpeedKmh: Double? = null,
    val forecast: List<WeatherForecastDay> = emptyList(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

class WeatherProvider(
    private val context: Context,
    private val onWeather: (CurrentWeather) -> Unit,
    private val onError: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var request: Job? = null
    @Volatile private var retry: Job? = null
    @Volatile private var lastLocation: Location? = null
    @Volatile private var lastSuccessfulRequestElapsedMs = 0L
    @Volatile private var lastAttemptElapsedMs = 0L

    init {
        restoreCachedWeather()?.let(onWeather)
    }

    @Synchronized
    fun refresh(latitude: Double, longitude: Double) {
        if (!DashboardPreferences.current(context).onlineWeatherEnabled) return
        val location = Location("weather").apply {
            this.latitude = latitude
            this.longitude = longitude
        }
        val now = SystemClock.elapsedRealtime()
        val movedEnough = lastLocation?.distanceTo(location)?.let { it >= MIN_DISTANCE_METERS } ?: true
        if (!movedEnough && now - lastSuccessfulRequestElapsedMs < REFRESH_INTERVAL_MS) return
        if (now - lastAttemptElapsedMs < FAILURE_RETRY_INTERVAL_MS) return
        if (request?.isActive == true) return
        lastAttemptElapsedMs = now
        request = scope.launch {
            runCatching { fetch(latitude, longitude) }
                .onSuccess { weather ->
                    retry?.cancel()
                    retry = null
                    lastLocation = location
                    lastSuccessfulRequestElapsedMs = SystemClock.elapsedRealtime()
                    cache(weather, weather.updatedAtMillis)
                    onWeather(weather)
                }
                .onFailure { error ->
                    onError(error.message ?: error.javaClass.simpleName)
                    scheduleRetry(latitude, longitude)
                }
        }
    }

    private fun scheduleRetry(latitude: Double, longitude: Double) {
        if (retry?.isActive == true) return
        retry = scope.launch {
            kotlinx.coroutines.delay(FAILURE_RETRY_INTERVAL_MS)
            retry = null
            refresh(latitude, longitude)
        }
    }

    private fun fetch(latitude: Double, longitude: Double): CurrentWeather {
        val endpoint = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=5",
            latitude,
            longitude,
        )
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "TS10-Astra-Launcher/0.1")
        return try {
            if (connection.responseCode !in 200..299) error("Weather HTTP ${connection.responseCode}")
            val root = connection.inputStream.bufferedReader().use { reader ->
                JSONObject(reader.readText())
            }
            val current = root.getJSONObject("current")
            val daily = root.getJSONObject("daily")
            val dates = daily.getJSONArray("time")
            val codes = daily.getJSONArray("weather_code")
            val maximums = daily.getJSONArray("temperature_2m_max")
            val minimums = daily.getJSONArray("temperature_2m_min")
            val precipitation = daily.getJSONArray("precipitation_probability_max")
            val dayCount = minOf(dates.length(), codes.length(), maximums.length(), minimums.length(), precipitation.length())
            CurrentWeather(
                code = current.getInt("weather_code"),
                temperatureCelsius = current.getDouble("temperature_2m"),
                locationName = resolveLocationName(latitude, longitude),
                apparentTemperatureCelsius = current.optDoubleOrNull("apparent_temperature"),
                humidityPercent = current.optIntOrNull("relative_humidity_2m"),
                windSpeedKmh = current.optDoubleOrNull("wind_speed_10m"),
                forecast = List(dayCount) { index ->
                    WeatherForecastDay(
                        date = dates.getString(index),
                        code = codes.getInt(index),
                        maximumCelsius = maximums.getDouble(index),
                        minimumCelsius = minimums.getDouble(index),
                        precipitationProbability = precipitation.optInt(index, 0),
                    )
                },
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun cache(weather: CurrentWeather, timestamp: Long) {
        context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CODE, weather.code)
            .putLong(KEY_TEMPERATURE_BITS, weather.temperatureCelsius.toBits())
            .putString(KEY_LOCATION_NAME, weather.locationName)
            .putLong(KEY_APPARENT_TEMPERATURE_BITS, weather.apparentTemperatureCelsius?.toBits() ?: NULL_DOUBLE_BITS)
            .putInt(KEY_HUMIDITY, weather.humidityPercent ?: NULL_INT)
            .putLong(KEY_WIND_SPEED_BITS, weather.windSpeedKmh?.toBits() ?: NULL_DOUBLE_BITS)
            .putString(KEY_FORECAST, weather.forecast.toJson().toString())
            .putLong(KEY_TIMESTAMP, timestamp)
            .apply()
    }

    private fun restoreCachedWeather(): CurrentWeather? {
        val preferences = context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE)
        val timestamp = preferences.getLong(KEY_TIMESTAMP, 0L)
        if (System.currentTimeMillis() - timestamp !in 0..CACHE_MAX_AGE_MS) return null
        if (!preferences.contains(KEY_CODE) || !preferences.contains(KEY_TEMPERATURE_BITS)) return null
        return CurrentWeather(
            code = preferences.getInt(KEY_CODE, 0),
            temperatureCelsius = Double.fromBits(preferences.getLong(KEY_TEMPERATURE_BITS, 0L)),
            locationName = preferences.getString(KEY_LOCATION_NAME, null),
            apparentTemperatureCelsius = preferences.getLong(KEY_APPARENT_TEMPERATURE_BITS, NULL_DOUBLE_BITS)
                .takeUnless { it == NULL_DOUBLE_BITS }
                ?.let(Double::fromBits),
            humidityPercent = preferences.getInt(KEY_HUMIDITY, NULL_INT).takeUnless { it == NULL_INT },
            windSpeedKmh = preferences.getLong(KEY_WIND_SPEED_BITS, NULL_DOUBLE_BITS)
                .takeUnless { it == NULL_DOUBLE_BITS }
                ?.let(Double::fromBits),
            forecast = preferences.getString(KEY_FORECAST, null).toForecast(),
            updatedAtMillis = timestamp,
        )
    }

    @Suppress("DEPRECATION")
    private fun resolveLocationName(latitude: Double, longitude: Double): String? {
        if (!Geocoder.isPresent()) return null
        return runCatching {
            val address = Geocoder(context, Locale("es", "ES"))
                .getFromLocation(latitude, longitude, 1)
                ?.firstOrNull()
            sequenceOf(
                address?.locality,
                address?.subAdminArea,
                address?.adminArea,
                address?.countryName,
            ).firstOrNull { !it.isNullOrBlank() }
                ?.trim()
        }.getOrNull()
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L
        const val FAILURE_RETRY_INTERVAL_MS = 60 * 1000L
        const val CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        const val MIN_DISTANCE_METERS = 10_000f
        const val TIMEOUT_MS = 8_000
        const val CACHE_FILE = "weather_cache"
        const val KEY_CODE = "weather_code"
        const val KEY_TEMPERATURE_BITS = "temperature_bits"
        const val KEY_LOCATION_NAME = "location_name"
        const val KEY_APPARENT_TEMPERATURE_BITS = "apparent_temperature_bits"
        const val KEY_HUMIDITY = "humidity"
        const val KEY_WIND_SPEED_BITS = "wind_speed_bits"
        const val KEY_FORECAST = "forecast"
        const val KEY_TIMESTAMP = "timestamp"
        const val NULL_DOUBLE_BITS = Long.MIN_VALUE
        const val NULL_INT = Int.MIN_VALUE
    }
}

private fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (has(name) && !isNull(name)) optDouble(name) else null

private fun JSONObject.optIntOrNull(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null

private fun List<WeatherForecastDay>.toJson() = JSONArray().also { array ->
    forEach { day ->
        array.put(
            JSONObject()
                .put("date", day.date)
                .put("code", day.code)
                .put("maximum", day.maximumCelsius)
                .put("minimum", day.minimumCelsius)
                .put("precipitation", day.precipitationProbability),
        )
    }
}

private fun String?.toForecast(): List<WeatherForecastDay> {
    if (this.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(this)
        List(array.length()) { index ->
            val day = array.getJSONObject(index)
            WeatherForecastDay(
                date = day.getString("date"),
                code = day.getInt("code"),
                maximumCelsius = day.getDouble("maximum"),
                minimumCelsius = day.getDouble("minimum"),
                precipitationProbability = day.optInt("precipitation", 0),
            )
        }
    }.getOrDefault(emptyList())
}
