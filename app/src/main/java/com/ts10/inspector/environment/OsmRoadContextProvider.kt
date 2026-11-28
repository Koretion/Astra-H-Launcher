package com.ts10.inspector.environment

import com.ts10.inspector.ui.uiText
import com.ts10.inspector.ui.fail
import com.ts10.inspector.ui.UiText
import com.ts10.inspector.R
import android.content.Context
import android.location.Location
import com.ts10.inspector.BuildConfig
import android.os.SystemClock
import com.ts10.inspector.vehicle.fuel.distanceMeters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.StringReader
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

internal enum class RoadLineStyle {
    UNKNOWN,
    NONE,
    DASHED,
    SOLID,
    DOUBLE_SOLID,
}

internal data class OsmRoadContext(
    val wayId: Long,
    val name: String?,
    val reference: String?,
    val highway: String,
    val maxSpeedKmh: Int?,
    val lanesInTravelDirection: Int?,
    val lanesEstimated: Boolean,
    val oneway: Boolean,
    val isRoundabout: Boolean,
    val centerLineStyle: RoadLineStyle,
    val laneLineStyles: List<RoadLineStyle>,
    val distanceMeters: Double,
    val headingDifferenceDegrees: Double?,
    val curvature: Float,
    val tags: Map<String, String>,
)

internal data class OsmRoadContextState(
    val road: OsmRoadContext? = null,
    val status: UiText = UiText.of(R.string.osm_waiting_gps),
    val lastError: UiText? = null,
    val cachedWays: Int = 0,
    val queryInProgress: Boolean = false,
    val lastQueryElapsedMs: Long? = null,
    val lastSuccessElapsedMs: Long? = null,
    val lastSource: String? = null,
)

internal data class OsmCoordinate(val latitude: Double, val longitude: Double)

internal data class OsmRoadWay(
    val id: Long,
    val tags: Map<String, String>,
    val geometry: List<OsmCoordinate>,
)

/**
 * Rate-limited OpenStreetMap road context. Public Overpass is only used to refresh a compact
 * local envelope; every GPS fix is matched against the in-memory geometry without network I/O.
 */
internal object OsmRoadContextProvider {
    private val OVERPASS_URLS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )
    // Requesting full geometry for every road in 1.4 km plus every speed-tagged road in 3.5 km
    // made dense-city Overpass replies huge. The current road already carries its maxspeed tag,
    // so one compact envelope is both faster and sufficient for matching and speed limits.
    // The 550 m query repeatedly timed out on the TS10 in dense areas, leaving an empty cache and
    // therefore no speed sign or lane layout. Keep the live envelope deliberately compact.
    private const val ROAD_CONTEXT_RADIUS_METERS = 300
    private const val REFRESH_DISTANCE_METERS = 140f
    private const val MIN_QUERY_INTERVAL_MS = 20_000L
    private const val CACHE_MAX_AGE_MS = 12 * 60 * 60 * 1_000L
    private const val MAX_MATCH_DISTANCE_METERS = 65.0
    private const val TRANSIENT_MISS_HOLD_MS = 12_000L
    private const val CACHE_PREFERENCES = "osm_road_context"
    private const val KEY_JSON = "overpass_json"
    private const val CACHE_FILE = "osm_road_cache.json"
    private const val KEY_LATITUDE = "latitude"
    private const val KEY_LONGITUDE = "longitude"
    private const val KEY_SAVED_AT = "saved_at"
    private const val KEY_CACHE_SCHEMA = "schema"
    private const val CACHE_SCHEMA = 3
    private const val OSM_FALLBACK_RADIUS_METERS = 120
    private const val MAX_RESPONSE_CHARS = 8 * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(OsmRoadContextState())
    val state: StateFlow<OsmRoadContextState> = mutableState.asStateFlow()

    private var applicationContext: Context? = null
    private var onRoadChanged: ((OsmRoadContext?) -> Unit)? = null
    private var cachedWays: List<OsmRoadWay> = emptyList()
    private var cacheCenter: Location? = null
    private var lastQueryElapsedMs = -MIN_QUERY_INTERVAL_MS
    private var queryJob: Job? = null
    private var lastMatchedWayId: Long? = null
    private var latestRoadLocation: Location? = null
    private var lastMatchElapsedMs = 0L
    private var nextOverpassUrlIndex = 0
    private var cacheRestoreStarted = false
    private val stabilizer = OsmRoadStabilizer()

    private data class RoadEnvelope(
        val ways: List<OsmRoadWay>,
        val cacheJson: String,
        val source: String,
        val overpassError: UiText? = null,
    )


    fun start(context: Context, onRoadChanged: (OsmRoadContext?) -> Unit) {
        applicationContext = context.applicationContext
        this.onRoadChanged = onRoadChanged
        if (cacheRestoreStarted) return
        cacheRestoreStarted = true
        // La cache puede ocupar varios MB: leerla y parsearla fuera del hilo principal.
        scope.launch { restoreCache(context.applicationContext) }
    }

    @Synchronized
    fun onLocation(location: Location) {
        val now = SystemClock.elapsedRealtime()
        if (!isUsableRoadFix(location.accuracy.takeIf { location.hasAccuracy() },
                now - location.elapsedRealtimeNanos / 1_000_000)) {
            val previous = latestRoadLocation
            if (previous == null || now - previous.elapsedRealtimeNanos / 1_000_000 > 10_000) {
                onLocationUnavailable()
            }
            return
        }
        // Network callbacks can arrive after a newer GPS fix.
        if (latestRoadLocation?.let { it.elapsedRealtimeNanos > location.elapsedRealtimeNanos } == true) return
        val snapshot = Location(location)
        latestRoadLocation = snapshot
        val match = matchOsmRoad(
            ways = cachedWays,
            latitude = snapshot.latitude,
            longitude = snapshot.longitude,
            bearingDegrees = snapshot.bearing.takeIf { snapshot.hasBearing() && snapshot.speed >= 2f },
            previousWayId = lastMatchedWayId,
            maxDistanceMeters = MAX_MATCH_DISTANCE_METERS,
        )
        val moved = cacheCenter?.distanceTo(snapshot) ?: Float.MAX_VALUE
        publishMatch(match, now)
        val needsRefresh = cachedWays.isEmpty() || moved >= REFRESH_DISTANCE_METERS || match == null
        if (needsRefresh && queryJob?.isActive != true && now - lastQueryElapsedMs >= MIN_QUERY_INTERVAL_MS) {
            lastQueryElapsedMs = now
            queryJob = scope.launch { refresh(snapshot) }
        }
    }

    @Synchronized
    fun clear() {
        latestRoadLocation = null
        stabilizer.reset()
        lastMatchedWayId = null
        lastMatchElapsedMs = 0L
        publish(null, UiText.of(R.string.osm_stopped))
    }

    @Synchronized
    fun onLocationUnavailable() {
        latestRoadLocation = null
        stabilizer.reset()
        lastMatchedWayId = null
        lastMatchElapsedMs = 0L
        publish(null, UiText.of(R.string.osm_no_gps))
    }

    private fun refresh(location: Location) {
        mutableState.value = mutableState.value.copy(
            status = if (mutableState.value.road == null) UiText.of(R.string.osm_querying) else UiText.of(R.string.osm_updating_cache),
            queryInProgress = true,
            lastQueryElapsedMs = SystemClock.elapsedRealtime(),
        )
        runCatching {
            val envelope = requestWays(location.latitude, location.longitude)
            val ways = envelope.ways
            if (ways.isEmpty()) fail(R.string.osm_no_ways)
            persistCache(envelope.cacheJson, location)
            synchronized(this) {
                cachedWays = ways
                cacheCenter = Location(location)
                mutableState.value = mutableState.value.copy(
                    cachedWays = ways.size,
                    queryInProgress = false,
                    lastSuccessElapsedMs = SystemClock.elapsedRealtime(),
                    lastError = envelope.overpassError,
                    lastSource = envelope.source,
                )
                val latest = latestRoadLocation ?: return@synchronized
                val now = SystemClock.elapsedRealtime()
                if (!isUsableRoadFix(latest.accuracy.takeIf { latest.hasAccuracy() },
                        now - latest.elapsedRealtimeNanos / 1_000_000)) {
                    onLocationUnavailable()
                    return@synchronized
                }
                // Overpass can take tens of seconds. Match its geometry at the current
                // position, never at the position where the request started.
                publishMatch(matchOsmRoad(
                    ways, latest.latitude, latest.longitude,
                    latest.bearing.takeIf { latest.hasBearing() && latest.speed >= 2f },
                    lastMatchedWayId, MAX_MATCH_DISTANCE_METERS,
                ), now)
            }
        }.onFailure { error ->
            mutableState.value = mutableState.value.copy(
                status = if (mutableState.value.road == null) UiText.of(R.string.osm_unavailable) else UiText.of(R.string.osm_using_cache),
                lastError = error.uiText(),
                queryInProgress = false,
            )
        }
    }

    private fun publishMatch(road: OsmRoadContext?, nowElapsedMs: Long) {
        if (road != null) {
            lastMatchElapsedMs = nowElapsedMs
            publish(stabilizer.stabilize(road, nowElapsedMs), UiText.of(R.string.osm_road_found))
            return
        }
        // A single imprecise GPS fix or a junction must not make the sign and lane data flicker.
        // Keep the last match briefly while a new envelope is fetched, then clear it if the miss
        // is sustained so stale road limits cannot remain indefinitely.
        if (mutableState.value.road != null && nowElapsedMs - lastMatchElapsedMs <= TRANSIENT_MISS_HOLD_MS) {
            mutableState.value = mutableState.value.copy(status = UiText.of(R.string.osm_keeping_road))
        } else {
            stabilizer.reset()
            publish(null, UiText.of(R.string.osm_searching))
        }
    }

    private fun publish(road: OsmRoadContext?, status: UiText) {
        if (road != null) lastMatchedWayId = road.wayId
        val previous = mutableState.value.road
        mutableState.value = mutableState.value.copy(road = road, status = status)
        val relevantChange = previous?.wayId != road?.wayId ||
            previous?.maxSpeedKmh != road?.maxSpeedKmh ||
            previous?.lanesInTravelDirection != road?.lanesInTravelDirection ||
            previous?.isRoundabout != road?.isRoundabout
        if (relevantChange) onRoadChanged?.invoke(road)
    }

    private fun requestWays(latitude: Double, longitude: Double): RoadEnvelope {
        val query = buildOsmRoadQuery(
            latitude,
            longitude,
            ROAD_CONTEXT_RADIUS_METERS,
        )
        val body = "data=" + URLEncoder.encode(query, Charsets.UTF_8.name())
        // One rotating Overpass mirror per refresh avoids chaining three long timeouts. If it
        // fails, the official OSM map API supplies a deliberately small local envelope.
        val index = nextOverpassUrlIndex % OVERPASS_URLS.size
        nextOverpassUrlIndex = (index + 1) % OVERPASS_URLS.size
        val overpass = runCatching { requestWaysFrom(OVERPASS_URLS[index], body) }
        overpass.getOrNull()?.let { response ->
            val ways = parseOsmRoadResponse(response)
            if (ways.isNotEmpty()) return RoadEnvelope(ways, response, "Overpass")
        }
        val overpassError = overpass.exceptionOrNull()?.uiText() ?: UiText.of(R.string.osm_no_ways_response)
        val ways = requestWaysFromOsmMapApi(latitude, longitude, OSM_FALLBACK_RADIUS_METERS)
        return RoadEnvelope(
            ways = ways,
            cacheJson = serializeOsmRoadWays(ways),
            source = "OSM API fallback",
            overpassError = UiText.of(R.string.osm_overpass_fallback, overpassError),
        )
    }

    private fun requestWaysFrom(url: String, body: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4_000
            readTimeout = 4_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty(
                "User-Agent",
                "AstraTS10Launcher/${BuildConfig.VERSION_NAME} (OSM road context)",
            )
        }
        return try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.readUtf8Bounded(MAX_RESPONSE_CHARS).orEmpty()
            if (code !in 200..299) fail(R.string.osm_http_error, "Overpass", code)
            response
        } finally {
            connection.disconnect()
        }
    }

    private fun requestWaysFromOsmMapApi(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int,
    ): List<OsmRoadWay> {
        val latitudeDelta = radiusMeters / 111_320.0
        val longitudeDelta = radiusMeters / (111_320.0 * cos(Math.toRadians(latitude)).coerceAtLeast(0.2))
        val bbox = listOf(
            longitude - longitudeDelta,
            latitude - latitudeDelta,
            longitude + longitudeDelta,
            latitude + latitudeDelta,
        ).joinToString(",") { formatCoordinate(it) }
        val connection = (URL("https://api.openstreetmap.org/api/0.6/map?bbox=$bbox")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/xml")
            setRequestProperty(
                "User-Agent",
                "AstraTS10Launcher/${BuildConfig.VERSION_NAME} (OSM road context fallback)",
            )
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) fail(R.string.osm_http_error, "OSM API", code)
            parseOsmMapResponse(connection.inputStream.readUtf8Bounded(MAX_RESPONSE_CHARS))
        } finally {
            connection.disconnect()
        }
    }

    private fun cacheFile(context: Context) = File(context.filesDir, CACHE_FILE)

    private fun persistCache(json: String, location: Location) {
        val context = applicationContext ?: return
        // El JSON va a un archivo: en SharedPreferences cada apply() reescribe todo el XML.
        val target = cacheFile(context)
        val temporary = File(target.path + ".tmp")
        if (runCatching { temporary.writeText(json, Charsets.UTF_8) }.isFailure ||
            !temporary.renameTo(target)
        ) {
            temporary.delete()
            return
        }
        context.getSharedPreferences(CACHE_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_JSON)
            .putLong(KEY_LATITUDE, location.latitude.toRawBits())
            .putLong(KEY_LONGITUDE, location.longitude.toRawBits())
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .putInt(KEY_CACHE_SCHEMA, CACHE_SCHEMA)
            .apply()
    }

    private fun restoreCache(context: Context) {
        val preferences = context.getSharedPreferences(CACHE_PREFERENCES, Context.MODE_PRIVATE)
        // Versiones anteriores guardaban el JSON dentro de las preferencias.
        if (preferences.contains(KEY_JSON)) preferences.edit().remove(KEY_JSON).apply()
        val age = System.currentTimeMillis() - preferences.getLong(KEY_SAVED_AT, 0L)
        if (age !in 0..CACHE_MAX_AGE_MS) return
        if (preferences.getInt(KEY_CACHE_SCHEMA, 0) != CACHE_SCHEMA) return
        val json = runCatching { cacheFile(context).readText(Charsets.UTF_8) }.getOrNull() ?: return
        val ways = runCatching { parseOsmRoadResponse(json) }.getOrDefault(emptyList())
        if (ways.isEmpty()) return
        synchronized(this) {
            // Una consulta en vivo terminada mientras se leia la cache tiene prioridad.
            if (cachedWays.isNotEmpty()) return
            cachedWays = ways
            cacheCenter = Location("osm-cache").apply {
                latitude = Double.fromBits(preferences.getLong(KEY_LATITUDE, 0L))
                longitude = Double.fromBits(preferences.getLong(KEY_LONGITUDE, 0L))
            }
            mutableState.value = OsmRoadContextState(status = UiText.of(R.string.osm_cache_ready))
                .copy(cachedWays = ways.size, lastSource = "Cache local")
        }
    }
}

private fun InputStream.readUtf8Bounded(maxChars: Int): String =
    bufferedReader(Charsets.UTF_8).use { reader ->
        val result = StringBuilder(minOf(maxChars, 64 * 1024))
        val buffer = CharArray(8 * 1024)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            if (result.length + read > maxChars) fail(R.string.osm_response_too_large)
            result.append(buffer, 0, read)
        }
        result.toString()
    }

internal fun buildOsmRoadQuery(
    latitude: Double,
    longitude: Double,
    roadContextRadiusMeters: Int,
): String = """
    [out:json][timeout:8][maxsize:8388608];
    way(around:$roadContextRadiusMeters,${formatCoordinate(latitude)},${formatCoordinate(longitude)})
      ["highway"]["highway"!~"^(footway|path|cycleway|pedestrian|steps|bridleway|corridor|platform)$"];
    out tags geom;
""".trimIndent()

internal fun parseOsmRoadResponse(json: String): List<OsmRoadWay> {
    val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
    return buildList {
        for (index in 0 until elements.length()) {
            val element = elements.optJSONObject(index) ?: continue
            if (element.optString("type") != "way") continue
            val tagsJson = element.optJSONObject("tags") ?: continue
            val highway = tagsJson.optString("highway").takeIf(String::isNotBlank) ?: continue
            if (!isDrivableHighway(highway)) continue
            val tags = buildMap {
                val keys = tagsJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, tagsJson.optString(key))
                }
            }
            if (tags["access"] in setOf("no", "private") || tags["motor_vehicle"] in setOf("no", "private")) continue
            val geometryJson = element.optJSONArray("geometry") ?: continue
            val geometry = buildList {
                for (pointIndex in 0 until geometryJson.length()) {
                    val point = geometryJson.optJSONObject(pointIndex) ?: continue
                    add(OsmCoordinate(point.optDouble("lat"), point.optDouble("lon")))
                }
            }
            if (geometry.size >= 2) add(OsmRoadWay(element.optLong("id"), tags, geometry))
        }
    }
}

/** Parses the compact XML returned by the official OSM map API fallback. */
internal fun parseOsmMapResponse(xml: String): List<OsmRoadWay> {
    val nodes = HashMap<Long, OsmCoordinate>()
    val roads = ArrayList<OsmRoadWay>()
    var wayId: Long? = null
    var nodeRefs = ArrayList<Long>()
    var tags = LinkedHashMap<String, String>()
    val handler = object : DefaultHandler() {
        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            when (qName) {
                "node" -> {
                    val id = attributes.getValue("id")?.toLongOrNull()
                    val latitude = attributes.getValue("lat")?.toDoubleOrNull()
                    val longitude = attributes.getValue("lon")?.toDoubleOrNull()
                    if (id != null && latitude != null && longitude != null) {
                        nodes[id] = OsmCoordinate(latitude, longitude)
                    }
                }
                "way" -> {
                    wayId = attributes.getValue("id")?.toLongOrNull()
                    nodeRefs = ArrayList()
                    tags = LinkedHashMap()
                }
                "nd" -> if (wayId != null) {
                    attributes.getValue("ref")?.toLongOrNull()?.let(nodeRefs::add)
                }
                "tag" -> if (wayId != null) {
                    val key = attributes.getValue("k")
                    val value = attributes.getValue("v")
                    if (key != null && value != null) tags[key] = value
                }
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            if (qName == "way") {
                val id = wayId
                val highway = tags["highway"]
                val geometry = nodeRefs.mapNotNull(nodes::get)
                if (id != null && highway != null && isDrivableHighway(highway) && geometry.size >= 2 &&
                    tags["access"] !in setOf("no", "private") &&
                    tags["motor_vehicle"] !in setOf("no", "private")) {
                    roads += OsmRoadWay(id, tags.toMap(), geometry)
                }
                wayId = null
            }
        }
    }
    val factory = SAXParserFactory.newInstance().apply {
        isNamespaceAware = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }
    factory.newSAXParser().xmlReader.apply { contentHandler = handler }
        .parse(InputSource(StringReader(xml)))
    return roads
}

/** Stores fallback data in the same bounded JSON shape already understood by the cache. */
internal fun serializeOsmRoadWays(ways: List<OsmRoadWay>): String = JSONObject()
    .put("elements", JSONArray().apply {
        ways.forEach { way ->
            put(JSONObject()
                .put("type", "way")
                .put("id", way.id)
                .put("tags", JSONObject(way.tags))
                .put("geometry", JSONArray().apply {
                    way.geometry.forEach { coordinate ->
                        put(JSONObject().put("lat", coordinate.latitude).put("lon", coordinate.longitude))
                    }
                }))
        }
    })
    .toString()

internal fun matchOsmRoad(
    ways: List<OsmRoadWay>,
    latitude: Double,
    longitude: Double,
    bearingDegrees: Float?,
    previousWayId: Long?,
    maxDistanceMeters: Double,
): OsmRoadContext? {
    data class Candidate(
        val way: OsmRoadWay,
        val distance: Double,
        val forward: Boolean,
        val headingDifference: Double?,
        val score: Double,
        val segmentIndex: Int,
    )

    fun Candidate.directionalMaxSpeedKmh(): Int? {
        val direction = if (forward) "forward" else "backward"
        return parseMaxSpeedKmh(way.tags["maxspeed:$direction"] ?: way.tags["maxspeed"])
    }

    val previousWay = previousWayId?.let { id -> ways.firstOrNull { it.id == id } }

    /** La vía anterior, otro tramo de la misma carretera o uno que empieza donde acaba aquella. */
    fun continuityBonus(way: OsmRoadWay): Double {
        if (previousWay == null) return 0.0
        if (way.id == previousWay.id) return SAME_WAY_BONUS
        val sameRoad = samePhysicalRoad(previousWay.tags, way.tags)
        val connected = waysTouch(previousWay.geometry, way.geometry)
        return when {
            sameRoad && connected -> CONNECTED_SAME_ROAD_BONUS
            sameRoad -> SAME_ROAD_BONUS
            connected -> CONNECTED_BONUS
            else -> 0.0
        }
    }

    val candidates = ways.mapNotNull { way ->
        val nearest = nearestSegment(way.geometry, latitude, longitude) ?: return@mapNotNull null
        if (nearest.distanceMeters > maxDistanceMeters) return@mapNotNull null
        val headingDifference = bearingDegrees?.let { angularDifference(it.toDouble(), nearest.bearingDegrees) }
        val reverseDifference = bearingDegrees?.let { angularDifference(it.toDouble(), normalizeBearing(nearest.bearingDegrees + 180.0)) }
        val forward = when {
            headingDifference == null || reverseDifference == null -> way.tags["oneway"] != "-1"
            headingDifference <= reverseDifference -> true
            else -> false
        }
        val travelDifference = if (forward) headingDifference else reverseDifference
        if (travelDifference != null && travelDifference > 50.0) return@mapNotNull null
        val oneway = effectiveOneway(way.tags)
        if (bearingDegrees != null && oneway != 0 && ((oneway > 0) != forward)) {
            return@mapNotNull null
        }
        val headingPenalty = travelDifference?.let { min(it, 90.0) * 0.22 } ?: 0.0
        val classPenalty = highwayMatchPenalty(way.tags["highway"])
        Candidate(
            way,
            nearest.distanceMeters,
            forward,
            travelDifference,
            nearest.distanceMeters + headingPenalty + classPenalty - continuityBonus(way),
            nearest.segmentIndex,
        )
    }
    val selected = candidates.minByOrNull(Candidate::score) ?: return null
    // Parallel roads with different limits cannot be distinguished from this fix.
    // Keep the road geometry but suppress the sign instead of guessing a limit.
    val selectedSpeed = selected.directionalMaxSpeedKmh()
    // OSM commonly splits one physical road into adjacent ways at junctions. A short selected
    // segment may omit maxspeed even though another nearby segment with the same ref/name has it.
    val inheritedSpeed = selectedSpeed ?: candidates
        .filter { other ->
            other.way.id != selected.way.id &&
                samePhysicalRoad(selected.way.tags, other.way.tags)
        }
        .mapNotNull(Candidate::directionalMaxSpeedKmh)
        .distinct()
        .singleOrNull()
    val effectiveSpeed = selectedSpeed ?: inheritedSpeed
    // Siguiendo la misma carretera no se duda: un ramal o una vía paralela cercana con otro límite
    // apagaba la señal en cada enlace aunque el coche no hubiera salido de la vía.
    val continuing = previousWay != null &&
        (selected.way.id == previousWay.id || samePhysicalRoad(previousWay.tags, selected.way.tags))
    val ambiguous = !continuing && effectiveSpeed != null && candidates.any { other ->
        val otherSpeed = other.directionalMaxSpeedKmh()
        other.way.id != selected.way.id && abs(other.score - selected.score) < 8.0 &&
            otherSpeed != null && !samePhysicalRoad(selected.way.tags, other.way.tags) &&
            otherSpeed != effectiveSpeed
    }
    return selected.way.toRoadContext(
        forward = selected.forward,
        distanceMeters = selected.distance,
        headingDifferenceDegrees = selected.headingDifference,
        segmentIndex = selected.segmentIndex,
    ).let {
        when {
            ambiguous -> it.copy(maxSpeedKmh = null)
            it.maxSpeedKmh == null && inheritedSpeed != null -> it.copy(maxSpeedKmh = inheritedSpeed)
            else -> it
        }
    }
}

private fun samePhysicalRoad(first: Map<String, String>, second: Map<String, String>): Boolean {
    val firstRef = first["ref"]?.trim()?.takeIf(String::isNotEmpty)
    val secondRef = second["ref"]?.trim()?.takeIf(String::isNotEmpty)
    if (firstRef != null && secondRef != null) return firstRef.equals(secondRef, ignoreCase = true)
    val firstName = first["name"]?.trim()?.takeIf(String::isNotEmpty)
    val secondName = second["name"]?.trim()?.takeIf(String::isNotEmpty)
    return firstName != null && secondName != null &&
        firstName.equals(secondName, ignoreCase = true) && first["highway"] == second["highway"]
}

private const val SAME_WAY_BONUS = 14.0
private const val CONNECTED_SAME_ROAD_BONUS = 12.0
private const val SAME_ROAD_BONUS = 8.0
private const val CONNECTED_BONUS = 5.0
private const val TOUCH_METERS = 6.0

/** True si algún extremo de una vía está a menos de [TOUCH_METERS] de un extremo de la otra. */
private fun waysTouch(first: List<OsmCoordinate>, second: List<OsmCoordinate>): Boolean {
    if (first.isEmpty() || second.isEmpty()) return false
    val ends = listOf(first.first(), first.last())
    val others = listOf(second.first(), second.last())
    return ends.any { a -> others.any { b -> approximateMeters(a, b) <= TOUCH_METERS } }
}

private fun approximateMeters(a: OsmCoordinate, b: OsmCoordinate): Double {
    val latitudeScale = 111_320.0
    val longitudeScale = latitudeScale * cos(Math.toRadians((a.latitude + b.latitude) / 2.0))
    return hypot((a.longitude - b.longitude) * longitudeScale, (a.latitude - b.latitude) * latitudeScale)
}

/**
 * Suaviza lo que se muestra de OSM. En la TS10 la señal y los carriles parpadeaban porque:
 * - en enlaces y vías paralelas el emparejamiento saltaba unos segundos a un ramal;
 * - un tramo nuevo de la misma carretera llegaba sin `maxspeed` o con los carriles estimados.
 * Un cambio a otra carretera solo se acepta si se mantiene [SWITCH_CONFIRM_MS]; el límite se
 * conserva [SPEED_HOLD_MS] mientras se sigue la misma vía (o una rotonda) sin límite propio, y los
 * carriles reales del tramo anterior se mantienen si el nuevo solo los estima.
 */
internal class OsmRoadStabilizer {
    private var shown: OsmRoadContext? = null
    private var speedKmh: Int? = null
    private var speedAtMs = 0L
    private var pendingWayId: Long? = null
    private var pendingSinceMs = 0L

    fun reset() {
        shown = null
        speedKmh = null
        pendingWayId = null
    }

    fun stabilize(raw: OsmRoadContext, nowMs: Long): OsmRoadContext {
        val previous = shown
        if (previous != null && raw.wayId != previous.wayId && !sameCarriageway(previous, raw)) {
            if (pendingWayId != raw.wayId) {
                pendingWayId = raw.wayId
                pendingSinceMs = nowMs
            }
            if (nowMs - pendingSinceMs < SWITCH_CONFIRM_MS) return previous
        }
        pendingWayId = null
        val keepLanes = previous != null && raw.wayId != previous.wayId && raw.lanesEstimated &&
            !previous.lanesEstimated && samePhysicalRoad(previous.tags, raw.tags)
        val speed = when {
            raw.maxSpeedKmh != null -> raw.maxSpeedKmh.also {
                speedKmh = it
                speedAtMs = nowMs
            }
            previous != null && nowMs - speedAtMs <= SPEED_HOLD_MS && holdsSpeed(previous, raw) -> speedKmh
            else -> null
        }
        val result = raw.copy(
            maxSpeedKmh = speed,
            lanesInTravelDirection = if (keepLanes) previous!!.lanesInTravelDirection else raw.lanesInTravelDirection,
            lanesEstimated = if (keepLanes) false else raw.lanesEstimated,
            laneLineStyles = if (keepLanes) previous!!.laneLineStyles else raw.laneLineStyles,
        )
        shown = result
        return result
    }

    private fun sameCarriageway(previous: OsmRoadContext, next: OsmRoadContext): Boolean =
        samePhysicalRoad(previous.tags, next.tags) || next.isRoundabout || previous.isRoundabout ||
            (previous.highway == next.highway && !next.highway.endsWith("_link"))

    private fun holdsSpeed(previous: OsmRoadContext, next: OsmRoadContext): Boolean =
        next.wayId == previous.wayId || samePhysicalRoad(previous.tags, next.tags) || next.isRoundabout ||
            (previous.highway == next.highway && next.highway in MAIN_ROADS)

    private companion object {
        const val SWITCH_CONFIRM_MS = 3_000L
        const val SPEED_HOLD_MS = 60_000L
        val MAIN_ROADS = setOf("motorway", "trunk", "primary")
    }
}

internal fun isUsableRoadFix(accuracyMeters: Float?, ageMs: Long): Boolean =
    accuracyMeters != null && accuracyMeters.isFinite() && accuracyMeters in 0f..40f && ageMs in 0..15_000

private data class NearestSegment(
    val distanceMeters: Double,
    val bearingDegrees: Double,
    val segmentIndex: Int,
)

private fun nearestSegment(
    geometry: List<OsmCoordinate>,
    latitude: Double,
    longitude: Double,
): NearestSegment? {
    val latitudeScale = 111_320.0
    val longitudeScale = latitudeScale * cos(Math.toRadians(latitude))
    var best: NearestSegment? = null
    geometry.zipWithNext().forEachIndexed { index, (start, end) ->
        val ax = (start.longitude - longitude) * longitudeScale
        val ay = (start.latitude - latitude) * latitudeScale
        val bx = (end.longitude - longitude) * longitudeScale
        val by = (end.latitude - latitude) * latitudeScale
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 0.001) return@forEachIndexed
        val projection = ((-ax * dx - ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val distance = hypot(ax + projection * dx, ay + projection * dy)
        if (best == null || distance < best!!.distanceMeters) {
            best = NearestSegment(distance, normalizeBearing(Math.toDegrees(atan2(dx, dy))), index)
        }
    }
    return best
}

private fun OsmRoadWay.toRoadContext(
    forward: Boolean,
    distanceMeters: Double,
    headingDifferenceDegrees: Double?,
    segmentIndex: Int,
): OsmRoadContext {
    val onewayValue = effectiveOneway(tags)
    val actualForward = if (onewayValue < 0) false else forward
    val direction = if (actualForward) "forward" else "backward"
    val speed = parseMaxSpeedKmh(tags["maxspeed:$direction"] ?: tags["maxspeed"])
    val lanes = resolveDirectionalLanes(tags, actualForward, onewayValue != 0)
    val changes = tags["change:lanes:$direction"] ?: tags["change:lanes"]
    return OsmRoadContext(
        wayId = id,
        name = tags["name"],
        reference = tags["ref"],
        highway = tags["highway"].orEmpty(),
        maxSpeedKmh = speed,
        lanesInTravelDirection = lanes.first,
        lanesEstimated = lanes.second,
        oneway = onewayValue != 0,
        isRoundabout = tags["junction"] in setOf("roundabout", "circular"),
        centerLineStyle = parseRoadLineStyle(tags["divider"]),
        laneLineStyles = resolveLaneLineStyles(lanes.first, changes, tags["lane_markings"]),
        distanceMeters = distanceMeters,
        headingDifferenceDegrees = headingDifferenceDegrees,
        curvature = roadCurvatureAhead(geometry, segmentIndex, actualForward),
        tags = tags,
    )
}

/** Signed bend of the OSM geometry ahead: negative left, positive right. */
internal fun roadCurvatureAhead(
    geometry: List<OsmCoordinate>,
    segmentIndex: Int,
    forward: Boolean,
): Float {
    if (geometry.size < 3) return 0f
    val lastSegment = geometry.size - 2
    val currentIndex = segmentIndex.coerceIn(0, lastSegment)
    val aheadIndex = if (forward) {
        (currentIndex + 2).coerceAtMost(lastSegment)
    } else {
        (currentIndex - 2).coerceAtLeast(0)
    }
    if (aheadIndex == currentIndex) return 0f
    fun travelBearing(index: Int): Double {
        val start = geometry[index]
        val end = geometry[index + 1]
        val meanLatitude = Math.toRadians((start.latitude + end.latitude) / 2.0)
        val dx = (end.longitude - start.longitude) * cos(meanLatitude)
        val dy = end.latitude - start.latitude
        val bearing = normalizeBearing(Math.toDegrees(atan2(dx, dy)))
        return if (forward) bearing else normalizeBearing(bearing + 180.0)
    }
    val current = travelBearing(currentIndex)
    val ahead = travelBearing(aheadIndex)
    val signedDegrees = ((ahead - current + 540.0) % 360.0) - 180.0
    return (signedDegrees / 500.0).toFloat().coerceIn(-0.22f, 0.22f)
}

internal fun parseMaxSpeedKmh(value: String?): Int? {
    val normalized = value?.trim()?.lowercase(Locale.ROOT) ?: return null
    val number = Regex("""\d+(?:[.,]\d+)?""").find(normalized)?.value?.replace(',', '.')?.toDoubleOrNull()
        ?: return null
    val kmh = when {
        "mph" in normalized -> number * 1.609344
        "knot" in normalized -> number * 1.852
        else -> number
    }
    return kmh.toInt().takeIf { it in 5..200 }
}

internal fun resolveDirectionalLanes(tags: Map<String, String>, forward: Boolean, oneway: Boolean): Pair<Int?, Boolean> {
    val direction = if (forward) "forward" else "backward"
    parsePositiveInt(tags["lanes:$direction"])?.let { return it to false }
    val total = parsePositiveInt(tags["lanes"])
    if (oneway) return total to false
    if (total != null) return ceil(total / 2.0).toInt().coerceAtLeast(1) to true
    return null to false
}

internal fun resolveLaneLineStyles(laneCount: Int?, changes: String?, laneMarkings: String?): List<RoadLineStyle> {
    if (laneCount == null || laneCount <= 1) return emptyList()
    if (laneMarkings == "no") return List(laneCount - 1) { RoadLineStyle.NONE }
    val values = changes?.split('|')?.map(String::trim).orEmpty()
    return List(laneCount - 1) { boundary ->
        val left = values.getOrNull(boundary)
        val right = values.getOrNull(boundary + 1)
        val blockedFromLeft = left == "no" || left == "not_right"
        val blockedFromRight = right == "no" || right == "not_left"
        if (blockedFromLeft || blockedFromRight) RoadLineStyle.SOLID else RoadLineStyle.DASHED
    }
}

internal fun parseRoadLineStyle(value: String?): RoadLineStyle = when (value?.lowercase(Locale.ROOT)) {
    "no" -> RoadLineStyle.NONE
    "dashed", "dashed_line" -> RoadLineStyle.DASHED
    "solid", "solid_line" -> RoadLineStyle.SOLID
    "double_solid", "double_solid_line" -> RoadLineStyle.DOUBLE_SOLID
    else -> RoadLineStyle.UNKNOWN
}

private fun parsePositiveInt(value: String?): Int? = value?.toIntOrNull()?.takeIf { it in 1..12 }

private fun parseOneway(value: String?): Int = when (value?.lowercase(Locale.ROOT)) {
    "yes", "true", "1" -> 1
    "-1", "reverse" -> -1
    else -> 0
}

private fun effectiveOneway(tags: Map<String, String>): Int {
    val explicit = parseOneway(tags["oneway"])
    if (explicit != 0 || tags.containsKey("oneway")) return explicit
    return if (tags["junction"] in setOf("roundabout", "circular")) 1 else 0
}

private fun isDrivableHighway(highway: String): Boolean = highway !in setOf(
    "footway", "path", "cycleway", "pedestrian", "steps", "bridleway", "corridor", "platform",
)

private fun highwayMatchPenalty(highway: String?): Double = when (highway) {
    "motorway", "trunk", "primary", "secondary", "tertiary" -> 0.0
    "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link" -> 1.0
    "unclassified", "residential", "living_street" -> 2.0
    "service" -> 7.0
    "track" -> 12.0
    else -> 5.0
}

private fun angularDifference(first: Double, second: Double): Double {
    val difference = abs(normalizeBearing(first) - normalizeBearing(second))
    return min(difference, 360.0 - difference)
}

private fun normalizeBearing(value: Double): Double = (value % 360.0 + 360.0) % 360.0

private fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.7f", value)
