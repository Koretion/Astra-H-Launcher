package com.ts10.inspector.diagnostics

import android.content.Context
import android.os.SystemClock
import com.ts10.inspector.camera.GrabadoraHdAdasClient
import com.ts10.inspector.camera.GrabadoraHdCameraClient
import com.ts10.inspector.settings.DashboardPreferences
import com.ts10.inspector.vision.GrabadoraHdVisionProbe
import com.ts10.inspector.vision.LocalVisionLaneDetector
import com.ts10.inspector.vision.LocalVisionObjectDetector
import com.ts10.inspector.vision.RoadObjectKind
import com.ts10.inspector.vision.UvcDirectVisionProbe
import com.ts10.inspector.vision.VisionDeviceDiagnostics
import com.ts10.inspector.vision.VisionFrameHub
import java.util.Locale

internal fun StringBuilder.appendGrabadoraHdAdasSection() {
    val adas = GrabadoraHdAdasClient.state.value
    section("GRABADORA HD ADAS")
    field("Connected", adas.connected)
    field("Available", adas.available)
    field("Warning callback registered", adas.warningCallbackRegistered)
    field("Result callback registered", adas.resultCallbackRegistered)
    field("Status", adas.status)
    field("Structured result frames", adas.resultFrameCount)
    field("Warning events", adas.warningEventCount)
    field("Last warning", adas.lastWarning.label)
    field("Warning active", adas.warningActive)
    field("Lane received", adas.lane != null)
    field("Visible vehicles", adas.vehicles.size)
    field(
        "Callback shapes",
        adas.callbackShapes.entries.sortedBy { it.key }
            .joinToString { "${it.key}=${it.value}" }.ifBlank { "--" },
    )
    field(
        "Last structured frame age",
        adas.lastFrameElapsedRealtime.takeIf { it > 0L }
            ?.let { "${SystemClock.elapsedRealtime() - it} ms" }
            ?: "--",
    )
    appendLine("Warning history (oldest to newest):")
    if (adas.warningHistory.isEmpty()) appendLine("--")
    adas.warningHistory.forEach(::appendLine)
    appendLine("Structured samples (oldest to newest):")
    if (adas.structuredSamples.isEmpty()) appendLine("--")
    adas.structuredSamples.forEach(::appendLine)
    appendLine("ADAS callback errors:")
    if (adas.callbackErrors.isEmpty()) appendLine("--")
    adas.callbackErrors.forEach(::appendLine)
}

internal fun StringBuilder.appendGrabadoraHdCameraTargetsSection() {
    val cameraTargets = GrabadoraHdCameraClient.state.value
    section("GRABADORA HD CAMERA TARGETS")
    field("Connected", cameraTargets.connected)
    field("Callback registered", cameraTargets.callbackRegistered)
    field("Status", cameraTargets.status)
    field("USB device", cameraTargets.deviceName ?: "--")
    field("Camera id", cameraTargets.cameraId ?: "--")
    field("Last callback code", cameraTargets.lastCallbackCode ?: "--")
    field("Registration attempts", cameraTargets.registrationAttempts)
    field("Target callbacks", cameraTargets.targetCallbackCount)
    field("Empty target callbacks", cameraTargets.emptyTargetCallbackCount)
    field("Target events", cameraTargets.targetEventCount)
    field("Target batches", cameraTargets.targetBatchCount)
    field(
        "Callback shapes",
        cameraTargets.callbackShapes.entries.sortedBy { it.key }
            .joinToString { "${it.key}=${it.value}" }.ifBlank { "--" },
    )
    field(
        "Last target age",
        cameraTargets.lastTargetElapsedRealtime.takeIf { it > 0L }
            ?.let { "${SystemClock.elapsedRealtime() - it} ms" }
            ?: "--",
    )
    if (cameraTargets.samples.isEmpty()) {
        appendLine("No MyTarget samples received")
    } else {
        cameraTargets.samples.forEach { appendLine(it.logLine()) }
    }
    appendLine("USB inventory visible to launcher:")
    if (cameraTargets.usbInventory.isEmpty()) appendLine("--")
    cameraTargets.usbInventory.forEach(::appendLine)
    appendLine("Registration history:")
    if (cameraTargets.registrationHistory.isEmpty()) appendLine("--")
    cameraTargets.registrationHistory.forEach(::appendLine)
    appendLine("Connection history:")
    if (cameraTargets.connectionHistory.isEmpty()) appendLine("--")
    cameraTargets.connectionHistory.forEach(::appendLine)
    appendLine("Camera callback errors:")
    if (cameraTargets.callbackErrors.isEmpty()) appendLine("--")
    cameraTargets.callbackErrors.forEach(::appendLine)
}

internal fun StringBuilder.appendLocalVisionPhaseV0Section(context: Context) {
    val visionProbe = GrabadoraHdVisionProbe.state.value
    val dashboardSettings = DashboardPreferences.current(context)
    section("LOCAL VISION PHASE V0")
    field("Configured capture mode", dashboardSettings.localVisionCaptureMode)
    field("Status", visionProbe.status)
    field("Active", visionProbe.active)
    field("Surface attached", visionProbe.surfaceAttached)
    field("Candidate locked", visionProbe.locked)
    field("Camera id", visionProbe.cameraId ?: "--")
    field("Candidate", visionProbe.candidate ?: "--")
    field(
        "Requested size/format",
        if (visionProbe.width != null && visionProbe.height != null) {
            "${visionProbe.width}x${visionProbe.height} ${visionProbe.format}"
        } else {
            "--"
        },
    )
    field(
        "Delivered size",
        if (visionProbe.imageWidth != null && visionProbe.imageHeight != null) {
            "${visionProbe.imageWidth}x${visionProbe.imageHeight}"
        } else {
            "--"
        },
    )
    field("Frames", visionProbe.frames)
    field("Candidate frames", visionProbe.candidateFrames)
    field("Incompatible frames", visionProbe.incompatibleFrames)
    field("FPS", "%.2f".format(Locale.US, visionProbe.fps))
    field("First frame delay", visionProbe.firstFrameDelayMs?.let { "$it ms" } ?: "--")
    field(
        "Last frame age",
        visionProbe.lastFrameElapsedRealtime.takeIf { it > 0L }
            ?.let { "${SystemClock.elapsedRealtime() - it} ms" }
            ?: "--",
    )
    field("Planes", visionProbe.planeCount)
    field("Row/pixel stride", "${visionProbe.rowStride ?: "--"}/${visionProbe.pixelStride ?: "--"}")
    field("Sample CRC32", visionProbe.sampleCrc32 ?: "--")
    field("Sample mean", visionProbe.sampleMean ?: "--")
    field("Callback average", "%.3f ms".format(Locale.US, visionProbe.callbackAverageMs))
    field("Callback maximum", "%.3f ms".format(Locale.US, visionProbe.callbackMaxMs))
    field("PRIVATE GPU/readback last", "%.3f ms".format(Locale.US, visionProbe.gpuReadbackLastMs))
    field("PRIVATE GPU/readback average", "%.3f ms".format(Locale.US, visionProbe.gpuReadbackAverageMs))
    field("PRIVATE GPU/readback p95", "%.3f ms".format(Locale.US, visionProbe.gpuReadbackP95Ms))
    field("PRIVATE GPU/readback maximum", "%.3f ms".format(Locale.US, visionProbe.gpuReadbackMaximumMs))
    field("Process CPU", "%.1f %%".format(Locale.US, visionProbe.processCpuPercent))
    field("Watchdog stalls", visionProbe.stalls)
    field("Probe round", visionProbe.round)
    if (visionProbe.attempts.isEmpty()) {
        appendLine("No Surface candidates tested yet")
    } else {
        visionProbe.attempts.forEach { appendLine("Attempt: $it") }
    }
}

internal fun StringBuilder.appendVisionFrameHubSection() {
    val frameHub = VisionFrameHub.state.value
    section("VISION FRAME HUB")
    field("Mode", frameHub.mode)
    field("Status", frameHub.status)
    field("Source", frameHub.sourceName)
    field("Offered frames", frameHub.offeredFrames)
    field("Published frames", frameHub.publishedFrames)
    field("Rejected by mode", frameHub.rejectedModeFrames)
    field("Conversion errors", frameHub.conversionErrors)
    field("Last sequence", frameHub.lastSequence)
    field("Source size", "${frameHub.sourceWidth}x${frameHub.sourceHeight}")
    field("Shared size", "${frameHub.frameWidth}x${frameHub.frameHeight}")
    field("FPS", "%.2f".format(Locale.US, frameHub.fps))
    field("Copy last", "%.3f ms".format(Locale.US, frameHub.copyLastMs))
    field("Copy average", "%.3f ms".format(Locale.US, frameHub.copyAverageMs))
    field("Copy p95", "%.3f ms".format(Locale.US, frameHub.copyP95Ms))
    field("Copy maximum", "%.3f ms".format(Locale.US, frameHub.copyMaximumMs))
    field(
        "Last shared frame age",
        frameHub.lastFrameElapsedRealtime.takeIf { it > 0L }
            ?.let { "${SystemClock.elapsedRealtime() - it} ms" }
            ?: "--",
    )
}

internal fun StringBuilder.appendUsbUvcVisionSourceSection(context: Context) {
    val directUvc = UvcDirectVisionProbe.state.value
    val dashboardSettings = DashboardPreferences.current(context)
    section("USB/UVC VISION SOURCE")
    field("Selected vision source", dashboardSettings.localVisionSource)
    field("Active", directUvc.active)
    field("Status", directUvc.status.english(context))
    field("Device", directUvc.device)
    field("Selected size", directUvc.selectedSize)
    field("Supported sizes", directUvc.supportedSizes)
    field("Supported formats", directUvc.supportedFormats)
    field("Frames", directUvc.frames)
    field("FPS", "%.2f".format(Locale.US, directUvc.fps))
    field("Last RGBX buffer bytes", directUvc.lastBufferBytes)
    field("Errors", directUvc.errors)
    field(
        "Last direct frame age",
        directUvc.lastFrameElapsedRealtime.takeIf { it > 0L }
            ?.let { "${SystemClock.elapsedRealtime() - it} ms" }
            ?: "--",
    )
}

internal fun StringBuilder.appendLocalObjectDetectorPhaseV1Section() {
    val localDetector = LocalVisionObjectDetector.snapshotForReport()
    section("LOCAL OBJECT DETECTOR PHASE V1")
    field("Status", localDetector.status)
    field("Ready", localDetector.ready)
    field("Active", localDetector.active)
    field("Model", localDetector.model)
    field("Offered frames", localDetector.offeredFrames)
    field("Processed frames", localDetector.processedFrames)
    field("Dropped/replaced frames", localDetector.droppedFrames)
    field("Rejected: detector not ready", localDetector.rejectedNotReadyFrames)
    field("Rejected: inference busy", localDetector.rejectedBusyFrames)
    field("Rejected: adaptive interval", localDetector.rejectedIntervalFrames)
    field("Rejected: thermal guard", localDetector.rejectedThermalFrames)
    field("Rejected: memory guard", localDetector.rejectedMemoryFrames)
    field("Thermal pause events", localDetector.thermalPauseEvents)
    field("Memory pause events", localDetector.memoryPauseEvents)
    field("Current adaptive interval", "${localDetector.adaptiveIntervalMs} ms")
    field("Detection events", localDetector.detectionEvents)
    field(
        "Detection counts",
        localDetector.detectionCounts.entries.sortedBy { it.key }
            .joinToString { "${it.key}=${it.value}" }.ifBlank { "--" },
    )
    field("Current detections", localDetector.detections.size)
    field("Last inference", "${localDetector.inferenceMs} ms")
    field("Minimum inference", "${localDetector.minimumInferenceMs} ms")
    field("Average inference", "%.1f ms".format(Locale.US, localDetector.averageInferenceMs))
    field("P95 inference (last 120)", "${localDetector.p95InferenceMs} ms")
    field("Maximum inference", "${localDetector.maximumInferenceMs} ms")
    field(
        "Last result age",
        localDetector.lastResultElapsedRealtime.takeIf { it > 0L }
            ?.let { "${SystemClock.elapsedRealtime() - it} ms" }
            ?: "--",
    )
    appendLine("Current road objects:")
    if (localDetector.detections.isEmpty()) appendLine("--")
    localDetector.detections.forEach { appendLine(it.logLine()) }
    appendLine("Detection history (oldest to newest):")
    if (localDetector.detectionHistory.isEmpty()) appendLine("--")
    localDetector.detectionHistory.forEach(::appendLine)
    appendLine("Tracked road objects:")
    field("Confirmed tracks", localDetector.trackedObjects.size)
    field("Tentative tracks", localDetector.tentativeTracks)
    field("Tracks created", localDetector.totalTracksCreated)
    field("Tracks confirmed", localDetector.totalTracksConfirmed)
    field("Tracks expired", localDetector.totalTracksExpired)
    if (localDetector.trackedObjects.isEmpty()) appendLine("--")
    localDetector.trackedObjects.forEach { appendLine(it.logLine()) }
    appendLine("Tracking history (oldest to newest):")
    if (localDetector.trackingHistory.isEmpty()) appendLine("--")
    localDetector.trackingHistory.forEach(::appendLine)
    appendLine("Detector errors:")
    if (localDetector.errors.isEmpty()) appendLine("--")
    localDetector.errors.forEach(::appendLine)
}

internal fun StringBuilder.appendLocalLaneDetectorPhaseV3Section() {
    val laneDetector = LocalVisionLaneDetector.snapshotForReport()
    section("LOCAL LANE DETECTOR PHASE V3")
    field("Status", laneDetector.status)
    field("Active", laneDetector.active)
    field("Offered frames", laneDetector.offeredFrames)
    field("Processed frames", laneDetector.processedFrames)
    field("Last/max latency", "${laneDetector.lastLatencyMs}/${laneDetector.maximumLatencyMs} ms")
    field("Coherent frames", laneDetector.coherentFrames)
    field(
        "Last result age",
        laneDetector.lastResultElapsedRealtime.takeIf { it > 0L }
            ?.let { "${SystemClock.elapsedRealtime() - it} ms" }
            ?: "--",
    )
    field("Raw lane", laneDetector.rawLane?.logLine() ?: "--")
    field("Stable lane", laneDetector.stableLane?.logLine() ?: "--")
    appendLine("Lane history (oldest to newest):")
    if (laneDetector.history.isEmpty()) appendLine("--")
    laneDetector.history.forEach(::appendLine)
    appendLine("Lane detector errors:")
    if (laneDetector.errors.isEmpty()) appendLine("--")
    laneDetector.errors.forEach(::appendLine)
}

internal fun StringBuilder.appendConfirmedRoadSignsV3Section() {
    val localDetector = LocalVisionObjectDetector.snapshotForReport()
    section("CONFIRMED ROAD SIGNS V3")
    val confirmedSigns = localDetector.trackedObjects.filter {
        it.kind == RoadObjectKind.STOP_SIGN || it.kind == RoadObjectKind.TRAFFIC_LIGHT
    }
    field("Current confirmed signs", confirmedSigns.size)
    if (confirmedSigns.isEmpty()) appendLine("--")
    confirmedSigns.forEach { appendLine(it.logLine()) }
    field("Speed-sign candidate events", localDetector.speedSignCandidateEvents)
    field("Current speed-sign candidates", localDetector.speedSignCandidates.size)
    localDetector.speedSignCandidates.forEach { appendLine(it.logLine()) }
    appendLine(
        "Speed-sign value classifier: awaiting labelled real-camera crops; " +
            "candidate detector does not guess a number",
    )
    appendLine("Evidence crops: Pictures/AstraVision (bounded to 60 per app process)")
}

internal fun StringBuilder.appendVisionDeviceSnapshotSection(context: Context) {
    val visionDevice = VisionDeviceDiagnostics.snapshot(context)
    section("VISION DEVICE SNAPSHOT")
    field("SDK / release", "${visionDevice.sdk} / ${visionDevice.release}")
    field("Hardware / board", "${visionDevice.hardware} / ${visionDevice.board}")
    field("ABIs", visionDevice.abis)
    field("Processors", visionDevice.processors)
    field("Memory total", "${visionDevice.memoryTotalMb} MB")
    field("Memory available", "${visionDevice.memoryAvailableMb} MB")
    field("Low memory", visionDevice.lowMemory)
    field("Process PSS", "${visionDevice.processPssMb} MB")
    field("Java heap used/max", "${visionDevice.javaHeapUsedMb}/${visionDevice.javaHeapMaxMb} MB")
    field("Thermal status", visionDevice.thermalStatus)
    field("Battery temperature", visionDevice.batteryTemperatureC?.let { "%.1f C".format(Locale.US, it) } ?: "--")
    field("Fingerprint", visionDevice.fingerprint)
}

internal fun StringBuilder.appendPersistentVisionTelemetrySection(context: Context) {
    val persistentVisionTelemetry = TelemetryLog.snapshot(context)
    section("PERSISTENT VISION TELEMETRY (OLDEST TO NEWEST)")
    append(persistentVisionTelemetry)
    if (!persistentVisionTelemetry.endsWith('\n')) appendLine()
}
