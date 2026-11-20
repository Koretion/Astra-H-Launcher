package com.ts10.inspector.diagnostics

import android.content.Context
import android.os.SystemClock
import com.ts10.inspector.environment.OsmRoadContextProvider
import com.ts10.inspector.launcher.LauncherRuntime
import com.ts10.inspector.trips.VehicleTripTracker
import java.util.Locale

internal fun StringBuilder.appendLauncherGpsAcquisitionSection(context: Context) {
    val gpsAcquisition = LauncherRuntime.gpsAcquisition.value
    section("LAUNCHER GPS ACQUISITION")
    field("Status", gpsAcquisition.status.english(context))
    field("Registered providers", gpsAcquisition.registeredProviders.joinToString().ifBlank { "--" })
    field("Received callbacks", gpsAcquisition.receivedFixes)
    field("Last fix UTC", gpsAcquisition.lastFixUtc ?: "--")
    field("Last provider", gpsAcquisition.lastProvider ?: "--")
    field("Last accuracy", gpsAcquisition.lastAccuracyMeters?.let { "$it m" } ?: "--")
    field(
        "Last callback age",
        gpsAcquisition.lastFixElapsedMs.takeIf { it > 0L }
            ?.let { "${(SystemClock.elapsedRealtime() - it).coerceAtLeast(0L)} ms" }
            ?: "--",
    )
    field("Registration attempts", gpsAcquisition.registrationAttempts)
    field("Moving without GPS", gpsAcquisition.movingWithoutGps)
    field("Last registration error", gpsAcquisition.lastError ?: "--")
}

internal fun StringBuilder.appendOpenstreetmapRoadContextSection(context: Context) {
    val osm = OsmRoadContextProvider.state.value
    section("OPENSTREETMAP ROAD CONTEXT")
    field("Status", osm.status.english(context))
    field("Last error", osm.lastError?.english(context) ?: "--")
    field("Query in progress", osm.queryInProgress)
    field("Cached ways", osm.cachedWays)
    field(
        "Last query age",
        osm.lastQueryElapsedMs?.let { "${(SystemClock.elapsedRealtime() - it).coerceAtLeast(0L)} ms" } ?: "--",
    )
    field(
        "Last success age",
        osm.lastSuccessElapsedMs?.let { "${(SystemClock.elapsedRealtime() - it).coerceAtLeast(0L)} ms" } ?: "--",
    )
    field("Data source", osm.lastSource ?: "--")
    field("Way id", osm.road?.wayId ?: "--")
    field("Road", osm.road?.name ?: osm.road?.reference ?: "--")
    field("Highway", osm.road?.highway ?: "--")
    field("Distance", osm.road?.distanceMeters?.let { "%.1f m".format(Locale.US, it) } ?: "--")
    field("Heading difference", osm.road?.headingDifferenceDegrees?.let { "%.1f deg".format(Locale.US, it) } ?: "--")
    field("Max speed", osm.road?.maxSpeedKmh?.let { "$it km/h" } ?: "--")
    field("Lanes", osm.road?.lanesInTravelDirection ?: "--")
}

internal fun StringBuilder.appendVehicleTrackingGoogleSheetsSection(context: Context) {
    val tracking = VehicleTripTracker.state.value
    section("VEHICLE TRACKING / GOOGLE SHEETS")
    field("Enabled", tracking.enabled)
    field("Status", tracking.status.english(context))
    field("Active session", tracking.activeSession ?: "--")
    field("Session points", tracking.sessionPoints)
    field("Pending uploads", tracking.pendingUploads)
    field("Upload worker state", tracking.uploadWorkerState)
    field("Upload enqueue reason", tracking.uploadEnqueueReason ?: "--")
    field("Upload run attempt", tracking.uploadRunAttempt)
    field("Last upload attempt UTC", tracking.lastUploadAttemptUtc ?: "--")
    field("SQLite sessions", tracking.databaseSessions)
    field("SQLite points", tracking.databasePoints)
    field("Received fixes", tracking.receivedFixes)
    field("Accepted fixes", tracking.acceptedFixes)
    field("Rejected fixes", tracking.rejectedFixes)
    field("Last fix UTC", tracking.lastFixUtc ?: "--")
    field("Last provider", tracking.lastFixProvider ?: "--")
    field("Last accuracy", tracking.lastFixAccuracyMeters?.let { "$it m" } ?: "--")
    field("Last fix age", tracking.lastFixAgeMs?.let { "$it ms" } ?: "--")
    field(
        "Last received coordinates",
        if (tracking.lastFixLatitude != null && tracking.lastFixLongitude != null) {
            "${tracking.lastFixLatitude},${tracking.lastFixLongitude}"
        } else "--",
    )
    field("Last accepted UTC", tracking.lastAcceptedUtc ?: "--")
    field(
        "Last accepted coordinates",
        if (tracking.lastAcceptedLatitude != null && tracking.lastAcceptedLongitude != null) {
            "${tracking.lastAcceptedLatitude},${tracking.lastAcceptedLongitude}"
        } else "--",
    )
    field("Last vehicle speed", tracking.lastVehicleSpeedKmh?.let { "$it km/h" } ?: "--")
    field("Last GPS speed", tracking.lastGpsSpeedKmh?.let { "$it km/h" } ?: "--")
    field("Last speed source", tracking.lastSpeedSource ?: "--")
    field("Last decision", tracking.lastDecision ?: "--")
    field("Last batch", tracking.lastBatchUid ?: "--")
    field("Last HTTP result", tracking.lastHttpResult ?: "--")
    field("Last upload UTC", tracking.lastUploadUtc ?: "--")
    field("Last error", tracking.lastError ?: "--")
    appendLine("Recent decisions (newest first):")
    if (tracking.recentDecisions.isEmpty()) appendLine("--")
    tracking.recentDecisions.forEach { appendLine("  $it") }
}
