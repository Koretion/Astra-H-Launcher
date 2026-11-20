package com.ts10.inspector.vision

import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import com.ts10.inspector.diagnostics.TelemetryLog
import com.ts10.inspector.settings.LocalVisionCaptureMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.zip.CRC32
import kotlin.math.roundToInt

/**
 * Phase V0 probe for Grabadora HD's private secondary-output Surface API.
 *
 * It never opens the USB device. Grabadora HD remains the sole camera owner and this probe
 * only supplies an ImageReader Surface to its already running camera service.
 */
internal object GrabadoraHdVisionProbe {
    private const val PRIVATE_STARTUP_ATTEMPTS = 3
    private const val PRIVATE_RETRY_TIMEOUT_MS = 2_500L
    private const val STANDARD_CANDIDATE_TIMEOUT_MS = 7_000L
    private const val STABLE_WATCHDOG_MS = 5_000L
    private const val STALL_RESTART_DELAY_MS = 1_000L

    private val mutableState = MutableStateFlow(GrabadoraHdVisionProbeState())
    val state: StateFlow<GrabadoraHdVisionProbeState> = mutableState.asStateFlow()

    private val thread = HandlerThread("TS10-grabadora-frame-probe").apply { start() }
    private val handler = Handler(thread.looper)
    private var bridge: GrabadoraHdSurfaceBridge? = null
    private var cameraId: Int? = null
    private var reader: ImageReader? = null
    private var privateGlSource: PrivateSurfaceGlFrameSource? = null
    private var attachedSurface: Surface? = null
    private var candidateIndex = 0
    private var generation = 0
    private var round = 0
    private var candidateFrames = 0L
    private var candidateCallbacks = 0L
    private var incompatibleFrames = 0L
    private var lastWatchdogFrames = 0L
    private var locked = false
    private var startedElapsed = 0L
    private var firstFrameElapsed = 0L
    private var processCpuStarted = 0L
    private var callbackCpuNanos = 0L
    private var maxCallbackNanos = 0L
    private var gpuReadbackTotalNanos = 0L
    private var gpuReadbackLastNanos = 0L
    private var gpuReadbackMaxNanos = 0L
    private var gpuReadbackSamples = 0L
    private var gpuReadbackCount = 0
    private var gpuReadbackCursor = 0
    private val gpuReadbackWindow = LongArray(120)
    private val attempts = mutableListOf<String>()
    private var candidates: List<ProbeCandidate> = emptyList()

    fun start(cameraId: Int, bridge: GrabadoraHdSurfaceBridge) {
        if (cameraId < 0) return
        handler.post {
            if (this.cameraId == cameraId && this.bridge != null && hasFrameSource()) return@post
            stopInternal("Restart for new camera", retainAttempts = false)
            this.cameraId = cameraId
            this.bridge = bridge
            candidateIndex = 0
            round = 1
            attempts.clear()
            candidates = candidatesFor(LocalVisionPipeline.captureMode)
            TelemetryLog.event("CAMERA_PROBE", "start camera=$cameraId")
            startCandidate()
        }
    }

    fun stop(reason: String = "Probe stopped") {
        handler.post { stopInternal(reason, retainAttempts = true) }
    }

    private fun startCandidate() {
        val activeCamera = cameraId ?: return
        val activeBridge = bridge ?: return
        val candidate = candidates[candidateIndex]
        generation += 1
        val activeGeneration = generation
        candidateFrames = 0L
        candidateCallbacks = 0L
        incompatibleFrames = 0L
        lastWatchdogFrames = 0L
        locked = false
        startedElapsed = SystemClock.elapsedRealtime()
        firstFrameElapsed = 0L
        processCpuStarted = Process.getElapsedCpuTime()
        callbackCpuNanos = 0L
        maxCallbackNanos = 0L
        gpuReadbackTotalNanos = 0L
        gpuReadbackLastNanos = 0L
        gpuReadbackMaxNanos = 0L
        gpuReadbackSamples = 0L
        gpuReadbackCount = 0
        gpuReadbackCursor = 0

        val surface = if (candidate.pipeline == ProbePipeline.PRIVATE_GL) {
            val source = runCatching {
                PrivateSurfaceGlFrameSource(
                    sourceWidth = candidate.width,
                    sourceHeight = candidate.height,
                    handler = handler,
                    onFrame = { frame ->
                        if (activeGeneration == generation) processPrivateFrame(frame, candidate)
                    },
                    onError = { error -> handlePrivateError(activeGeneration, candidate, error) },
                )
            }.getOrElse { error ->
                recordAttempt(candidate, "OpenGL ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                advanceCandidate()
                return
            }
            privateGlSource = source
            source.surface
        } else {
            val imageReader = runCatching {
                ImageReader.newInstance(candidate.width, candidate.height, candidate.format, MAX_IMAGES)
            }.getOrElse { error ->
                recordAttempt(candidate, "ImageReader ${error.javaClass.simpleName}")
                advanceCandidate()
                return
            }
            reader = imageReader
            imageReader.setOnImageAvailableListener({ source ->
                if (activeGeneration != generation) {
                    source.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }
                val callbackStarted = SystemClock.elapsedRealtimeNanos()
                val image = runCatching { source.acquireLatestImage() }.getOrNull()
                    ?: return@setOnImageAvailableListener
                try {
                    processFrame(image, candidate)
                } finally {
                    image.close()
                    recordCallbackDuration(SystemClock.elapsedRealtimeNanos() - callbackStarted)
                }
            }, handler)
            imageReader.surface
        }
        attachedSurface = surface
        mutableState.value = GrabadoraHdVisionProbeState(
            active = true,
            cameraId = activeCamera,
            status = "Adjuntando ${candidate.label}",
            candidate = candidate.label,
            width = candidate.width,
            height = candidate.height,
            format = candidate.formatName,
            attempts = attempts.toList(),
            round = round,
        )

        runCatching { activeBridge.attach(activeCamera, surface) }
            .onSuccess {
                mutableState.value = mutableState.value.copy(
                    surfaceAttached = true,
                    status = "Surface accepted; waiting for frames",
                )
                TelemetryLog.event(
                    "CAMERA_PROBE",
                    "surface accepted candidate=${candidate.label}; waiting frames",
                )
                handler.postDelayed({ watchdog(activeGeneration) }, candidate.timeoutMs)
            }
            .onFailure { error ->
                recordAttempt(candidate, "Binder attach ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                releaseCurrentSurface(detach = false)
                advanceCandidate()
            }
    }

    private fun processFrame(image: Image, candidate: ProbeCandidate) {
        val now = SystemClock.elapsedRealtime()
        if (firstFrameElapsed == 0L) firstFrameElapsed = now
        candidateCallbacks += 1
        val totalFrames = mutableState.value.frames + 1
        val elapsed = (now - firstFrameElapsed).coerceAtLeast(1L)
        val fps = if (candidateCallbacks <= 1) 0f else (candidateCallbacks - 1) * 1_000f / elapsed
        val planes = image.planes
        val plane = planes.firstOrNull()
        // CRC/mean are diagnostics, not part of vision. Sampling every callback used to add a
        // second image walk on the ImageReader thread and distorted the measured FPS.
        val sample = if (candidateCallbacks == 1L || candidateCallbacks % SAMPLE_EVERY_FRAMES == 0L) {
            sampleImage(image)
        } else {
            ImageSample(mutableState.value.sampleCrc32, mutableState.value.sampleMean)
        }
        if (!isLocalVisionFrameCompatible(planes.size, plane?.pixelStride)) {
            incompatibleFrames += 1
            mutableState.value = mutableState.value.copy(
                active = true,
                surfaceAttached = true,
                locked = false,
                status = "Frame incompatible: planos=${planes.size} pixelStride=${plane?.pixelStride ?: 0}",
                frames = totalFrames,
                incompatibleFrames = incompatibleFrames,
                fps = fps,
                lastFrameElapsedRealtime = now,
                firstFrameDelayMs = firstFrameElapsed - startedElapsed,
                imageWidth = image.width,
                imageHeight = image.height,
                planeCount = planes.size,
                rowStride = plane?.rowStride,
                pixelStride = plane?.pixelStride,
                sampleCrc32 = sample.crc32,
                sampleMean = sample.mean,
                callbackAverageMs = callbackCpuNanos / candidateCallbacks.coerceAtLeast(1) / 1_000_000f,
                callbackMaxMs = maxCallbackNanos / 1_000_000f,
                processCpuPercent = processCpuPercent(now),
                attempts = attempts.toList(),
            )
            return
        }
        candidateFrames += 1
        if (locked) VisionFrameHub.offer(image, FRAME_SOURCE_NAME, now)
        if (!locked && candidateFrames >= LOCK_AFTER_FRAMES) {
            locked = true
            recordAttempt(candidate, "OK: $candidateFrames frames")
        }
        mutableState.value = mutableState.value.copy(
            active = true,
            surfaceAttached = true,
            locked = locked,
            status = if (locked) "Stable capture" else "Receiving frames",
            frames = totalFrames,
            candidateFrames = candidateFrames,
            incompatibleFrames = incompatibleFrames,
            fps = fps,
            lastFrameElapsedRealtime = now,
            firstFrameDelayMs = firstFrameElapsed - startedElapsed,
            imageWidth = image.width,
            imageHeight = image.height,
            planeCount = planes.size,
            rowStride = plane?.rowStride,
            pixelStride = plane?.pixelStride,
            sampleCrc32 = sample.crc32,
            sampleMean = sample.mean,
            callbackAverageMs = callbackCpuNanos / candidateCallbacks.coerceAtLeast(1) / 1_000_000f,
            callbackMaxMs = maxCallbackNanos / 1_000_000f,
            processCpuPercent = processCpuPercent(now),
            attempts = attempts.toList(),
        )
    }

    private fun processPrivateFrame(frame: PrivateGlFrame, candidate: ProbeCandidate) {
        val processingStarted = SystemClock.elapsedRealtimeNanos()
        val now = frame.capturedElapsedRealtime
        if (firstFrameElapsed == 0L) firstFrameElapsed = now
        candidateCallbacks += 1
        candidateFrames += 1
        val totalFrames = mutableState.value.frames + 1
        val elapsed = (now - firstFrameElapsed).coerceAtLeast(1L)
        val fps = if (candidateCallbacks <= 1) 0f else (candidateCallbacks - 1) * 1_000f / elapsed
        val sample = if (candidateCallbacks == 1L || candidateCallbacks % SAMPLE_EVERY_FRAMES == 0L) {
            sampleBuffer(frame.rgba)
        } else {
            ImageSample(mutableState.value.sampleCrc32, mutableState.value.sampleMean)
        }
        if (locked) {
            VisionFrameHub.offerRgbx(
                buffer = frame.rgba,
                sourceWidth = candidate.width,
                sourceHeight = candidate.height,
                sourceName = FRAME_SOURCE_PRIVATE_NAME,
                capturedElapsed = now,
                bufferWidth = frame.width,
                bufferHeight = frame.height,
                flipVertical = true,
            )
        }
        if (!locked && candidateFrames >= LOCK_AFTER_FRAMES) {
            locked = true
            recordAttempt(candidate, "OK: $candidateFrames frames")
        }
        recordGpuReadback(frame.gpuReadbackNanos)
        recordCallbackDuration(
            frame.gpuReadbackNanos + (SystemClock.elapsedRealtimeNanos() - processingStarted),
        )
        mutableState.value = mutableState.value.copy(
            active = true,
            surfaceAttached = true,
            locked = locked,
            status = if (locked) "Stable PRIVATE/OpenGL capture" else "Receiving PRIVATE texture",
            frames = totalFrames,
            candidateFrames = candidateFrames,
            incompatibleFrames = incompatibleFrames,
            fps = fps,
            lastFrameElapsedRealtime = now,
            firstFrameDelayMs = firstFrameElapsed - startedElapsed,
            imageWidth = candidate.width,
            imageHeight = candidate.height,
            planeCount = 0,
            rowStride = frame.width * 4,
            pixelStride = 4,
            sampleCrc32 = sample.crc32,
            sampleMean = sample.mean,
            callbackAverageMs = callbackCpuNanos / candidateCallbacks.coerceAtLeast(1) / 1_000_000f,
            callbackMaxMs = maxCallbackNanos / 1_000_000f,
            gpuReadbackLastMs = gpuReadbackLastNanos / 1_000_000f,
            gpuReadbackAverageMs = gpuReadbackTotalNanos / gpuReadbackSamples.coerceAtLeast(1L) / 1_000_000f,
            gpuReadbackP95Ms = gpuReadbackP95Nanos() / 1_000_000f,
            gpuReadbackMaximumMs = gpuReadbackMaxNanos / 1_000_000f,
            processCpuPercent = processCpuPercent(now),
            attempts = attempts.toList(),
        )
    }

    private fun handlePrivateError(activeGeneration: Int, candidate: ProbeCandidate, error: Throwable) {
        if (activeGeneration != generation) return
        recordAttempt(candidate, "OpenGL frame ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        releaseCurrentSurface(detach = true)
        advanceCandidate()
    }

    private fun recordCallbackDuration(duration: Long) {
        callbackCpuNanos += duration
        if (duration > maxCallbackNanos) maxCallbackNanos = duration
    }

    private fun recordGpuReadback(duration: Long) {
        gpuReadbackLastNanos = duration
        gpuReadbackTotalNanos += duration
        gpuReadbackSamples += 1L
        if (duration > gpuReadbackMaxNanos) gpuReadbackMaxNanos = duration
        gpuReadbackWindow[gpuReadbackCursor] = duration
        gpuReadbackCursor = (gpuReadbackCursor + 1) % gpuReadbackWindow.size
        if (gpuReadbackCount < gpuReadbackWindow.size) gpuReadbackCount += 1
    }

    private fun gpuReadbackP95Nanos(): Long {
        if (gpuReadbackCount == 0) return 0L
        val values = gpuReadbackWindow.copyOf(gpuReadbackCount).sortedArray()
        return values[((values.size - 1) * 0.95f).toInt().coerceIn(values.indices)]
    }

    private fun watchdog(activeGeneration: Int) {
        if (activeGeneration != generation || !hasFrameSource()) return
        val currentFrames = candidateFrames
        if (!locked) {
            val candidate = candidates[candidateIndex]
            val result = if (incompatibleFrames > 0L) {
                "$incompatibleFrames frames incompatibles en ${candidate.timeoutMs / 1_000f}s"
            } else {
                "No frames in ${candidate.timeoutMs / 1_000f}s"
            }
            recordAttempt(candidate, result)
            releaseCurrentSurface(detach = true)
            advanceCandidate()
            return
        }

        if (currentFrames == lastWatchdogFrames) {
            val state = mutableState.value
            mutableState.value = state.copy(
                status = "Capture stopped: watchdog saw no frames",
                stalls = state.stalls + 1,
                processCpuPercent = processCpuPercent(SystemClock.elapsedRealtime()),
            )
            TelemetryLog.event(
                "CAMERA_PROBE",
                "watchdog stall frames=${state.frames} stalls=${state.stalls + 1}",
            )
            val stalledCandidate = candidates[candidateIndex]
            recordAttempt(stalledCandidate, "watchdog saw no frames; restarting preferred source")
            releaseCurrentSurface(detach = true)
            candidateIndex = 0
            round = 1
            scheduleCandidateStart(STALL_RESTART_DELAY_MS)
            return
        } else {
            lastWatchdogFrames = currentFrames
            mutableState.value = mutableState.value.copy(
                processCpuPercent = processCpuPercent(SystemClock.elapsedRealtime()),
            )
        }
        handler.postDelayed({ watchdog(activeGeneration) }, STABLE_WATCHDOG_MS)
    }

    private fun advanceCandidate() {
        candidateIndex += 1
        if (candidateIndex < candidates.size) {
            scheduleCandidateStart(BETWEEN_CANDIDATES_MS)
            return
        }
        if (round < MAX_ROUNDS) {
            round += 1
            candidateIndex = 0
            mutableState.value = mutableState.value.copy(
                active = false,
                surfaceAttached = false,
                status = "No frames; automatic retry $round/$MAX_ROUNDS",
                attempts = attempts.toList(),
                round = round,
            )
            scheduleCandidateStart(BETWEEN_ROUNDS_MS)
            return
        }
        mutableState.value = mutableState.value.copy(
            active = false,
            surfaceAttached = false,
            status = "Sonda agotada; nuevo intento programado",
            attempts = attempts.toList(),
        )
        val exhaustedGeneration = generation
        handler.postDelayed({
            if (
                exhaustedGeneration == generation &&
                cameraId != null &&
                bridge != null &&
                !hasFrameSource()
            ) {
                candidateIndex = 0
                round = 1
                TelemetryLog.event("CAMERA_PROBE", "retry after exhausted candidates")
                startCandidate()
            }
        }, EXHAUSTED_RETRY_MS)
    }

    /**
     * A camera callback can restart the probe while an earlier watchdog retry is pending.
     * Only let the retry that still belongs to the current generation create a Surface.
     */
    private fun scheduleCandidateStart(delayMs: Long) {
        val scheduledGeneration = generation
        handler.postDelayed({
            if (
                scheduledGeneration == generation &&
                cameraId != null &&
                bridge != null &&
                !hasFrameSource()
            ) {
                startCandidate()
            }
        }, delayMs)
    }

    private fun releaseCurrentSurface(detach: Boolean) {
        generation += 1
        val activeCamera = cameraId
        val surface = attachedSurface
        if (detach && activeCamera != null && surface != null) {
            runCatching { bridge?.detach(activeCamera, surface) }
                .onFailure { error ->
                    attempts += "Detach ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                }
        }
        attachedSurface = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null
        privateGlSource?.close()
        privateGlSource = null
    }

    private fun stopInternal(reason: String, retainAttempts: Boolean) {
        TelemetryLog.event("CAMERA_PROBE", "stop reason=$reason")
        VisionFrameHub.reset(reason)
        releaseCurrentSurface(detach = true)
        cameraId = null
        bridge = null
        locked = false
        mutableState.value = GrabadoraHdVisionProbeState(
            status = reason,
            attempts = if (retainAttempts) attempts.toList() else emptyList(),
        )
        if (!retainAttempts) attempts.clear()
    }

    private fun recordAttempt(candidate: ProbeCandidate, result: String) {
        val event = "R$round ${candidate.label}: $result"
        attempts += event
        TelemetryLog.event("CAMERA_PROBE", event)
        mutableState.value = mutableState.value.copy(attempts = attempts.toList())
    }

    private fun processCpuPercent(now: Long): Float {
        val wallMs = (now - startedElapsed).coerceAtLeast(1L)
        val cpuMs = (Process.getElapsedCpuTime() - processCpuStarted).coerceAtLeast(0L)
        return (cpuMs * 100f / wallMs).coerceAtLeast(0f)
    }

    private fun sampleImage(image: Image): ImageSample {
        val plane = image.planes.firstOrNull() ?: return ImageSample()
        val buffer = plane.buffer.duplicate()
        if (!buffer.hasRemaining()) return ImageSample()
        val samples = minOf(SAMPLE_BYTES, buffer.remaining())
        val step = (buffer.remaining() / samples).coerceAtLeast(1)
        val crc = CRC32()
        var sum = 0L
        var count = 0
        var index = buffer.position()
        val limit = buffer.limit()
        while (index < limit && count < samples) {
            val value = buffer.get(index).toInt() and 0xff
            crc.update(value)
            sum += value
            count += 1
            index += step
        }
        return ImageSample(
            crc32 = crc.value.toString(16).padStart(8, '0'),
            mean = if (count == 0) null else (sum.toFloat() / count).roundToInt(),
        )
    }

    private fun sampleBuffer(sourceBuffer: java.nio.ByteBuffer): ImageSample {
        val buffer = sourceBuffer.duplicate()
        if (!buffer.hasRemaining()) return ImageSample()
        val samples = minOf(SAMPLE_BYTES, buffer.remaining())
        val step = (buffer.remaining() / samples).coerceAtLeast(1)
        val crc = CRC32()
        var sum = 0L
        var count = 0
        var index = buffer.position()
        while (index < buffer.limit() && count < samples) {
            val value = buffer.get(index).toInt() and 0xff
            crc.update(value)
            sum += value
            count += 1
            index += step
        }
        return ImageSample(
            crc32 = crc.value.toString(16).padStart(8, '0'),
            mean = if (count == 0) null else (sum.toFloat() / count).roundToInt(),
        )
    }

    private fun hasFrameSource(): Boolean = reader != null || privateGlSource != null

    private data class ProbeCandidate(
        val width: Int,
        val height: Int,
        val format: Int,
        val formatName: String,
        val pipeline: ProbePipeline = ProbePipeline.IMAGE_READER,
        val timeoutMs: Long = STANDARD_CANDIDATE_TIMEOUT_MS,
    ) {
        val label: String get() = "${width}x$height $formatName"
    }

    private data class ImageSample(val crc32: String? = null, val mean: Int? = null)

    private enum class ProbePipeline { IMAGE_READER, PRIVATE_GL }

    private val PRIVATE_GL_CANDIDATE = ProbeCandidate(
            1280,
            720,
            ImageFormat.PRIVATE,
            "PRIVATE_OPENGL",
            ProbePipeline.PRIVATE_GL,
            PRIVATE_RETRY_TIMEOUT_MS,
        )
    private val RGBA_SMALL_CANDIDATE = ProbeCandidate(640, 360, PixelFormat.RGBA_8888, "RGBA_8888")
    private val RGBA_CANDIDATE = ProbeCandidate(1280, 720, PixelFormat.RGBA_8888, "RGBA_8888")
    private fun candidatesFor(mode: LocalVisionCaptureMode): List<ProbeCandidate> = when (mode) {
        LocalVisionCaptureMode.PRIVATE_GL ->
            List(PRIVATE_STARTUP_ATTEMPTS) { PRIVATE_GL_CANDIDATE } +
                listOf(RGBA_SMALL_CANDIDATE, RGBA_CANDIDATE)
        LocalVisionCaptureMode.RGBA -> listOf(RGBA_SMALL_CANDIDATE, RGBA_CANDIDATE)
    }
    private const val MAX_IMAGES = 2
    private const val LOCK_AFTER_FRAMES = 3L
    private const val BETWEEN_CANDIDATES_MS = 700L
    private const val BETWEEN_ROUNDS_MS = 30_000L
    private const val EXHAUSTED_RETRY_MS = 60_000L
    private const val MAX_ROUNDS = 2
    private const val SAMPLE_BYTES = 1_024
    private const val SAMPLE_EVERY_FRAMES = 30L
    private const val FRAME_SOURCE_NAME = "grabadora-hd-surface"
    private const val FRAME_SOURCE_PRIVATE_NAME = "grabadora-hd-private-gl"
}

internal interface GrabadoraHdSurfaceBridge {
    fun attach(cameraId: Int, surface: Surface)
    fun detach(cameraId: Int, surface: Surface)
}

internal data class GrabadoraHdVisionProbeState(
    val active: Boolean = false,
    val surfaceAttached: Boolean = false,
    val locked: Boolean = false,
    val cameraId: Int? = null,
    val status: String = "Waiting for the Grabadora HD camera",
    val candidate: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val format: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val frames: Long = 0L,
    val candidateFrames: Long = 0L,
    val incompatibleFrames: Long = 0L,
    val fps: Float = 0f,
    val firstFrameDelayMs: Long? = null,
    val lastFrameElapsedRealtime: Long = 0L,
    val planeCount: Int = 0,
    val rowStride: Int? = null,
    val pixelStride: Int? = null,
    val sampleCrc32: String? = null,
    val sampleMean: Int? = null,
    val callbackAverageMs: Float = 0f,
    val callbackMaxMs: Float = 0f,
    val gpuReadbackLastMs: Float = 0f,
    val gpuReadbackAverageMs: Float = 0f,
    val gpuReadbackP95Ms: Float = 0f,
    val gpuReadbackMaximumMs: Float = 0f,
    val processCpuPercent: Float = 0f,
    val stalls: Int = 0,
    val round: Int = 0,
    val attempts: List<String> = emptyList(),
)

internal fun grabadoraSurfaceId(clientId: Int, surfaceHash: Int): Int =
    "${clientId}_$surfaceHash".hashCode()

internal fun isLocalVisionFrameCompatible(planeCount: Int, pixelStride: Int?): Boolean =
    planeCount == 1 && pixelStride != null && pixelStride >= 3
