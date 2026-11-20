package com.ts10.inspector.vision

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.ts10.inspector.diagnostics.TelemetryLog
import com.ts10.inspector.diagnostics.appendDiagnosticEvent
import com.ts10.inspector.diagnostics.diagnosticEventTime
import com.ts10.inspector.diagnostics.incrementDiagnosticCount
import com.ts10.inspector.launcher.LauncherRuntime
import com.ts10.inspector.settings.DashboardPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.ceil

private const val YOLO_MAX_RESULTS = 12

/**
 * Low-impact road-object detector for the TS10.
 *
 * YOLO runs on a background-priority thread and never queues camera frames. It consumes the
 * immutable, reduced snapshot published by [VisionFrameHub], so no preprocessing blocks the
 * ImageReader callback. The adaptive interval protects navigation, audio and rendering.
 */
internal object LocalVisionObjectDetector {
    private const val MODEL_ASSET = "yolov8n_320_int8.tflite"
    private const val INFERENCE_THREADS = 2
    private const val INITIAL_FRAME_INTERVAL_MS = 900L
    private const val MAX_FRAME_INTERVAL_MS = 2_500L
    private const val MIN_AVAILABLE_MEMORY_BYTES = 256L * 1024L * 1024L
    private const val SCORE_THRESHOLD = 0.35f
    private const val IOU_THRESHOLD = 0.50f
    private const val MAX_RETAINED_ERRORS = 20
    private const val MAX_RETAINED_DETECTION_EVENTS = 80
    private const val DETECTION_HISTORY_INTERVAL_MS = 5_000L
    private const val TELEMETRY_INTERVAL_MS = 60_000L
    private const val DECODE_DIAGNOSTIC_INTERVAL_MS = 5_000L
    private const val LATENCY_WINDOW_SIZE = 120

    private val mutableState = MutableStateFlow(LocalVisionDetectorState())
    val state: StateFlow<LocalVisionDetectorState> = mutableState.asStateFlow()

    private val thread = HandlerThread(
        "TS10-yolo-detector",
        Process.THREAD_PRIORITY_BACKGROUND,
    ).apply { start() }
    private val handler = Handler(thread.looper)
    private val busy = AtomicBoolean(false)
    private val rejectedNotReady = AtomicLong(0L)
    private val rejectedBusy = AtomicLong(0L)
    private val rejectedInterval = AtomicLong(0L)
    private val rejectedThermal = AtomicLong(0L)
    private val rejectedMemory = AtomicLong(0L)
    private val thermalPauseEvents = AtomicLong(0L)
    private val memoryPauseEvents = AtomicLong(0L)
    private val activeResourcePause = AtomicReference<ResourcePause?>(null)

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var ready = false
    @Volatile private var stopped = false
    @Volatile private var lastAcceptedElapsed = 0L
    @Volatile private var nextIntervalMs = INITIAL_FRAME_INTERVAL_MS
    private var applicationContext: Context? = null
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null
    private var inputSpec: TensorSpec? = null
    private var outputSpec: TensorSpec? = null
    private var totalInferenceMs = 0L
    private var minimumInferenceMs = Long.MAX_VALUE
    private val latencyWindow = LongArray(LATENCY_WINDOW_SIZE)
    private var latencyWindowCount = 0
    private var latencyWindowCursor = 0
    private var lastHistorySignature = ""
    private var lastHistoryElapsed = 0L
    private var lastDecodeDiagnosticElapsed = 0L
    @Volatile private var lastDecodeDiagnostics = "sin_datos"
    private val objectTracker = LocalVisionObjectTracker()
    private var lastTrackingSignature = ""
    private var lastTrackingHistoryElapsed = 0L
    private var lastSpeedSignHistoryElapsed = 0L
    private var telemetryScheduled = false

    private val telemetryRunnable = object : Runnable {
        override fun run() {
            if (!telemetryScheduled) return
            publishTelemetry("periodic")
            handler.postDelayed(this, TELEMETRY_INTERVAL_MS)
        }
    }

    fun start(context: Context) {
        applicationContext = context.applicationContext
        TelemetryLog.initialize(context)
        stopped = false
        if (ready || mutableState.value.initializing) return
        TelemetryLog.event("DETECTOR", "start requested")
        scheduleTelemetry()
        mutableState.update {
            it.copy(
                initializing = true,
                status = "Cargando YOLOv8n INT8",
                trackedObjects = emptyList(),
                tentativeTracks = 0,
                totalTracksCreated = 0L,
                totalTracksConfirmed = 0L,
                totalTracksExpired = 0L,
                trackingHistory = emptyList(),
            )
        }
        handler.post {
            objectTracker.reset()
            lastTrackingSignature = ""
            lastTrackingHistoryElapsed = 0L
            lastSpeedSignHistoryElapsed = 0L
            initialise()
        }
    }

    fun stop(reason: String) {
        stopped = true
        ready = false
        handler.post {
            publishTelemetry("stop")
            TelemetryLog.event("DETECTOR", "stop reason=$reason")
            telemetryScheduled = false
            handler.removeCallbacks(telemetryRunnable)
            interpreter?.close()
            interpreter = null
            inputBuffer = null
            outputBuffer = null
            inputSpec = null
            outputSpec = null
            busy.set(false)
            mutableState.update {
                it.copy(initializing = false, ready = false, active = false, status = reason)
            }
        }
    }

    fun snapshotForReport(): LocalVisionDetectorState = mutableState.value.withRejectionCounters()

    /** Clears camera-derived UI immediately without unloading the ready LiteRT model. */
    fun clearObservations(reason: String) {
        handler.post {
            objectTracker.reset()
            lastTrackingSignature = ""
            mutableState.update {
                it.copy(
                    active = false,
                    status = reason,
                    lastOfferedElapsedRealtime = 0L,
                    lastResultElapsedRealtime = 0L,
                    detections = emptyList(),
                    trackedObjects = emptyList(),
                    tentativeTracks = 0,
                    speedSignCandidates = emptyList(),
                )
            }
        }
    }

    /** Returns true only when copying a frame cannot create a backlog or exceed the CPU budget. */
    fun shouldAcceptFrame(nowElapsed: Long): Boolean {
        if (!ready || stopped) {
            rejectedNotReady.incrementAndGet()
            return false
        }
        if (busy.get()) {
            rejectedBusy.incrementAndGet()
            return false
        }
        if (nowElapsed - lastAcceptedElapsed < nextIntervalMs) {
            rejectedInterval.incrementAndGet()
            return false
        }
        val context = applicationContext
        if (context == null) {
            rejectedNotReady.incrementAndGet()
            return false
        }
        when (deviceInferenceHeadroom(context)) {
            ResourcePause.THERMAL -> {
                rejectedThermal.incrementAndGet()
                recordResourcePause(ResourcePause.THERMAL)
                return false
            }
            ResourcePause.MEMORY -> {
                rejectedMemory.incrementAndGet()
                recordResourcePause(ResourcePause.MEMORY)
                return false
            }
            null -> clearResourcePause()
        }
        return true
    }

    /** Queues one immutable shared frame; all preprocessing happens on the detector thread. */
    fun offer(frame: SharedVisionFrame) {
        val timestampElapsed = frame.capturedElapsedRealtime
        if (!shouldAcceptFrame(timestampElapsed)) return
        if (!busy.compareAndSet(false, true)) {
            rejectedBusy.incrementAndGet()
            return
        }
        val input = inputBuffer
        val spec = inputSpec
        if (!ready || stopped || input == null || spec == null) {
            rejectedNotReady.incrementAndGet()
            busy.set(false)
            return
        }
        lastAcceptedElapsed = timestampElapsed
        mutableState.update { current ->
            current.copy(
                active = true,
                offeredFrames = current.offeredFrames + 1,
                lastOfferedElapsedRealtime = timestampElapsed,
                status = "YOLO ready; inference pending",
            )
        }
        handler.post {
            val semanticFrame = frame.rgb
            val transform = runCatching { fillYoloInput(semanticFrame, input, spec) }
                .getOrElse { error ->
                    busy.set(false)
                    recordError("Entrada ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                    return@post
                }
            val speedSignCandidates = detectSpeedSignCandidates(semanticFrame)
            applicationContext?.let { context ->
                VisionEvidenceRecorder.recordSpeedCandidates(
                    context,
                    semanticFrame,
                    speedSignCandidates,
                    timestampElapsed,
                )
            }
            runInference(transform, semanticFrame, speedSignCandidates)
        }
    }

    private fun initialise() {
        if (stopped) return
        runCatching {
            val context = applicationContext ?: error("Context unavailable")
            val loaded = Interpreter(
                loadMappedAsset(context, MODEL_ASSET),
                Interpreter.Options().apply {
                    setNumThreads(INFERENCE_THREADS)
                    setUseXNNPACK(true)
                },
            )
            val inputTensor = loaded.getInputTensor(0)
            val outputTensor = loaded.getOutputTensor(0)
            val newInputSpec = TensorSpec(
                shape = inputTensor.shape(),
                dataType = inputTensor.dataType(),
                scale = inputTensor.quantizationParams().scale,
                zeroPoint = inputTensor.quantizationParams().zeroPoint,
            )
            validateYoloInputSpec(newInputSpec)
            val newOutputSpec = TensorSpec(
                shape = outputTensor.shape(),
                dataType = outputTensor.dataType(),
                scale = outputTensor.quantizationParams().scale,
                zeroPoint = outputTensor.quantizationParams().zeroPoint,
            )
            loaded to (newInputSpec to newOutputSpec)
        }.onSuccess { (loaded, specs) ->
            val (newInputSpec, newOutputSpec) = specs
            interpreter = loaded
            inputSpec = newInputSpec
            outputSpec = newOutputSpec
            inputBuffer = directTensorBuffer(newInputSpec)
            outputBuffer = directTensorBuffer(newOutputSpec)
            ready = true
            mutableState.update {
                it.copy(
                    initializing = false,
                    ready = true,
                    active = false,
                    status = "YOLO ready; adaptive load",
                    model = "YOLOv8n INT8 ${newInputSpec.width}x${newInputSpec.height} / CPU 2 hilos",
                )
            }
            TelemetryLog.event(
                "MODEL",
                "loaded input=${newInputSpec.description()} output=${newOutputSpec.description()} " +
                    "threads=$INFERENCE_THREADS xnnpack=true",
            )
        }.onFailure { error ->
            ready = false
            recordError("YOLO load ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            mutableState.update {
                it.copy(initializing = false, ready = false, status = "YOLO unavailable")
            }
        }
    }

    private fun runInference(
        transform: LetterboxTransform,
        semanticFrame: VisionRgbFrame,
        speedSignCandidates: List<SpeedSignCandidate>,
    ) {
        val loaded = interpreter
        val input = inputBuffer
        val output = outputBuffer
        val spec = outputSpec
        if (stopped || loaded == null || input == null || output == null || spec == null) {
            busy.set(false)
            return
        }
        val started = SystemClock.elapsedRealtime()
        runCatching {
            input.rewind()
            output.clear()
            loaded.run(input, output)
            output.rewind()
            decodeYoloOutputDetailed(output, spec, transform, SCORE_THRESHOLD, IOU_THRESHOLD)
        }.onSuccess { detections ->
            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
            val result = detections
            nextIntervalMs = adaptiveDetectorIntervalMs(elapsed)
            totalInferenceMs += elapsed
            minimumInferenceMs = min(minimumInferenceMs, elapsed)
            recordLatency(elapsed)
            lastDecodeDiagnostics = result.diagnostics.toLine()
            val diagnosticNow = SystemClock.elapsedRealtime()
            if (diagnosticNow - lastDecodeDiagnosticElapsed >= DECODE_DIAGNOSTIC_INTERVAL_MS) {
                lastDecodeDiagnosticElapsed = diagnosticNow
                TelemetryLog.event("DETECTOR_DIAG", result.diagnostics.toLine())
            }
            val acceptedDetections = result.detections.map { detection ->
                if (detection.kind != RoadObjectKind.TRAFFIC_LIGHT) detection else {
                    val light = classifyTrafficLight(semanticFrame, detection.box)
                    detection.copy(
                        trafficLightColor = light.color,
                        trafficLightColorConfidence = light.confidence,
                        semanticDetails = light.logLine(),
                    )
                }
            }
            val historyNow = SystemClock.elapsedRealtime()
            applicationContext?.let { context ->
                VisionEvidenceRecorder.recordTrafficLights(
                    context,
                    semanticFrame,
                    acceptedDetections,
                    historyNow,
                )
            }
            val tracking = objectTracker.update(
                historyNow,
                acceptedDetections,
                LocalVisionLaneDetector.state.value.stableLane,
            )
            val historySignature = acceptedDetections.map { it.kind.modelLabel }.distinct().sorted().joinToString()
            val retainHistory = acceptedDetections.isNotEmpty() && (
                historySignature != lastHistorySignature ||
                    historyNow - lastHistoryElapsed >= DETECTION_HISTORY_INTERVAL_MS
                )
            if (retainHistory) {
                lastHistorySignature = historySignature
                lastHistoryElapsed = historyNow
                TelemetryLog.event(
                    "DETECTION",
                    acceptedDetections.joinToString { it.logLine() },
                )
            }
            if (speedSignCandidates.isNotEmpty() && historyNow - lastSpeedSignHistoryElapsed >= SPEED_SIGN_HISTORY_INTERVAL_MS) {
                lastSpeedSignHistoryElapsed = historyNow
                TelemetryLog.event(
                    "SPEED_SIGN",
                    speedSignCandidates.joinToString { it.logLine() },
                )
            }
            val trackingSignature = tracking.objects.joinToString { tracked ->
                "${tracked.id}:${tracked.kind.modelLabel}:${tracked.horizontalMotion}:${tracked.relativeMotion}"
            }
            val retainTrackingHistory = tracking.objects.isNotEmpty() && (
                trackingSignature != lastTrackingSignature ||
                    historyNow - lastTrackingHistoryElapsed >= DETECTION_HISTORY_INTERVAL_MS
                )
            if (retainTrackingHistory) {
                lastTrackingSignature = trackingSignature
                lastTrackingHistoryElapsed = historyNow
                TelemetryLog.event(
                    "TRACKING",
                    tracking.objects.joinToString { it.logLine() } +
                        " tentative=${tracking.tentativeCount} " +
                        "confirmedNow=${tracking.newlyConfirmedIds} expiredNow=${tracking.expiredIds}",
                )
            }
            mutableState.update { current ->
                val processed = current.processedFrames + 1
                val counts = acceptedDetections.fold(current.detectionCounts) { result, detection ->
                    incrementDiagnosticCount(result, detection.kind.modelLabel)
                }
                current.copy(
                    active = true,
                    status = if (acceptedDetections.isEmpty()) {
                        "YOLO active; no relevant objects (${elapsed} ms)"
                    } else {
                        "YOLO: ${acceptedDetections.size} detectados / " +
                            "${tracking.objects.size} seguidos (${elapsed} ms)"
                    },
                    processedFrames = processed,
                    detectionEvents = current.detectionEvents + acceptedDetections.size,
                    inferenceMs = elapsed,
                    averageInferenceMs = totalInferenceMs.toFloat() / processed,
                    minimumInferenceMs = minimumInferenceMs,
                    p95InferenceMs = currentP95LatencyMs(),
                    maximumInferenceMs = max(current.maximumInferenceMs, elapsed),
                    adaptiveIntervalMs = nextIntervalMs,
                    lastResultElapsedRealtime = historyNow,
                    detections = acceptedDetections,
                    trackedObjects = tracking.objects,
                    tentativeTracks = tracking.tentativeCount,
                    totalTracksCreated = tracking.totalCreated,
                    totalTracksConfirmed = tracking.totalConfirmed,
                    totalTracksExpired = tracking.totalExpired,
                    droppedFrames = totalRejectedFrames(),
                    rejectedNotReadyFrames = rejectedNotReady.get(),
                    rejectedBusyFrames = rejectedBusy.get(),
                    rejectedIntervalFrames = rejectedInterval.get(),
                    rejectedThermalFrames = rejectedThermal.get(),
                    rejectedMemoryFrames = rejectedMemory.get(),
                    thermalPauseEvents = thermalPauseEvents.get(),
                    memoryPauseEvents = memoryPauseEvents.get(),
                    detectionCounts = counts,
                    detectionHistory = if (!retainHistory) current.detectionHistory else {
                        appendDiagnosticEvent(
                            current.detectionHistory,
                            "${diagnosticEventTime()} ${acceptedDetections.joinToString { it.logLine() }}",
                            MAX_RETAINED_DETECTION_EVENTS,
                        )
                    },
                    trackingHistory = if (!retainTrackingHistory) current.trackingHistory else {
                        appendDiagnosticEvent(
                            current.trackingHistory,
                            "${diagnosticEventTime()} ${tracking.objects.joinToString { it.logLine() }}",
                            MAX_RETAINED_DETECTION_EVENTS,
                        )
                    },
                    speedSignCandidates = speedSignCandidates,
                    speedSignCandidateEvents = current.speedSignCandidateEvents + speedSignCandidates.size,
                )
            }
        }.onFailure { error ->
            nextIntervalMs = MAX_FRAME_INTERVAL_MS
            recordError("Inferencia YOLO ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        }
        busy.set(false)
    }

    private fun deviceInferenceHeadroom(context: Context): ResourcePause? {
        val power = context.getSystemService(PowerManager::class.java)
        if (power?.currentThermalStatus?.let { it >= PowerManager.THERMAL_STATUS_SEVERE } == true) {
            return ResourcePause.THERMAL
        }
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
        if (memory.lowMemory || memory.availMem < MIN_AVAILABLE_MEMORY_BYTES) {
            return ResourcePause.MEMORY
        }
        return null
    }

    private fun recordError(message: String) {
        TelemetryLog.event("ERROR", message)
        mutableState.update { current ->
            current.copy(
                status = message,
                errors = appendDiagnosticEvent(current.errors, message, MAX_RETAINED_ERRORS),
            )
        }
    }

    private fun scheduleTelemetry() {
        if (telemetryScheduled) return
        telemetryScheduled = true
        handler.postDelayed(telemetryRunnable, TELEMETRY_INTERVAL_MS)
    }

    private fun recordResourcePause(reason: ResourcePause) {
        val previous = activeResourcePause.getAndSet(reason)
        if (previous == reason) return
        when (reason) {
            ResourcePause.THERMAL -> thermalPauseEvents.incrementAndGet()
            ResourcePause.MEMORY -> memoryPauseEvents.incrementAndGet()
        }
        val status = when (reason) {
            ResourcePause.THERMAL -> "YOLO paused for temperature"
            ResourcePause.MEMORY -> "YOLO paused for memory"
        }
        mutableState.update { it.copy(active = false, status = status) }
        TelemetryLog.event("RESOURCE", "pause reason=${reason.name.lowercase(Locale.US)}")
    }

    private fun clearResourcePause() {
        val previous = activeResourcePause.getAndSet(null) ?: return
        TelemetryLog.event("RESOURCE", "resume previous=${previous.name.lowercase(Locale.US)}")
        mutableState.update { it.copy(status = "YOLO resumed; adaptive load") }
    }

    private fun recordLatency(elapsedMs: Long) {
        latencyWindow[latencyWindowCursor] = elapsedMs
        latencyWindowCursor = (latencyWindowCursor + 1) % latencyWindow.size
        latencyWindowCount = min(latencyWindowCount + 1, latencyWindow.size)
    }

    private fun currentP95LatencyMs(): Long =
        percentile95LatencyMs(latencyWindow.copyOf(latencyWindowCount))

    private fun publishTelemetry(reason: String) {
        val current = mutableState.value
        val dropped = totalRejectedFrames()
        val updated = current.copy(
            droppedFrames = dropped,
            rejectedNotReadyFrames = rejectedNotReady.get(),
            rejectedBusyFrames = rejectedBusy.get(),
            rejectedIntervalFrames = rejectedInterval.get(),
            rejectedThermalFrames = rejectedThermal.get(),
            rejectedMemoryFrames = rejectedMemory.get(),
            thermalPauseEvents = thermalPauseEvents.get(),
            memoryPauseEvents = memoryPauseEvents.get(),
            adaptiveIntervalMs = nextIntervalMs,
            minimumInferenceMs = minimumInferenceMs.takeUnless { it == Long.MAX_VALUE } ?: 0L,
            p95InferenceMs = currentP95LatencyMs(),
        )
        mutableState.value = updated
        val context = applicationContext ?: return
        val device = runCatching { VisionDeviceDiagnostics.snapshot(context) }.getOrNull()
        val probe = GrabadoraHdVisionProbe.state.value
        val launcher = LauncherRuntime.state.value
        val navigationPackage = DashboardPreferences.current(context).navigationPackage
        TelemetryLog.event(
            "SUMMARY",
            "reason=$reason status=${updated.status} ready=${updated.ready} active=${updated.active} " +
                "cameraFrames=${probe.frames} cameraFps=${formatTelemetryFloat(probe.fps)} " +
                "processCpuPct=${formatTelemetryFloat(probe.processCpuPercent)} " +
                "navigation=$navigationPackage speedKmh=${launcher.speedKmh} speedSource=${launcher.speedSource} " +
                "mediaPlaying=${launcher.mediaPlaying} mediaPackage=${launcher.mediaPackage ?: "--"} " +
                "offered=${updated.offeredFrames} processed=${updated.processedFrames} dropped=$dropped " +
                "rejectNotReady=${updated.rejectedNotReadyFrames} rejectBusy=${updated.rejectedBusyFrames} " +
                "rejectInterval=${updated.rejectedIntervalFrames} rejectThermal=${updated.rejectedThermalFrames} " +
                "rejectMemory=${updated.rejectedMemoryFrames} thermalPauses=${updated.thermalPauseEvents} " +
                "memoryPauses=${updated.memoryPauseEvents} intervalMs=${updated.adaptiveIntervalMs} " +
                "latencyLastMs=${updated.inferenceMs} latencyMinMs=${updated.minimumInferenceMs} " +
                "latencyAvgMs=${formatTelemetryFloat(updated.averageInferenceMs)} " +
                "latencyP95Ms=${updated.p95InferenceMs} latencyMaxMs=${updated.maximumInferenceMs} " +
                "detections=${updated.detectionEvents} counts=${updated.detectionCounts} " +
                "tracks=${updated.trackedObjects.size} tentativeTracks=${updated.tentativeTracks} " +
                "tracksCreated=${updated.totalTracksCreated} tracksConfirmed=${updated.totalTracksConfirmed} " +
                "tracksExpired=${updated.totalTracksExpired} " +
                "decodeDiagnostics=${lastDecodeDiagnostics} " +
                "memoryAvailableMb=${device?.memoryAvailableMb ?: -1} processPssMb=${device?.processPssMb ?: -1} " +
                "heapMb=${device?.javaHeapUsedMb ?: -1}/${device?.javaHeapMaxMb ?: -1} " +
                "thermal=${device?.thermalStatus ?: "unknown"} batteryC=${device?.batteryTemperatureC ?: "--"}",
        )
    }

    private fun totalRejectedFrames(): Long = rejectedNotReady.get() + rejectedBusy.get() +
        rejectedInterval.get() + rejectedThermal.get() + rejectedMemory.get()

    private fun LocalVisionDetectorState.withRejectionCounters(): LocalVisionDetectorState = copy(
        droppedFrames = totalRejectedFrames(),
        rejectedNotReadyFrames = rejectedNotReady.get(),
        rejectedBusyFrames = rejectedBusy.get(),
        rejectedIntervalFrames = rejectedInterval.get(),
        rejectedThermalFrames = rejectedThermal.get(),
        rejectedMemoryFrames = rejectedMemory.get(),
        thermalPauseEvents = LocalVisionObjectDetector.thermalPauseEvents.get(),
        memoryPauseEvents = LocalVisionObjectDetector.memoryPauseEvents.get(),
        adaptiveIntervalMs = nextIntervalMs,
    )
}

private enum class ResourcePause { THERMAL, MEMORY }

private fun formatTelemetryFloat(value: Float): String = String.format(Locale.US, "%.1f", value)

internal fun adaptiveDetectorIntervalMs(inferenceMs: Long): Long =
    (inferenceMs.coerceAtLeast(1L) * 2L + 250L).coerceIn(650L, 2_500L)

internal fun percentile95LatencyMs(samples: LongArray): Long {
    if (samples.isEmpty()) return 0L
    val sorted = samples.sortedArray()
    val index = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(sorted.indices)
    return sorted[index]
}

internal data class TensorSpec(
    val shape: IntArray,
    val dataType: DataType,
    val scale: Float,
    val zeroPoint: Int,
) {
    val height: Int get() = shape[1]
    val width: Int get() = shape[2]
    val elements: Int get() = shape.fold(1, Int::times)
    val bytesPerElement: Int get() = when (dataType) {
        DataType.FLOAT32 -> Float.SIZE_BYTES
        DataType.UINT8, DataType.INT8 -> 1
        else -> error("Tensor $dataType no soportado")
    }

    fun description(): String =
        "shape=${shape.contentToString()} type=$dataType scale=$scale zeroPoint=$zeroPoint"
}

internal data class LetterboxTransform(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val modelWidth: Int,
    val modelHeight: Int,
    val scale: Float,
    val padX: Float,
    val padY: Float,
)

internal data class YoloCandidate(
    val kind: RoadObjectKind,
    val score: Float,
    val box: NormalizedDetectionBox,
)

private data class YoloDecodeResult(
    val detections: List<LocalVisionDetection>,
    val diagnostics: YoloDecodeDiagnostics,
)

private data class YoloDecodeDiagnostics(
    val outputMin: Float,
    val outputMax: Float,
    val bestScore: Float,
    val bestSigmoid: Float,
    val bestClass: String,
    val scoresAt010: Int,
    val scoresAt020: Int,
    val scoresAt035: Int,
    val validBoxesAt035: Int,
    val candidatesBeforeNms: Int,
    val detectionsAfterNms: Int,
    val coordinateSpace: String,
    val coordinateMax: Float,
) {
    fun toLine(): String =
        "outputMin=${formatTelemetryFloat(outputMin)} " +
            "outputMax=${formatTelemetryFloat(outputMax)} " +
            "bestScore=${formatTelemetryFloat(bestScore)} " +
            "bestSigmoid=${formatTelemetryFloat(bestSigmoid)} " +
            "bestClass=$bestClass scores010=$scoresAt010 scores020=$scoresAt020 " +
            "scores035=$scoresAt035 validBoxes035=$validBoxesAt035 " +
            "beforeNms=$candidatesBeforeNms afterNms=$detectionsAfterNms " +
            "coordinates=$coordinateSpace coordinateMax=${formatTelemetryFloat(coordinateMax)}"
}

private fun validateYoloInputSpec(spec: TensorSpec) {
    require(spec.shape.size == 4 && spec.shape[0] == 1 && spec.shape[3] == 3) {
        "Entrada YOLO inesperada ${spec.shape.contentToString()}"
    }
    require(spec.dataType in setOf(DataType.FLOAT32, DataType.UINT8, DataType.INT8)) {
        "Input type ${spec.dataType}"
    }
}

private fun directTensorBuffer(spec: TensorSpec): ByteBuffer =
    ByteBuffer.allocateDirect(spec.elements * spec.bytesPerElement).order(ByteOrder.nativeOrder())

private fun loadMappedAsset(context: Context, assetName: String): MappedByteBuffer {
    val descriptor = context.assets.openFd(assetName)
    return descriptor.createInputStream().channel.use { channel ->
        channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
    }.also { descriptor.close() }
}

private fun fillYoloInput(
    frame: VisionRgbFrame,
    target: ByteBuffer,
    spec: TensorSpec,
): LetterboxTransform {
    val scale = min(spec.width.toFloat() / frame.width, spec.height.toFloat() / frame.height)
    val scaledWidth = frame.width * scale
    val scaledHeight = frame.height * scale
    val padX = (spec.width - scaledWidth) / 2f
    val padY = (spec.height - scaledHeight) / 2f
    val transform = LetterboxTransform(
        frame.width,
        frame.height,
        spec.width,
        spec.height,
        scale,
        padX,
        padY,
    )
    target.clear()
    repeat(spec.height) { outputY ->
        repeat(spec.width) { outputX ->
            val inside = outputX >= padX && outputX < padX + scaledWidth &&
                outputY >= padY && outputY < padY + scaledHeight
            if (!inside) {
                repeat(3) { putNormalizedChannel(target, LETTERBOX_COLOR, spec) }
            } else {
                val sourceX = ((outputX - padX) / scale).toInt().coerceIn(0, frame.width - 1)
                val sourceY = ((outputY - padY) / scale).toInt().coerceIn(0, frame.height - 1)
                val rgb = frame.rgb(sourceX, sourceY)
                putNormalizedChannel(target, rgb shr 16 and 0xff, spec)
                putNormalizedChannel(target, rgb shr 8 and 0xff, spec)
                putNormalizedChannel(target, rgb and 0xff, spec)
            }
        }
    }
    target.rewind()
    return transform
}

private fun putNormalizedChannel(target: ByteBuffer, channel: Int, spec: TensorSpec) {
    val normalized = channel / 255f
    when (spec.dataType) {
        DataType.FLOAT32 -> target.putFloat(normalized)
        DataType.UINT8 -> target.put(quantize(normalized, spec, 0, 255).toByte())
        DataType.INT8 -> target.put(quantize(normalized, spec, -128, 127).toByte())
        else -> error("Entrada ${spec.dataType} no soportada")
    }
}

private fun quantize(value: Float, spec: TensorSpec, minimum: Int, maximum: Int): Int {
    require(spec.scale > 0f) { "Invalid quantisation scale" }
    return (value / spec.scale + spec.zeroPoint).roundToInt().coerceIn(minimum, maximum)
}

internal fun decodeYoloOutput(
    buffer: ByteBuffer,
    spec: TensorSpec,
    transform: LetterboxTransform,
    scoreThreshold: Float,
    iouThreshold: Float,
): List<LocalVisionDetection> = decodeYoloOutputDetailed(
    buffer,
    spec,
    transform,
    scoreThreshold,
    iouThreshold,
).detections

private fun decodeYoloOutputDetailed(
    buffer: ByteBuffer,
    spec: TensorSpec,
    transform: LetterboxTransform,
    scoreThreshold: Float,
    iouThreshold: Float,
): YoloDecodeResult {
    require(spec.shape.size == 3 && spec.shape[0] == 1) {
        "Salida YOLO inesperada ${spec.shape.contentToString()}"
    }
    val values = FloatArray(spec.elements)
    var outputMin = Float.POSITIVE_INFINITY
    var outputMax = Float.NEGATIVE_INFINITY
    repeat(values.size) { index ->
        values[index] = readTensorValue(buffer, spec)
        outputMin = min(outputMin, values[index])
        outputMax = max(outputMax, values[index])
    }
    val first = spec.shape[1]
    val second = spec.shape[2]
    val channelFirst = first in 6..256 && second > first
    val featureCount = if (channelFirst) first else second
    val candidateCount = if (channelFirst) second else first
    require(featureCount >= 6) { "Caracteristicas YOLO=$featureCount" }

    fun value(candidate: Int, feature: Int): Float = if (channelFirst) {
        values[feature * candidateCount + candidate]
    } else {
        values[candidate * featureCount + feature]
    }

    // Ultralytics exports exist in both pixel-coordinate and 0..1 coordinate variants. This
    // particular fully-quantized 320 model returns normalized xywh values (confirmed on-device:
    // the complete output remains around 0..1.3). Treating those values as 320px coordinates
    // puts every box inside the vertical letterbox padding and causes it to be rejected.
    var coordinateMax = 0f
    repeat(candidateCount) { candidate ->
        repeat(4) { feature ->
            val coordinate = value(candidate, feature)
            if (coordinate.isFinite()) coordinateMax = max(coordinateMax, kotlin.math.abs(coordinate))
        }
    }
    val normalizedCoordinates = coordinateMax <= NORMALIZED_COORDINATE_MAX
    val coordinateScaleX = if (normalizedCoordinates) transform.modelWidth.toFloat() else 1f
    val coordinateScaleY = if (normalizedCoordinates) transform.modelHeight.toFloat() else 1f

    val candidates = ArrayList<YoloCandidate>()
    var globalBestScore = Float.NEGATIVE_INFINITY
    var bestClass = "none"
    var scoresAt010 = 0
    var scoresAt020 = 0
    var scoresAt035 = 0
    var validBoxesAt035 = 0
    repeat(candidateCount) { candidate ->
        var bestKind: RoadObjectKind? = null
        var candidateBestScore = Float.NEGATIVE_INFINITY
        COCO_ROAD_CLASSES.forEach { (classIndex, kind) ->
            val feature = 4 + classIndex
            if (feature < featureCount) {
                val score = value(candidate, feature)
                if (score > candidateBestScore) {
                    candidateBestScore = score
                    bestKind = kind
                }
                if (score >= 0.10f) scoresAt010++
                if (score >= 0.20f) scoresAt020++
                if (score >= 0.35f) scoresAt035++
            }
        }
        val selectedKind = bestKind ?: return@repeat
        if (candidateBestScore > globalBestScore) {
            globalBestScore = candidateBestScore
            bestClass = selectedKind.modelLabel
        }
        val centerX = value(candidate, 0) * coordinateScaleX
        val centerY = value(candidate, 1) * coordinateScaleY
        val width = value(candidate, 2) * coordinateScaleX
        val height = value(candidate, 3) * coordinateScaleY
        val box = modelBoxToSource(
            centerX - width / 2f,
            centerY - height / 2f,
            centerX + width / 2f,
            centerY + height / 2f,
            transform,
        ) ?: return@repeat
        if (candidateBestScore >= scoreThresholdFor(selectedKind, scoreThreshold)) {
            validBoxesAt035++
            candidates += YoloCandidate(selectedKind, candidateBestScore, box)
        }
    }
    val detections = nonMaximumSuppression(candidates, iouThreshold, YOLO_MAX_RESULTS)
        .map { LocalVisionDetection(it.kind, it.score, it.box) }
    return YoloDecodeResult(
        detections = detections,
        diagnostics = YoloDecodeDiagnostics(
            outputMin = outputMin,
            outputMax = outputMax,
            bestScore = globalBestScore,
            bestSigmoid = if (globalBestScore.isFinite()) sigmoid(globalBestScore) else 0f,
            bestClass = bestClass,
            scoresAt010 = scoresAt010,
            scoresAt020 = scoresAt020,
            scoresAt035 = scoresAt035,
            validBoxesAt035 = validBoxesAt035,
            candidatesBeforeNms = candidates.size,
            detectionsAfterNms = detections.size,
            coordinateSpace = if (normalizedCoordinates) "normalized" else "pixels",
            coordinateMax = coordinateMax,
        ),
    )
}

/** Traffic lights occupy very few pixels in the 320px road model. Tracking still requires two
 * observations, so a slightly lower candidate threshold improves recall without making a single
 * weak response visible to the driver. */
private fun scoreThresholdFor(kind: RoadObjectKind, defaultThreshold: Float): Float = when (kind) {
    RoadObjectKind.TRAFFIC_LIGHT -> 0.22f
    else -> defaultThreshold
}

private fun sigmoid(value: Float): Float =
    (1f / (1f + kotlin.math.exp(-value))).coerceIn(0f, 1f)

private const val NORMALIZED_COORDINATE_MAX = 2f

private fun readTensorValue(buffer: ByteBuffer, spec: TensorSpec): Float = when (spec.dataType) {
    DataType.FLOAT32 -> buffer.float
    DataType.UINT8 -> ((buffer.get().toInt() and 0xff) - spec.zeroPoint) * spec.scale
    DataType.INT8 -> (buffer.get().toInt() - spec.zeroPoint) * spec.scale
    else -> error("Salida ${spec.dataType} no soportada")
}

private fun modelBoxToSource(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    transform: LetterboxTransform,
): NormalizedDetectionBox? = normalizedDetectionBox(
    left = (left - transform.padX) / transform.scale,
    top = (top - transform.padY) / transform.scale,
    right = (right - transform.padX) / transform.scale,
    bottom = (bottom - transform.padY) / transform.scale,
    imageWidth = transform.sourceWidth,
    imageHeight = transform.sourceHeight,
)

internal fun nonMaximumSuppression(
    candidates: List<YoloCandidate>,
    iouThreshold: Float,
    limit: Int,
): List<YoloCandidate> {
    val retained = ArrayList<YoloCandidate>()
    candidates.sortedByDescending(YoloCandidate::score).forEach { candidate ->
        if (retained.size >= limit) return@forEach
        val overlaps = retained.any { selected ->
            selected.kind == candidate.kind && intersectionOverUnion(selected.box, candidate.box) > iouThreshold
        }
        if (!overlaps) retained += candidate
    }
    return retained
}

internal fun intersectionOverUnion(a: NormalizedDetectionBox, b: NormalizedDetectionBox): Float {
    val intersectionWidth = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0f)
    val intersectionHeight = (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0f)
    val intersection = intersectionWidth * intersectionHeight
    val areaA = (a.right - a.left).coerceAtLeast(0f) * (a.bottom - a.top).coerceAtLeast(0f)
    val areaB = (b.right - b.left).coerceAtLeast(0f) * (b.bottom - b.top).coerceAtLeast(0f)
    val union = areaA + areaB - intersection
    return if (union <= 0f) 0f else intersection / union
}

internal data class LocalVisionDetectorState(
    val initializing: Boolean = false,
    val ready: Boolean = false,
    val active: Boolean = false,
    val status: String = "YOLO not started",
    val model: String = "YOLOv8n INT8 320x320 / LiteRT CPU",
    val offeredFrames: Long = 0L,
    val processedFrames: Long = 0L,
    val droppedFrames: Long = 0L,
    val rejectedNotReadyFrames: Long = 0L,
    val rejectedBusyFrames: Long = 0L,
    val rejectedIntervalFrames: Long = 0L,
    val rejectedThermalFrames: Long = 0L,
    val rejectedMemoryFrames: Long = 0L,
    val thermalPauseEvents: Long = 0L,
    val memoryPauseEvents: Long = 0L,
    val detectionEvents: Long = 0L,
    val inferenceMs: Long = 0L,
    val minimumInferenceMs: Long = 0L,
    val averageInferenceMs: Float = 0f,
    val p95InferenceMs: Long = 0L,
    val maximumInferenceMs: Long = 0L,
    val adaptiveIntervalMs: Long = 1_500L,
    val lastOfferedElapsedRealtime: Long = 0L,
    val lastResultElapsedRealtime: Long = 0L,
    val detections: List<LocalVisionDetection> = emptyList(),
    val trackedObjects: List<TrackedRoadObject> = emptyList(),
    val tentativeTracks: Int = 0,
    val totalTracksCreated: Long = 0L,
    val totalTracksConfirmed: Long = 0L,
    val totalTracksExpired: Long = 0L,
    val detectionCounts: Map<String, Long> = emptyMap(),
    val detectionHistory: List<String> = emptyList(),
    val trackingHistory: List<String> = emptyList(),
    val speedSignCandidates: List<SpeedSignCandidate> = emptyList(),
    val speedSignCandidateEvents: Long = 0L,
    val errors: List<String> = emptyList(),
)

internal data class LocalVisionDetection(
    val kind: RoadObjectKind,
    val score: Float,
    val box: NormalizedDetectionBox,
    val trafficLightColor: TrafficLightColor = TrafficLightColor.UNKNOWN,
    val trafficLightColorConfidence: Float = 0f,
    val semanticDetails: String? = null,
) {
    fun logLine(): String = String.format(
        Locale.US,
        "%s %.1f%% box=[%.3f,%.3f,%.3f,%.3f]",
        kind.label,
        score * 100f,
        box.left,
        box.top,
        box.right,
        box.bottom,
    ) + semanticDetails?.let { " $it" }.orEmpty()
}

internal data class NormalizedDetectionBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal enum class RoadObjectKind(val modelLabel: String, val label: String) {
    PERSON("person", "Peaton"),
    BICYCLE("bicycle", "Bicicleta"),
    CAR("car", "Coche"),
    MOTORCYCLE("motorcycle", "Moto"),
    BUS("bus", "Autobus"),
    TRUCK("truck", "Camion"),
    TRAFFIC_LIGHT("traffic light", "Semaforo"),
    STOP_SIGN("stop sign", "Senal STOP"),
}

internal fun roadObjectKind(label: String): RoadObjectKind? =
    RoadObjectKind.entries.firstOrNull { it.modelLabel.equals(label.trim(), ignoreCase = true) }

internal fun normalizedDetectionBox(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    imageWidth: Int,
    imageHeight: Int,
): NormalizedDetectionBox? {
    if (imageWidth <= 0 || imageHeight <= 0) return null
    val normalizedLeft = (left / imageWidth).coerceIn(0f, 1f)
    val normalizedTop = (top / imageHeight).coerceIn(0f, 1f)
    val normalizedRight = (right / imageWidth).coerceIn(0f, 1f)
    val normalizedBottom = (bottom / imageHeight).coerceIn(0f, 1f)
    if (normalizedRight <= normalizedLeft || normalizedBottom <= normalizedTop) return null
    return NormalizedDetectionBox(normalizedLeft, normalizedTop, normalizedRight, normalizedBottom)
}

private const val LETTERBOX_COLOR = 114
private const val SPEED_SIGN_HISTORY_INTERVAL_MS = 5_000L
private val COCO_ROAD_CLASSES = mapOf(
    0 to RoadObjectKind.PERSON,
    1 to RoadObjectKind.BICYCLE,
    2 to RoadObjectKind.CAR,
    3 to RoadObjectKind.MOTORCYCLE,
    5 to RoadObjectKind.BUS,
    7 to RoadObjectKind.TRUCK,
    9 to RoadObjectKind.TRAFFIC_LIGHT,
    11 to RoadObjectKind.STOP_SIGN,
)
