package com.ts10.inspector.vehicle.fuel

import androidx.annotation.StringRes
import com.ts10.inspector.ui.AppLocale
import com.ts10.inspector.R
import android.content.Context
import android.location.Location
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// apiPriceField es el campo de la API española de precios; label es el nombre oficial en los informes.
internal enum class VehicleFuel(val label: String, val apiPriceField: String, @StringRes val text: Int) {
    GASOLINE_95_E5("Gasolina 95 E5", "Precio Gasolina 95 E5", R.string.fueltype_95),
    GASOLINE_98_E5("Gasolina 98 E5", "Precio Gasolina 98 E5", R.string.fueltype_98),
    DIESEL_A("Gasoleo A", "Precio Gasoleo A", R.string.fueltype_diesel),
    DIESEL_PREMIUM("Gasoleo Premium", "Precio Gasoleo Premium", R.string.fueltype_diesel_premium),
}

internal data class FuelStationSuggestion(
    val id: String,
    val name: String,
    val address: String,
    val distanceMeters: Int,
    val pricePerLiter: Float?,
)

internal data class PendingRefuel(
    val detectedAtMillis: Long,
    val estimatedLiters: Float,
    val station: FuelStationSuggestion? = null,
    val lookingUpStation: Boolean = false,
    val lookupMessage: String? = null,
)

internal data class RefuelEntry(
    val timestampMillis: Long,
    val liters: Float,
    val pricePerLiter: Float?,
    val stationName: String?,
    val fuel: VehicleFuel,
    val filledToFull: Boolean,
)

internal data class FuelTrackingState(
    val fuel: VehicleFuel = VehicleFuel.GASOLINE_95_E5,
    val pendingRefuel: PendingRefuel? = null,
    val history: List<RefuelEntry> = emptyList(),
)

/** Persists refuels and enriches detected fills with the nearest official Spanish station price. */
internal object FuelTrackingRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(FuelTrackingState())
    val state: StateFlow<FuelTrackingState> = mutableState.asStateFlow()

    private var context: Context? = null

    // El mensaje se guarda con el repostaje pendiente: se traduce al crearlo.
    private fun t(@StringRes id: Int, vararg args: Any): String =
        context?.let { AppLocale.string(it, id, *args) }.orEmpty()
    private var lastLocation: Location? = null
    private var lastStationLookupElapsedMs = -300_000L

    @Synchronized
    fun start(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        mutableState.value = read()
    }

    @Synchronized
    fun onLocation(location: Location) {
        if (!location.latitude.isFinite() || !location.longitude.isFinite()) return
        lastLocation = Location(location)
        val pending = mutableState.value.pendingRefuel
        val now = SystemClock.elapsedRealtime()
        if (pending != null && pending.station == null && !pending.lookingUpStation &&
            now - lastStationLookupElapsedMs >= STATION_LOOKUP_RETRY_MS) {
            lastStationLookupElapsedMs = now
            val retry = pending.copy(lookingUpStation = true, lookupMessage = null)
            mutableState.value = mutableState.value.copy(pendingRefuel = retry)
            persistPending()
            val snapshot = Location(location)
            val fuel = mutableState.value.fuel
            scope.launch { findOfficialStation(snapshot, fuel, retry.detectedAtMillis) }
        }
    }

    @Synchronized
    fun setFuel(fuel: VehicleFuel) {
        mutableState.value = mutableState.value.copy(fuel = fuel)
        preferences()?.edit()?.putString(KEY_FUEL, fuel.name)?.apply()
    }

    @Synchronized
    fun onRefuelDetected(estimatedLiters: Float) {
        if (estimatedLiters !in MIN_DETECTED_LITERS..MAX_DETECTED_LITERS) return
        val current = mutableState.value.pendingRefuel
        if (current != null) {
            if (estimatedLiters > current.estimatedLiters) {
                mutableState.value = mutableState.value.copy(
                    pendingRefuel = current.copy(estimatedLiters = estimatedLiters),
                )
                persistPending()
            }
            return
        }
        val pending = PendingRefuel(
            detectedAtMillis = System.currentTimeMillis(),
            estimatedLiters = estimatedLiters,
            lookingUpStation = lastLocation != null,
            lookupMessage = if (lastLocation == null) t(R.string.fuel_no_location) else null,
        )
        mutableState.value = mutableState.value.copy(pendingRefuel = pending)
        persistPending()
        val location = lastLocation?.let(::Location) ?: return
        val fuel = mutableState.value.fuel
        lastStationLookupElapsedMs = SystemClock.elapsedRealtime()
        scope.launch { findOfficialStation(location, fuel, pending.detectedAtMillis) }
    }

    @Synchronized
    fun dismissPending() {
        mutableState.value = mutableState.value.copy(pendingRefuel = null)
        persistPending()
    }

    @Synchronized
    fun recordRefuel(
        liters: Float,
        filledToFull: Boolean,
        pricePerLiter: Float?,
        stationName: String?,
    ): Boolean {
        if (liters !in 0.1f..80f || pricePerLiter?.let { it !in 0.1f..5f } == true) return false
        if (!FuelRangeEstimator.recordRefuel(liters, filledToFull)) return false
        val entry = RefuelEntry(
            timestampMillis = System.currentTimeMillis(),
            liters = liters,
            pricePerLiter = pricePerLiter,
            stationName = stationName?.trim()?.takeIf(String::isNotEmpty),
            fuel = mutableState.value.fuel,
            filledToFull = filledToFull,
        )
        mutableState.value = mutableState.value.copy(
            pendingRefuel = null,
            history = (listOf(entry) + mutableState.value.history).take(MAX_HISTORY),
        )
        persistAll()
        return true
    }

    private fun findOfficialStation(location: Location, fuel: VehicleFuel, detectedAtMillis: Long) {
        val result = runCatching { fetchNearestStation(location, fuel) }
        synchronized(this) {
            val pending = mutableState.value.pendingRefuel
                ?.takeIf { it.detectedAtMillis == detectedAtMillis } ?: return
            val station = result.getOrNull()
            mutableState.value = mutableState.value.copy(
                pendingRefuel = pending.copy(
                    station = station,
                    lookingUpStation = false,
                    lookupMessage = when {
                        result.isFailure -> t(R.string.fuel_lookup_unavailable, result.exceptionOrNull()?.message ?: t(R.string.fuel_network_error))
                        station == null -> t(R.string.fuel_no_station)
                        station.pricePerLiter == null -> t(R.string.fuel_station_no_price)
                        else -> t(R.string.fuel_price_suggested)
                    },
                ),
            )
            persistPending()
        }
    }

    private fun fetchNearestStation(location: Location, fuel: VehicleFuel): FuelStationSuggestion? {
        val connection = URL(OFFICIAL_STATIONS_ENDPOINT).openConnection() as HttpURLConnection
        connection.connectTimeout = NETWORK_CONNECT_TIMEOUT_MS
        connection.readTimeout = NETWORK_READ_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "TS10-Astra-Launcher/0.4")
        return try {
            if (connection.responseCode !in 200..299) error("Gasolineras HTTP ${connection.responseCode}")
            val root = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            nearestStation(root.getJSONArray("ListaEESSPrecio"), location, fuel)
        } finally {
            connection.disconnect()
        }
    }

    private fun nearestStation(
        stations: JSONArray,
        location: Location,
        fuel: VehicleFuel,
    ): FuelStationSuggestion? {
        var nearest: FuelStationSuggestion? = null
        for (index in 0 until stations.length()) {
            val item = stations.optJSONObject(index) ?: continue
            val latitude = item.optSpanishDouble("Latitud") ?: continue
            val longitude = item.optSpanishDouble("Longitud (WGS84)") ?: continue
            val distance = distanceMeters(location.latitude, location.longitude, latitude, longitude).toInt()
            if (distance > MAX_STATION_DISTANCE_METERS || distance >= (nearest?.distanceMeters ?: Int.MAX_VALUE)) continue
            nearest = FuelStationSuggestion(
                id = item.optString("IDEESS"),
                name = item.optString("Rotulo").ifBlank { item.optString("Rótulo", t(R.string.fuel_station)) },
                address = listOf(item.optString("Direccion"), item.optString("Municipio"))
                    .filter(String::isNotBlank).joinToString(", ")
                    .ifBlank {
                        listOf(item.optString("Dirección"), item.optString("Municipio"))
                            .filter(String::isNotBlank).joinToString(", ")
                    },
                distanceMeters = distance,
                pricePerLiter = item.optSpanishFloat(fuel.apiPriceField),
            )
        }
        return nearest
    }

    private fun read(): FuelTrackingState {
        val preferences = preferences() ?: return FuelTrackingState()
        val fuel = runCatching {
            VehicleFuel.valueOf(preferences.getString(KEY_FUEL, VehicleFuel.GASOLINE_95_E5.name)!!)
        }.getOrDefault(VehicleFuel.GASOLINE_95_E5)
        return FuelTrackingState(
            fuel = fuel,
            pendingRefuel = preferences.getString(KEY_PENDING, null).toPendingRefuel(),
            history = preferences.getString(KEY_HISTORY, null).toRefuelHistory(),
        )
    }

    private fun persistPending() {
        preferences()?.edit()?.apply {
            val pending = mutableState.value.pendingRefuel
            if (pending == null) remove(KEY_PENDING) else putString(KEY_PENDING, pending.toJson().toString())
        }?.apply()
    }

    private fun persistAll() {
        preferences()?.edit()
            ?.putString(KEY_FUEL, mutableState.value.fuel.name)
            ?.putString(KEY_HISTORY, mutableState.value.history.toJson().toString())
            ?.remove(KEY_PENDING)
            ?.apply()
    }

    private fun preferences() = context?.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)

    private const val PREFERENCES_FILE = "fuel_tracking_preferences"
    private const val KEY_FUEL = "fuel"
    private const val KEY_PENDING = "pending_refuel"
    private const val KEY_HISTORY = "refuel_history"
    private const val MIN_DETECTED_LITERS = 2f
    private const val MAX_DETECTED_LITERS = 80f
    private const val MAX_HISTORY = 100
    private const val MAX_STATION_DISTANCE_METERS = 750
    private const val NETWORK_CONNECT_TIMEOUT_MS = 8_000
    private const val NETWORK_READ_TIMEOUT_MS = 25_000
    private const val STATION_LOOKUP_RETRY_MS = 5 * 60_000L
    private const val OFFICIAL_STATIONS_ENDPOINT =
        "https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes/EstacionesTerrestres/"
}

internal fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadiusMeters = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
        kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
        kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
    return earthRadiusMeters * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
}

private fun JSONObject.optSpanishDouble(key: String): Double? = optString(key)
    .trim().replace(',', '.').toDoubleOrNull()

private fun JSONObject.optSpanishFloat(key: String): Float? = optString(key)
    .trim().replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }

private fun PendingRefuel.toJson() = JSONObject()
    .put("detected_at", detectedAtMillis)
    .put("estimated_liters", estimatedLiters.toDouble())
    .put("station", station?.toJson())
    .put("looking_up", lookingUpStation)
    .put("message", lookupMessage)

private fun FuelStationSuggestion.toJson() = JSONObject()
    .put("id", id).put("name", name).put("address", address)
    .put("distance", distanceMeters).put("price", pricePerLiter?.toDouble())

private fun String?.toPendingRefuel(): PendingRefuel? {
    if (isNullOrBlank()) return null
    return runCatching {
        val json = JSONObject(this)
        val stationJson = json.optJSONObject("station")
        PendingRefuel(
            detectedAtMillis = json.getLong("detected_at"),
            estimatedLiters = json.getDouble("estimated_liters").toFloat(),
            station = stationJson?.let {
                FuelStationSuggestion(
                    id = it.optString("id"), name = it.optString("name"), address = it.optString("address"),
                    distanceMeters = it.optInt("distance"),
                    pricePerLiter = it.optDouble("price", Double.NaN).takeIf(Double::isFinite)?.toFloat(),
                )
            },
            lookingUpStation = false,
            lookupMessage = json.optString("message").takeIf(String::isNotBlank),
        )
    }.getOrNull()
}

private fun List<RefuelEntry>.toJson() = JSONArray().also { array ->
    forEach { entry ->
        array.put(JSONObject()
            .put("timestamp", entry.timestampMillis).put("liters", entry.liters.toDouble())
            .put("price", entry.pricePerLiter?.toDouble()).put("station", entry.stationName)
            .put("fuel", entry.fuel.name).put("full", entry.filledToFull))
    }
}

private fun String?.toRefuelHistory(): List<RefuelEntry> {
    if (isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(this)
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            RefuelEntry(
                timestampMillis = item.getLong("timestamp"),
                liters = item.getDouble("liters").toFloat(),
                pricePerLiter = item.optDouble("price", Double.NaN).takeIf(Double::isFinite)?.toFloat(),
                stationName = item.optString("station").takeIf(String::isNotBlank),
                fuel = runCatching { VehicleFuel.valueOf(item.getString("fuel")) }
                    .getOrDefault(VehicleFuel.GASOLINE_95_E5),
                filledToFull = item.optBoolean("full"),
            )
        }
    }.getOrDefault(emptyList())
}
