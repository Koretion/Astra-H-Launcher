package com.ts10.inspector.trips

import com.ts10.inspector.ui.UiText
import com.ts10.inspector.R
import android.content.Context
import android.location.Location
import android.os.SystemClock
import com.ts10.inspector.settings.DriverProfilePreferences
import com.ts10.inspector.vehicle.fuel.distanceMeters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class VehicleTrackingState(
    val enabled: Boolean = false,
    val status: UiText = UiText.of(R.string.trk_disabled),
    val activeSession: String? = null,
    val sessionPoints: Int = 0,
    val pendingUploads: Int = 0,
    val lastUploadUtc: String? = null,
    val lastError: String? = null,
    val receivedFixes: Long = 0,
    val acceptedFixes: Long = 0,
    val rejectedFixes: Long = 0,
    val lastFixUtc: String? = null,
    val lastFixProvider: String? = null,
    val lastFixAccuracyMeters: Float? = null,
    val lastFixAgeMs: Long? = null,
    val lastFixLatitude: Double? = null,
    val lastFixLongitude: Double? = null,
    val lastAcceptedUtc: String? = null,
    val lastAcceptedLatitude: Double? = null,
    val lastAcceptedLongitude: Double? = null,
    val lastVehicleSpeedKmh: Float? = null,
    val lastGpsSpeedKmh: Float? = null,
    val lastSpeedSource: String? = null,
    val lastDecision: String? = null,
    val lastHttpResult: String? = null,
    val lastBatchUid: String? = null,
    val uploadWorkerState: String = "Not scheduled",
    val uploadEnqueueReason: String? = null,
    val uploadRunAttempt: Int = 0,
    val lastUploadAttemptUtc: String? = null,
    val databaseSessions: Int = 0,
    val databasePoints: Int = 0,
    val recentDecisions: List<String> = emptyList(),
)

object VehicleTripTracker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private val callbackGate = AtomicLong(0L)
    private val locationProcessing = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(VehicleTrackingState())
    val state: StateFlow<VehicleTrackingState> = mutableState.asStateFlow()

    @Volatile
    private var applicationContext: Context? = null
    private var database: TrackingDatabase? = null
    private var lastStoredPoint: StoredPoint? = null
    private var lastUsablePoint: StoredPoint? = null
    private var lastMotionPoint: StoredPoint? = null
    private val waitingStartPoints = ArrayDeque<StoredPoint>()
    private var startMotionEvidence = 0
    private var lastUploadAttemptElapsedMs = 0L
    private var uploadRunning = false
    @Volatile
    private var acceptingLocations = true

    fun start(context: Context) {
        applicationContext = context.applicationContext
        val settings = VehicleTrackingPreferences.current(context)
        if (!settings.enabled) {
            acceptingLocations = false
            applicationContext?.let(VehicleTrackingUploadWorker::cancel)
            mutableState.value = VehicleTrackingState(enabled = false, status = UiText.of(R.string.trk_disabled))
            scope.launch {
                operationMutex.withLock {
                    database?.let { closeActiveSession(it, "tracking_disabled", lastUsablePoint) }
                }
            }
            return
        }
        acceptingLocations = true
        scope.launch {
            operationMutex.withLock {
                ensureDatabase(context.applicationContext)
                restoreActiveSessionState()
                refreshState(settings, if (settings.configured) UiText.of(R.string.trk_waiting_motion) else UiText.of(R.string.trk_cloud_missing))
                if (settings.configured) {
                    VehicleTrackingUploadWorker.enqueue(context, immediate = true, reason = "startup")
                }
            }
        }
    }

    fun applySettings(context: Context) {
        start(context)
    }

    /** Snapshot used by the local dashboard. The route is deliberately decimated by SQL
     * so a 50-journey history remains cheap on the TS10. */
    internal fun recentTrips(context: Context, limit: Int): List<VehicleTripSummary> =
        TrackingDatabase(context.applicationContext).use { helper ->
            helper.recentTrips(limit.coerceIn(1, MAX_RETAINED_SESSIONS))
        }

    fun onLocation(
        location: Location,
        vehicleSpeedKmh: Int,
        vehicleSpeedSource: String,
    ) {
        val context = applicationContext ?: return
        val settings = VehicleTrackingPreferences.current(context)
        if (!settings.enabled || !acceptingLocations) return
        val now = SystemClock.elapsedRealtime()
        val previousGate = callbackGate.get()
        if (now - previousGate < LOCATION_PROCESS_INTERVAL_MS) return
        if (!callbackGate.compareAndSet(previousGate, now)) return
        if (!locationProcessing.compareAndSet(false, true)) return
        val snapshot = Location(location)
        val speedSnapshot = vehicleSpeedKmh.coerceAtLeast(0)
        val sourceSnapshot = vehicleSpeedSource.ifBlank { "GPS" }
        scope.launch {
            try {
                operationMutex.withLock {
                    if (!acceptingLocations) return@withLock
                    processLocation(context, settings, snapshot, now, speedSnapshot, sourceSnapshot)
                }
            } finally {
                locationProcessing.set(false)
            }
        }
    }

    fun onAccessoryOff() {
        acceptingLocations = false
        val context = applicationContext ?: return
        val settings = VehicleTrackingPreferences.current(context)
        if (!settings.enabled) return
        scope.launch {
            operationMutex.withLock {
                val db = ensureDatabase(context)
                closeActiveSession(db, "acc_off", lastUsablePoint)
                VehicleTrackingUploadWorker.enqueue(context, immediate = true, reason = "acc_off")
                refreshState(settings, UiText.of(R.string.trk_closed_acc_off))
            }
        }
    }

    fun onAccessoryOn() {
        acceptingLocations = true
    }

    fun uploadNow() {
        val context = applicationContext ?: return
        val settings = VehicleTrackingPreferences.current(context)
        if (!settings.enabled || !settings.configured) {
            mutableState.value = mutableState.value.copy(status = UiText.of(R.string.trk_cloud_missing))
            return
        }
        mutableState.value = mutableState.value.copy(status = UiText.of(R.string.trk_manual_upload))
        VehicleTrackingUploadWorker.enqueue(context, immediate = true, reason = "manual")
        scope.launch {
            operationMutex.withLock {
                ensureDatabase(context)
                refreshState(settings, UiText.of(R.string.trk_manual_upload))
            }
        }
    }

    private fun processLocation(
        context: Context,
        settings: VehicleTrackingSettings,
        location: Location,
        nowElapsedMs: Long,
        vehicleSpeedKmh: Int,
        vehicleSpeedSource: String,
    ) {
        val db = ensureDatabase(context)
        val validation = validateLocation(location, nowElapsedMs)
        mutableState.value = mutableState.value.copy(
            receivedFixes = mutableState.value.receivedFixes + 1,
            lastFixUtc = location.time.takeIf { it > 0L }?.let { Instant.ofEpochMilli(it).toString() },
            lastFixProvider = location.provider,
            lastFixAccuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
            lastFixAgeMs = validation.ageMs,
            lastFixLatitude = location.latitude,
            lastFixLongitude = location.longitude,
        )
        if (!validation.accepted) {
            rejectFix(settings, validation.reason)
            return
        }

        val point = location.toStoredPoint(vehicleSpeedKmh, vehicleSpeedSource)
        mutableState.value = mutableState.value.copy(
            lastVehicleSpeedKmh = point.speedKmh,
            lastGpsSpeedKmh = point.gpsSpeedKmh,
            lastSpeedSource = point.speedSource,
        )
        lastUsablePoint = point
        val active = db.activeSession()
        if (active == null) {
            processWaitingForMovement(db, settings, point)
            return
        }

        val last = lastStoredPoint ?: db.lastPoint(active.id)
        if (last == null) {
            db.insertPoint(active, point, 0.0)
            lastStoredPoint = point
            acceptFix(settings, "Primer punto recuperado")
            return
        }
        val elapsedMs = elapsedBetween(last, point)
        if (elapsedMs <= 0L) {
            rejectFix(settings, "Out-of-order fix")
            return
        }
        if (elapsedMs > MAX_CONTINUOUS_SESSION_GAP_MS) {
            closeActiveSession(db, "location_gap", last)
            resetWaitingStart(point)
            acceptFix(settings, "Previous session closed after a long pause")
            VehicleTrackingUploadWorker.enqueue(context)
            return
        }
        val movement = distanceMeters(last, point)
        val impliedSpeed = impliedSpeedKmh(movement, elapsedMs)
        if (!isPlausibleTransition(point, movement, elapsedMs)) {
            rejectFix(settings, "Salto imposible (${movement.toInt()} m, ${impliedSpeed.toInt()} km/h)")
            return
        }
        val moving = point.speedKmh >= MIN_MOVING_SPEED_KMH || impliedSpeed >= MIN_IMPLIED_SPEED_KMH
        if (moving) lastMotionPoint = point
        val motionReference = lastMotionPoint ?: last
        if (!moving && elapsedBetween(motionReference, point) >= settings.autoCloseMinutes * 60_000L) {
            closeActiveSession(db, "idle_timeout", point)
            acceptFix(settings, "Trip closed after real inactivity")
            VehicleTrackingUploadWorker.enqueue(context)
            return
        }
        if (elapsedMs < settings.sampleIntervalSeconds * 1_000L) {
            acceptFix(settings, if (moving) "Movement confirmed; waiting for interval" else "Stopped; final point kept")
            return
        }
        if (!shouldStore(settings, point, movement, elapsedMs)) {
            acceptFix(settings, if (moving) "Fix valido no muestreado" else "Parado; punto final retenido")
            return
        }

        db.insertPoint(active, point, movement)
        lastStoredPoint = point
        acceptFix(settings, "Point saved")
        VehicleTrackingUploadWorker.enqueue(context)
    }

    private fun processWaitingForMovement(
        db: TrackingDatabase,
        settings: VehicleTrackingSettings,
        point: StoredPoint,
    ) {
        if (waitingStartPoints.isEmpty() && point.accuracyMeters > MAX_START_ACCURACY_METERS) {
            acceptFix(settings, "Waiting for an accurate first fix (${point.accuracyMeters.toInt()} m)")
            return
        }
        if (waitingStartPoints.isNotEmpty() &&
            elapsedBetween(waitingStartPoints.first(), point) > START_WINDOW_MS
        ) {
            waitingStartPoints.clear()
            startMotionEvidence = 0
        }
        waitingStartPoints.addLast(point)
        while (waitingStartPoints.size > MAX_START_CANDIDATES) waitingStartPoints.removeFirst()
        val anchor = waitingStartPoints.first()
        val elapsedMs = elapsedBetween(anchor, point)
        val movement = distanceMeters(anchor, point)
        val plausible = elapsedMs > 0L && isPlausibleTransition(point, movement, elapsedMs)
        val movingEvidence = plausible && trackingHasStartMovement(
            speedKmh = point.speedKmh,
            movementMeters = movement,
            elapsedMs = elapsedMs,
        )
        if (movingEvidence && startMotionEvidence == 0 && waitingStartPoints.size > 2) {
            // Retain the last stationary fix immediately before movement, not the complete
            // waiting history accumulated while the car was parked.
            val departureAnchor = waitingStartPoints.elementAt(waitingStartPoints.size - 2)
            waitingStartPoints.clear()
            waitingStartPoints.add(departureAnchor)
            waitingStartPoints.add(point)
        }
        startMotionEvidence = if (movingEvidence) startMotionEvidence + 1 else 0
        if (startMotionEvidence < REQUIRED_START_MOTION_FIXES) {
            acceptFix(settings, "Waiting for confirmed movement ($startMotionEvidence/$REQUIRED_START_MOTION_FIXES)")
            return
        }

        val driver = applicationContext
            ?.let { DriverProfilePreferences.get(it).state.value.activeProfile }
        var session = db.createSession(anchor.utc, driver?.id, driver?.name)
        var previous: StoredPoint? = null
        waitingStartPoints.forEach { candidate ->
            val distance = previous?.let { distanceMeters(it, candidate) } ?: 0.0
            db.insertPoint(session, candidate, distance)
            session = db.sessionById(session.id) ?: session
            previous = candidate
        }
        lastStoredPoint = previous
        lastMotionPoint = point
        waitingStartPoints.clear()
        startMotionEvidence = 0
        acceptFix(settings, "Trip started from the first valid fix")
        VehicleTrackingUploadWorker.enqueue(applicationContext ?: return)
    }

    private fun shouldStore(
        settings: VehicleTrackingSettings,
        point: StoredPoint,
        movementMeters: Double,
        elapsedMs: Long,
    ): Boolean {
        if (movementMeters < settings.minDistanceMeters) return false
        if (!isPlausibleTransition(point, movementMeters, elapsedMs)) return false
        val impliedSpeed = impliedSpeedKmh(movementMeters, elapsedMs)
        val trustedFastFix = point.accuracyMeters <= 15f && point.speedKmh >= 35f
        if (!trustedFastFix && point.speedKmh > impliedSpeed * 3.0 + 15.0) return false
        return if (movementMeters >= LARGE_MOVE_METERS) {
            impliedSpeed >= MIN_IMPLIED_SPEED_KMH || trustedFastFix
        } else {
            point.speedKmh >= MIN_MOVING_SPEED_KMH && impliedSpeed >= MIN_IMPLIED_SPEED_KMH
        }
    }

    private fun closeActiveSession(db: TrackingDatabase, reason: String, finalPoint: StoredPoint?) {
        val session = db.activeSession() ?: return
        val stored = lastStoredPoint ?: db.lastPoint(session.id)
        if (finalPoint != null && stored != null && elapsedBetween(stored, finalPoint) > 0L) {
            val elapsedMs = elapsedBetween(stored, finalPoint)
            val movement = distanceMeters(stored, finalPoint)
            if (isPlausibleTransition(finalPoint, movement, elapsedMs)) {
                db.insertPoint(session, finalPoint, movement)
                lastStoredPoint = finalPoint
            }
        }
        val updated = db.sessionById(session.id) ?: session
        if (updated.pointCount < MIN_POINTS_TO_KEEP_SESSION &&
            updated.distanceKm < MIN_DISTANCE_KM_TO_KEEP_SESSION
        ) {
            db.deleteSession(updated.id)
        } else {
            val endUtc = (lastStoredPoint ?: db.lastPoint(updated.id))?.utc ?: updated.startUtc
            db.closeSession(updated.id, endUtc, reason)
        }
        lastStoredPoint = null
        lastUsablePoint = null
        lastMotionPoint = null
        waitingStartPoints.clear()
        startMotionEvidence = 0
    }

    internal suspend fun uploadFromWorker(context: Context): TrackingUploadResult = operationMutex.withLock {
        applicationContext = context.applicationContext
        val settings = VehicleTrackingPreferences.current(context)
        if (!settings.enabled || !settings.configured) return@withLock TrackingUploadResult.DISABLED
        val db = ensureDatabase(context)
        val success = uploadPending(settings, force = true)
        when {
            !success -> TrackingUploadResult.RETRY
            db.pendingUploadCount() > 0 -> TrackingUploadResult.MORE_PENDING
            else -> TrackingUploadResult.COMPLETE
        }
    }

    private fun uploadPending(settings: VehicleTrackingSettings, force: Boolean = false): Boolean {
        if (!settings.enabled || !settings.configured || uploadRunning) return true
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastUploadAttemptElapsedMs < settings.uploadIntervalSeconds * 1_000L) return true
        val db = database ?: return true
        uploadRunning = true
        lastUploadAttemptElapsedMs = now
        return try {
            var workDone = false
            repeat(MAX_UPLOADS_PER_PASS) {
                val batch = db.pendingPointBatch(settings.batchSize)
                val closing = db.nextPendingClose()
                // A close is part of the same ordered session stream. Letting points from newer
                // sessions overtake it caused Apps Script maintenance to close the old session
                // twenty minutes later and Telegram to report it as a recovered historical trip.
                if (closing != null && shouldUploadCloseBeforePoints(closing.id, batch?.session?.id)) {
                    val payload = closedSessionPayload(settings, closing)
                    val ack = postJson(settings.scriptUrl, payload)
                    validateAck(ack, settings.deviceId, closing.key, payload.getString("batch_uid"), "closed")
                    db.markCloseUploaded(closing.id)
                    db.pruneUploadedSessions(MAX_RETAINED_SESSIONS)
                    recordHttpSuccess(payload.getString("batch_uid"), ack)
                    workDone = true
                    return@repeat
                }
                if (batch != null) {
                    val payload = openSessionPayload(settings, batch)
                    val ack = postJson(settings.scriptUrl, payload)
                    validateAck(ack, settings.deviceId, batch.session.key, payload.getString("batch_uid"), "open")
                    db.markPointsUploaded(batch.points.map(TrackingPoint::id))
                    recordHttpSuccess(payload.getString("batch_uid"), ack)
                    workDone = true
                    return@repeat
                }
                closing ?: return@repeat
                val payload = closedSessionPayload(settings, closing)
                val ack = postJson(settings.scriptUrl, payload)
                validateAck(ack, settings.deviceId, closing.key, payload.getString("batch_uid"), "closed")
                db.markCloseUploaded(closing.id)
                db.pruneUploadedSessions(MAX_RETAINED_SESSIONS)
                recordHttpSuccess(payload.getString("batch_uid"), ack)
                workDone = true
                return@repeat
            }
            mutableState.value = mutableState.value.copy(
                lastUploadUtc = if (workDone) utcNow() else mutableState.value.lastUploadUtc,
                lastError = null,
                pendingUploads = db.pendingUploadCount(),
            )
            true
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(
                status = UiText.of(R.string.trk_retry_pending),
                lastError = error.message ?: error.javaClass.simpleName,
                lastHttpResult = "ERROR: ${(error.message ?: error.javaClass.simpleName).take(MAX_DIAGNOSTIC_TEXT)}",
            )
            applicationContext?.let { context ->
                VehicleTrackingUploadDiagnostics.recordHttpError(
                    context,
                    error.message ?: error.javaClass.simpleName,
                )
            }
            false
        } finally {
            uploadRunning = false
        }
    }

    private fun openSessionPayload(
        settings: VehicleTrackingSettings,
        batch: PendingBatch,
    ): JSONObject {
        val points = JSONArray()
        batch.points.forEach { point ->
            points.put(
                JSONObject()
                    .put("point_seq", point.sequence)
                    .put("point_uid", trackingPointUid(settings.deviceId, batch.session.key, point.sequence))
                    .put("utc", point.point.utc)
                    .put("lat", point.point.latitude)
                    .put("lon", point.point.longitude)
                    .put("speed_kmh", round(point.point.speedKmh, 2))
                    .put("gps_speed_kmh", round(point.point.gpsSpeedKmh, 2))
                    .put("speed_source", point.point.speedSource)
                    .put("accuracy_m", round(point.point.accuracyMeters, 1))
                    .put("provider", point.point.provider)
                    .put("alt_m", point.point.altitudeMeters?.let { round(it, 1) } ?: "")
                    .put("satellites", point.point.satellites)
                    .put("fix", true)
                    .put("fix_3d", point.point.altitudeMeters != null)
                    .put("rssi_dbm", 0),
            )
        }
        val first = batch.points.first().sequence
        val last = batch.points.last().sequence
        return basePayload(settings, batch.session)
            .put("schema_version", TRACKING_SCHEMA_VERSION)
            .put("status", "open")
            .put("end_utc", "")
            .put("points_count", batch.points.size)
            .put("session_points_count", batch.session.pointCount)
            .put("batch_uid", "${batch.session.key}:$first-$last")
            .put("distance_km", round(batch.session.distanceKm, 3))
            .put("max_speed_kmh", round(batch.session.maxSpeedKmh, 2))
            .put("points", points)
    }

    private fun closedSessionPayload(
        settings: VehicleTrackingSettings,
        session: TrackingSession,
    ): JSONObject = basePayload(settings, session)
        .put("schema_version", TRACKING_SCHEMA_VERSION)
        .put("status", "closed")
        .put("close_reason", session.closeReason ?: "idle_timeout")
        .put("end_utc", session.endUtc ?: session.startUtc)
        .put("points_count", 0)
        .put("session_points_count", session.pointCount)
        .put("batch_uid", "${session.key}:close-${session.pointCount}")
        .put("distance_km", round(session.distanceKm, 3))
        .put("max_speed_kmh", round(session.maxSpeedKmh, 2))
        .put("points", JSONArray())

    private fun basePayload(
        settings: VehicleTrackingSettings,
        session: TrackingSession,
    ): JSONObject = JSONObject()
        .put("api_key", settings.apiKey)
        .put("device_id", settings.deviceId)
        .put("session_id", session.key)
        .put("driver_profile_id", session.driverProfileId ?: "")
        .put("driver_profile_name", session.driverProfileName ?: "Unassigned")
        .put("uploaded_utc", utcNow())
        .put("start_utc", session.startUtc)

    private fun postJson(endpoint: String, payload: JSONObject): JSONObject {
        require(endpoint.startsWith("https://")) { "The Apps Script URL must use HTTPS" }
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        var currentUrl = endpoint
        var requestMethod = "POST"
        repeat(MAX_HTTP_REDIRECTS + 1) { redirectIndex ->
            val connection = URL(currentUrl).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = requestMethod
                connection.connectTimeout = HTTP_CONNECT_TIMEOUT_MS
                connection.readTimeout = HTTP_READ_TIMEOUT_MS
                connection.doOutput = requestMethod == "POST"
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json,text/plain,*/*")
                connection.setRequestProperty("User-Agent", "AstraLauncher/0.1")
                if (requestMethod == "POST") {
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val code = connection.responseCode
                if (code in 200..299) {
                    val response = connection.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val json = runCatching { JSONObject(response) }.getOrNull()
                        ?: throw IllegalStateException("Apps Script no devolvio un ACK JSON")
                    if (!json.optBoolean("ok", false)) {
                        throw IllegalStateException(
                            "Apps Script: ${json.optString("error", "respuesta rechazada")}",
                        )
                    }
                    return json
                }
                if (code in HTTP_REDIRECT_CODES && redirectIndex < MAX_HTTP_REDIRECTS) {
                    val redirected = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("HTTP redirect $code without target")
                    currentUrl = URL(URL(currentUrl), redirected).toString()
                    require(currentUrl.startsWith("https://")) { "Insecure redirect rejected" }
                    if (code in setOf(301, 302, 303)) requestMethod = "GET"
                } else {
                    val response = connection.errorStream?.bufferedReader()?.use { it.readText() }
                        ?.take(MAX_ERROR_RESPONSE_LENGTH)
                        .orEmpty()
                    throw IllegalStateException("HTTP $code ${response.ifBlank { "sin respuesta" }}")
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Demasiados redirects HTTP")
    }

    private fun validateAck(
        ack: JSONObject,
        deviceId: String,
        sessionId: String,
        batchUid: String,
        status: String,
    ) {
        require(ack.optString("device_id") == deviceId) { "ACK for another device" }
        require(ack.optString("session_id") == sessionId) { "ACK for another session" }
        require(ack.optString("ack_batch_uid") == batchUid) { "ACK for another batch" }
        val acknowledgedStatus = ack.optString("status")
        require(trackingAckStatusAccepted(status, acknowledgedStatus)) {
            "ACK with unexpected status (sent=$status, received=${acknowledgedStatus.ifBlank { "empty" }})"
        }
    }

    private fun recordHttpSuccess(batchUid: String, ack: JSONObject) {
        val result = "ACK ${ack.optString("status")} lote=$batchUid " +
            "received=${ack.optInt("received_points", 0)} " +
            "nuevos=${ack.optInt("inserted_points", 0)} " +
            "reconciliados=${ack.optInt("reconciled_points", 0)}"
        mutableState.value = mutableState.value.copy(
            lastBatchUid = batchUid,
            lastHttpResult = result,
            lastError = null,
        )
        applicationContext?.let { VehicleTrackingUploadDiagnostics.recordHttpSuccess(it, batchUid, result) }
    }

    private fun refreshState(settings: VehicleTrackingSettings, status: UiText? = null) {
        val db = database
        val active = db?.activeSession()
        val diagnostics = applicationContext?.let(VehicleTrackingUploadDiagnostics::snapshot)
        mutableState.value = mutableState.value.copy(
            enabled = settings.enabled,
            status = status ?: when {
                !settings.enabled -> UiText.of(R.string.trk_disabled)
                !settings.configured -> UiText.of(R.string.trk_cloud_missing)
                active != null -> UiText.of(R.string.trk_recording)
                else -> UiText.of(R.string.trk_waiting_motion)
            },
            activeSession = active?.key,
            sessionPoints = active?.pointCount ?: 0,
            pendingUploads = db?.pendingUploadCount() ?: 0,
            databaseSessions = db?.sessionCount() ?: 0,
            databasePoints = db?.pointCount() ?: 0,
            uploadWorkerState = diagnostics?.workerState ?: mutableState.value.uploadWorkerState,
            uploadEnqueueReason = diagnostics?.enqueueReason ?: mutableState.value.uploadEnqueueReason,
            uploadRunAttempt = diagnostics?.runAttempt ?: mutableState.value.uploadRunAttempt,
            lastUploadAttemptUtc = diagnostics?.lastAttemptUtc ?: mutableState.value.lastUploadAttemptUtc,
            lastBatchUid = diagnostics?.lastBatchUid ?: mutableState.value.lastBatchUid,
            lastHttpResult = diagnostics?.lastHttpResult ?: mutableState.value.lastHttpResult,
            lastUploadUtc = diagnostics?.lastUploadUtc ?: mutableState.value.lastUploadUtc,
            lastError = diagnostics?.lastError ?: mutableState.value.lastError,
        )
    }

    internal fun refreshUploadDiagnostics(context: Context) {
        val diagnostics = VehicleTrackingUploadDiagnostics.snapshot(context)
        val workerStatus = when (diagnostics.workerState) {
            "Running" -> UiText.of(R.string.trk_uploading)
            "Continuing queue" -> UiText.of(R.string.trk_draining_queue)
            "Retrying with backoff", "Unexpected error; retrying" -> UiText.of(R.string.trk_retry_pending)
            "Completed" -> UiText.of(R.string.trk_upload_done)
            else -> mutableState.value.status
        }
        mutableState.value = mutableState.value.copy(
            status = workerStatus,
            uploadWorkerState = diagnostics.workerState,
            uploadEnqueueReason = diagnostics.enqueueReason,
            uploadRunAttempt = diagnostics.runAttempt,
            lastUploadAttemptUtc = diagnostics.lastAttemptUtc,
            lastBatchUid = diagnostics.lastBatchUid,
            lastHttpResult = diagnostics.lastHttpResult,
            lastUploadUtc = diagnostics.lastUploadUtc,
            lastError = diagnostics.lastError,
        )
    }

    private fun ensureDatabase(context: Context): TrackingDatabase =
        database ?: TrackingDatabase(context.applicationContext).also { database = it }

    private fun restoreActiveSessionState() {
        val db = database ?: return
        val active = db.activeSession() ?: return
        lastStoredPoint = db.lastPoint(active.id)
        lastUsablePoint = lastStoredPoint
        lastMotionPoint = lastStoredPoint
    }

    private fun resetWaitingStart(point: StoredPoint) {
        waitingStartPoints.clear()
        waitingStartPoints.add(point)
        startMotionEvidence = 0
        lastStoredPoint = null
        lastMotionPoint = null
    }

    private fun acceptFix(settings: VehicleTrackingSettings, decision: String) {
        val entry = "${utcNow()} ACCEPTED $decision"
        val accepted = lastUsablePoint
        mutableState.value = mutableState.value.copy(
            acceptedFixes = mutableState.value.acceptedFixes + 1,
            lastAcceptedUtc = accepted?.utc ?: mutableState.value.lastAcceptedUtc,
            lastAcceptedLatitude = accepted?.latitude ?: mutableState.value.lastAcceptedLatitude,
            lastAcceptedLongitude = accepted?.longitude ?: mutableState.value.lastAcceptedLongitude,
            lastDecision = decision,
            recentDecisions = (listOf(entry) + mutableState.value.recentDecisions)
                .take(MAX_TRACKING_DECISIONS),
        )
        refreshState(settings, UiText.Raw(decision))
    }

    private fun rejectFix(settings: VehicleTrackingSettings, reason: String) {
        val entry = "${utcNow()} REJECTED $reason"
        mutableState.value = mutableState.value.copy(
            rejectedFixes = mutableState.value.rejectedFixes + 1,
            lastDecision = "Rejected: $reason",
            recentDecisions = (listOf(entry) + mutableState.value.recentDecisions)
                .take(MAX_TRACKING_DECISIONS),
        )
        refreshState(settings, UiText.of(R.string.trk_gps_rejected, reason))
    }

    private fun validateLocation(location: Location, nowElapsedMs: Long): LocationValidation {
        val ageMs = locationAgeMillis(location.elapsedRealtimeNanos, nowElapsedMs)
        return when {
            !location.latitude.isFinite() || location.latitude !in -90.0..90.0 ->
                LocationValidation(false, "latitud invalida", ageMs)
            !location.longitude.isFinite() || location.longitude !in -180.0..180.0 ->
                LocationValidation(false, "longitud invalida", ageMs)
            !location.hasAccuracy() -> LocationValidation(false, "no horizontal accuracy", ageMs)
            location.accuracy <= 0f || location.accuracy > MAX_LOCATION_ACCURACY_METERS ->
                LocationValidation(false, "precision ${location.accuracy.toInt()} m", ageMs)
            ageMs !in 0..MAX_LOCATION_AGE_MS ->
                LocationValidation(false, "fix antiguo ${ageMs.coerceAtLeast(0)} ms", ageMs)
            else -> LocationValidation(true, "valido", ageMs)
        }
    }

    private fun Location.toStoredPoint(
        vehicleSpeedKmh: Int,
        vehicleSpeedSource: String,
    ): StoredPoint = StoredPoint(
        utc = Instant.ofEpochMilli(time.takeIf { it > 0L } ?: System.currentTimeMillis()).toString(),
        epochMillis = time.takeIf { it > 0L } ?: System.currentTimeMillis(),
        elapsedRealtimeMs = TimeUnit.NANOSECONDS.toMillis(elapsedRealtimeNanos),
        provider = provider.orEmpty(),
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = altitude.takeIf { hasAltitude() },
        speedKmh = vehicleSpeedKmh.coerceAtLeast(0).toFloat(),
        gpsSpeedKmh = if (hasSpeed()) max(0f, speed * 3.6f) else 0f,
        speedSource = vehicleSpeedSource,
        accuracyMeters = accuracy.takeIf { hasAccuracy() } ?: 99f,
        satellites = extras?.getInt("satellites", 0) ?: 0,
    )

    private fun distanceMeters(first: StoredPoint, second: StoredPoint): Double {
        val lat1 = Math.toRadians(first.latitude)
        val lat2 = Math.toRadians(second.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(second.longitude - first.longitude)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_METERS * 2.0 * asin(min(1.0, sqrt(a)))
    }

    private fun isPlausibleTransition(
        point: StoredPoint,
        movementMeters: Double,
        elapsedMs: Long,
    ): Boolean = trackingTransitionIsPlausible(point.speedKmh, movementMeters, elapsedMs)

    private fun impliedSpeedKmh(movementMeters: Double, elapsedMs: Long): Double =
        trackingImpliedSpeedKmh(movementMeters, elapsedMs)

    private fun elapsedBetween(first: StoredPoint, second: StoredPoint): Long =
        trackingElapsedMillis(
            firstEpochMs = first.epochMillis,
            firstElapsedMs = first.elapsedRealtimeMs,
            secondEpochMs = second.epochMillis,
            secondElapsedMs = second.elapsedRealtimeMs,
        )

    private fun utcNow(): String = Instant.now().toString()
    private fun round(value: Double, decimals: Int): Double =
        "%.${decimals}f".format(Locale.US, value).toDouble()

    private fun round(value: Float, decimals: Int): Double = round(value.toDouble(), decimals)

    private const val LOCATION_PROCESS_INTERVAL_MS = 2_000L
    private const val MAX_LOCATION_AGE_MS = 10_000L
    private const val MAX_LOCATION_ACCURACY_METERS = 35f
    private const val MAX_START_ACCURACY_METERS = 20f
    private const val MIN_MOVING_SPEED_KMH = 3f
    private const val MIN_IMPLIED_SPEED_KMH = 2.0
    private const val LARGE_MOVE_METERS = 25.0
    private const val REQUIRED_START_MOTION_FIXES = 2
    private const val MAX_START_CANDIDATES = 8
    private const val START_WINDOW_MS = 90_000L
    private const val MAX_CONTINUOUS_SESSION_GAP_MS = 2 * 60 * 60_000L
    private const val MIN_POINTS_TO_KEEP_SESSION = 4
    private const val MIN_DISTANCE_KM_TO_KEEP_SESSION = 0.02
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    // Drain old offline queues faster while keeping the worst-case worker duration bounded.
    private const val MAX_UPLOADS_PER_PASS = 8
    // The dashboard can show either the latest 25 or 50 journeys. Keep the larger
    // window locally after a successful cloud upload so opening a journey never
    // depends on connectivity.
    private const val MAX_RETAINED_SESSIONS = 50
    private const val MAX_HTTP_REDIRECTS = 5
    private const val HTTP_CONNECT_TIMEOUT_MS = 10_000
    // Apps Script occasionally needs more than 20 s while reconciling an old queued batch.
    // Batch UIDs make retries idempotent, but allowing one slow response avoids unnecessary
    // WorkManager backoff and helps the sizeable offline queue drain continuously.
    private const val HTTP_READ_TIMEOUT_MS = 45_000
    private const val MAX_ERROR_RESPONSE_LENGTH = 300
    private const val MAX_DIAGNOSTIC_TEXT = 300
    private const val MAX_TRACKING_DECISIONS = 40
    private const val TRACKING_SCHEMA_VERSION = 2
    private val HTTP_REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
}

internal data class StoredPoint(
    val utc: String,
    val epochMillis: Long,
    val elapsedRealtimeMs: Long,
    val provider: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val speedKmh: Float,
    val gpsSpeedKmh: Float,
    val speedSource: String,
    val accuracyMeters: Float,
    val satellites: Int,
)

internal data class VehicleTripMapPoint(
    val latitude: Double,
    val longitude: Double,
)

internal data class VehicleTripSummary(
    val id: Long,
    val startUtc: String,
    val endUtc: String?,
    val distanceKm: Double,
    val averageSpeedKmh: Float,
    val maxSpeedKmh: Float,
    val consumptionLitersPer100Km: Float? = null,
    val driverProfileId: String? = null,
    val driverProfileName: String = "",
    val route: List<VehicleTripMapPoint> = emptyList(),
)

internal data class LocationValidation(
    val accepted: Boolean,
    val reason: String,
    val ageMs: Long,
)

internal fun locationAgeMillis(locationElapsedRealtimeNanos: Long, nowElapsedMs: Long): Long =
    nowElapsedMs - TimeUnit.NANOSECONDS.toMillis(locationElapsedRealtimeNanos)

internal fun trackingPointUid(deviceId: String, sessionId: String, sequence: Int): String =
    "$deviceId:$sessionId:$sequence"

/**
 * Apps Script reports the current persisted session state in its ACK. When an old point batch is
 * replayed after that session has already been closed, a valid and fully idempotent `open` batch
 * is therefore acknowledged as `closed`. Exact device/session/batch identifiers are validated
 * separately, so accepting that one-way state transition cannot acknowledge a different batch.
 */
internal fun trackingAckStatusAccepted(sentStatus: String, acknowledgedStatus: String): Boolean =
    acknowledgedStatus == sentStatus ||
        (sentStatus == "open" && acknowledgedStatus == "closed")

internal fun trackingElapsedMillis(
    firstEpochMs: Long,
    firstElapsedMs: Long,
    secondEpochMs: Long,
    secondElapsedMs: Long,
): Long {
    val firstBootEpoch = firstEpochMs - firstElapsedMs
    val secondBootEpoch = secondEpochMs - secondElapsedMs
    val sameBoot = firstElapsedMs > 0L && secondElapsedMs > 0L &&
        kotlin.math.abs(firstBootEpoch - secondBootEpoch) <= MAX_BOOT_EPOCH_DRIFT_MS
    return if (sameBoot) secondElapsedMs - firstElapsedMs else secondEpochMs - firstEpochMs
}

internal fun trackingHasStartMovement(
    speedKmh: Float,
    movementMeters: Double,
    elapsedMs: Long,
): Boolean = speedKmh >= 4.5f ||
    (movementMeters >= 12.0 && trackingImpliedSpeedKmh(movementMeters, elapsedMs) >= 2.0)

internal fun trackingTransitionIsPlausible(
    reportedSpeedKmh: Float,
    movementMeters: Double,
    elapsedMs: Long,
): Boolean = elapsedMs > 0L && reportedSpeedKmh in 0f..220f &&
    trackingImpliedSpeedKmh(movementMeters, elapsedMs) <= 180.0

internal fun trackingImpliedSpeedKmh(movementMeters: Double, elapsedMs: Long): Double =
    if (elapsedMs <= 0L) Double.POSITIVE_INFINITY
    else movementMeters / elapsedMs.toDouble() * 3_600.0

private const val MAX_BOOT_EPOCH_DRIFT_MS = 60_000L
