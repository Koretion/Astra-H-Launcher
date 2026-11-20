package com.ts10.inspector.vision

import com.ts10.inspector.ui.UiText
import com.ts10.inspector.R
import android.content.Context
import android.media.Image
import android.os.SystemClock
import com.ts10.inspector.camera.GrabadoraHdCameraClient
import com.ts10.inspector.diagnostics.TelemetryLog
import com.ts10.inspector.settings.DashboardPreferences
import com.ts10.inspector.settings.LocalVisionCaptureMode
import com.ts10.inspector.settings.LocalVisionMode
import com.ts10.inspector.settings.LocalVisionSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import java.nio.ByteBuffer

internal data class SharedVisionFrame(
    val sequence: Long,
    val sourceName: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val capturedElapsedRealtime: Long,
    val publishedElapsedRealtime: Long,
    val rgb: VisionRgbFrame,
)

/**
 * Single-copy, latest-only handoff between camera sources and vision consumers.
 *
 * ImageReader owns [Image], therefore this hub makes one small 16:9 RGB snapshot before
 * the callback returns. Every expensive operation happens later on consumer threads.
 */
internal object VisionFrameHub {
    private const val FRAME_WIDTH = 320
    private const val FRAME_HEIGHT = 180
    private const val LATENCY_WINDOW = 120

    private val mutableState = MutableStateFlow(VisionFrameHubState())
    val state: StateFlow<VisionFrameHubState> = mutableState.asStateFlow()
    private val sequence = AtomicLong(0L)
    private val copyWindow = LongArray(LATENCY_WINDOW)
    private var copyWindowCount = 0
    private var copyWindowCursor = 0
    private var firstFrameElapsed = 0L
    private var totalCopyNanos = 0L
    private val downscaleScratch = RgbaDownscaleScratch()

    fun offer(image: Image, sourceName: String, capturedElapsed: Long): Boolean {
        val mode = LocalVisionPipeline.mode
        val offered = mutableState.value.offeredFrames + 1L
        if (mode == LocalVisionMode.OFF) {
            mutableState.value = mutableState.value.copy(
                mode = mode,
                sourceName = sourceName,
                offeredFrames = offered,
                rejectedModeFrames = mutableState.value.rejectedModeFrames + 1L,
                status = "Vision off",
            )
            return false
        }

        val copyStarted = SystemClock.elapsedRealtimeNanos()
        val rgb = runCatching {
            copySharedRgbFrame(image, FRAME_WIDTH, FRAME_HEIGHT, downscaleScratch)
        }
            .getOrElse { error ->
                mutableState.value = mutableState.value.copy(
                    mode = mode,
                    sourceName = sourceName,
                    offeredFrames = offered,
                    conversionErrors = mutableState.value.conversionErrors + 1L,
                    status = "FrameHub: ${error.javaClass.simpleName}",
                )
                return false
            }
        val copyNanos = SystemClock.elapsedRealtimeNanos() - copyStarted
        recordCopy(copyNanos)
        val published = SystemClock.elapsedRealtime()
        if (firstFrameElapsed == 0L) firstFrameElapsed = published
        val frameNumber = sequence.incrementAndGet()
        val frame = SharedVisionFrame(
            sequence = frameNumber,
            sourceName = sourceName,
            sourceWidth = image.width,
            sourceHeight = image.height,
            capturedElapsedRealtime = capturedElapsed,
            publishedElapsedRealtime = published,
            rgb = rgb,
        )
        val elapsed = (published - firstFrameElapsed).coerceAtLeast(1L)
        val publishedFrames = mutableState.value.publishedFrames + 1L
        mutableState.value = mutableState.value.copy(
            mode = mode,
            sourceName = sourceName,
            status = if (mode == LocalVisionMode.CAPTURE_ONLY) "Capture only" else "Shared frame active",
            offeredFrames = offered,
            publishedFrames = publishedFrames,
            lastSequence = frameNumber,
            lastFrameElapsedRealtime = published,
            sourceWidth = image.width,
            sourceHeight = image.height,
            frameWidth = rgb.width,
            frameHeight = rgb.height,
            fps = if (publishedFrames <= 1L) 0f else (publishedFrames - 1L) * 1_000f / elapsed,
            copyLastMs = copyNanos / 1_000_000f,
            copyAverageMs = totalCopyNanos / publishedFrames.coerceAtLeast(1L) / 1_000_000f,
            copyP95Ms = copyP95Nanos() / 1_000_000f,
            copyMaximumMs = maxOf(mutableState.value.copyMaximumMs, copyNanos / 1_000_000f),
        )

        if (mode == LocalVisionMode.ACTIVE) {
            LocalVisionLaneDetector.offer(frame)
            if (LocalVisionPipeline.objectsEnabled) LocalVisionObjectDetector.offer(frame)
        }
        return true
    }

    fun offerRgbx(
        buffer: ByteBuffer,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceName: String,
        capturedElapsed: Long,
        bufferWidth: Int = sourceWidth,
        bufferHeight: Int = sourceHeight,
        flipVertical: Boolean = false,
    ): Boolean {
        val mode = LocalVisionPipeline.mode
        val offered = mutableState.value.offeredFrames + 1L
        if (mode == LocalVisionMode.OFF) {
            mutableState.value = mutableState.value.copy(
                mode = mode,
                sourceName = sourceName,
                offeredFrames = offered,
                rejectedModeFrames = mutableState.value.rejectedModeFrames + 1L,
                status = "Vision off",
            )
            return false
        }
        val copyStarted = SystemClock.elapsedRealtimeNanos()
        val rgb = runCatching {
            copyRgbxToVisionFrame(
                buffer = buffer,
                sourceWidth = bufferWidth,
                sourceHeight = bufferHeight,
                width = FRAME_WIDTH,
                height = FRAME_HEIGHT,
                flipVertical = flipVertical,
                scratch = downscaleScratch,
            )
        }.getOrElse { error ->
            mutableState.value = mutableState.value.copy(
                mode = mode,
                sourceName = sourceName,
                offeredFrames = offered,
                conversionErrors = mutableState.value.conversionErrors + 1L,
                status = "FrameHub UVC: ${error.javaClass.simpleName}",
            )
            return false
        }
        return publish(
            rgb = rgb,
            sourceName = sourceName,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            capturedElapsed = capturedElapsed,
            offered = offered,
            copyNanos = SystemClock.elapsedRealtimeNanos() - copyStarted,
            mode = mode,
        )
    }

    fun reset(reason: String) {
        firstFrameElapsed = 0L
        totalCopyNanos = 0L
        copyWindowCount = 0
        copyWindowCursor = 0
        mutableState.value = VisionFrameHubState(mode = LocalVisionPipeline.mode, status = reason)
        LocalVisionObjectDetector.clearObservations(reason)
        LocalVisionLaneDetector.clearObservations(reason)
    }

    private fun recordCopy(nanos: Long) {
        totalCopyNanos += nanos
        copyWindow[copyWindowCursor] = nanos
        copyWindowCursor = (copyWindowCursor + 1) % copyWindow.size
        if (copyWindowCount < copyWindow.size) copyWindowCount += 1
    }

    private fun copyP95Nanos(): Long {
        if (copyWindowCount == 0) return 0L
        val values = copyWindow.copyOf(copyWindowCount).sortedArray()
        return values[((values.size - 1) * 0.95f).toInt().coerceIn(values.indices)]
    }

    private fun publish(
        rgb: VisionRgbFrame,
        sourceName: String,
        sourceWidth: Int,
        sourceHeight: Int,
        capturedElapsed: Long,
        offered: Long,
        copyNanos: Long,
        mode: LocalVisionMode,
    ): Boolean {
        recordCopy(copyNanos)
        val published = SystemClock.elapsedRealtime()
        if (firstFrameElapsed == 0L) firstFrameElapsed = published
        val frameNumber = sequence.incrementAndGet()
        val frame = SharedVisionFrame(
            sequence = frameNumber,
            sourceName = sourceName,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            capturedElapsedRealtime = capturedElapsed,
            publishedElapsedRealtime = published,
            rgb = rgb,
        )
        val elapsed = (published - firstFrameElapsed).coerceAtLeast(1L)
        val publishedFrames = mutableState.value.publishedFrames + 1L
        mutableState.value = mutableState.value.copy(
            mode = mode,
            sourceName = sourceName,
            status = if (mode == LocalVisionMode.CAPTURE_ONLY) "Capture only" else "Shared frame active",
            offeredFrames = offered,
            publishedFrames = publishedFrames,
            lastSequence = frameNumber,
            lastFrameElapsedRealtime = published,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            frameWidth = rgb.width,
            frameHeight = rgb.height,
            fps = if (publishedFrames <= 1L) 0f else (publishedFrames - 1L) * 1_000f / elapsed,
            copyLastMs = copyNanos / 1_000_000f,
            copyAverageMs = totalCopyNanos / publishedFrames.coerceAtLeast(1L) / 1_000_000f,
            copyP95Ms = copyP95Nanos() / 1_000_000f,
            copyMaximumMs = maxOf(mutableState.value.copyMaximumMs, copyNanos / 1_000_000f),
        )
        if (mode == LocalVisionMode.ACTIVE) {
            LocalVisionLaneDetector.offer(frame)
            if (LocalVisionPipeline.objectsEnabled) LocalVisionObjectDetector.offer(frame)
        }
        return true
    }
}

internal object LocalVisionPipeline {
    @Volatile var mode: LocalVisionMode = LocalVisionMode.ACTIVE
        private set
    @Volatile var source: LocalVisionSource = LocalVisionSource.DASHCAM
        private set
    @Volatile var captureMode: LocalVisionCaptureMode = LocalVisionCaptureMode.PRIVATE_GL
        private set
    @Volatile var objectsEnabled: Boolean = false
        private set
    private var applicationContext: Context? = null

    fun start(context: Context) {
        applicationContext = context.applicationContext
        TelemetryLog.initialize(context)
        val settings = DashboardPreferences.current(context)
        source = settings.localVisionSource
        captureMode = settings.localVisionCaptureMode
        objectsEnabled = settings.localVisionObjectsEnabled
        applyMode(settings.localVisionMode)
        if (source == LocalVisionSource.USB_UVC) UvcDirectVisionProbe.start(context)
    }

    fun applySource(newSource: LocalVisionSource) {
        if (source == newSource) return
        source = newSource
        TelemetryLog.event("FRAME_HUB", "source=$newSource")
        val context = applicationContext ?: return
        // La fuente se cambia antes de parar UVC porque al parar se reanuda la Surface de la dashcam.
        when (newSource) {
            LocalVisionSource.USB_UVC -> UvcDirectVisionProbe.start(context)
            LocalVisionSource.DASHCAM -> UvcDirectVisionProbe.stop(UiText.of(R.string.uvc_source_dashcam))
        }
    }

    /** El visor USB a pantalla completa necesita el dispositivo en exclusiva. */
    fun releaseUsbForViewer() {
        if (source == LocalVisionSource.USB_UVC) UvcDirectVisionProbe.stop(UiText.of(R.string.uvc_yielded))
    }

    fun reclaimUsbAfterViewer() {
        val context = applicationContext ?: return
        if (source == LocalVisionSource.USB_UVC) UvcDirectVisionProbe.start(context)
    }

    fun applyMode(newMode: LocalVisionMode) {
        mode = newMode
        val context = applicationContext
        when (newMode) {
            LocalVisionMode.ACTIVE -> if (context != null) {
                LocalVisionLaneDetector.start()
                if (objectsEnabled) LocalVisionObjectDetector.start(context)
                else LocalVisionObjectDetector.stop("Objects off; lanes take priority")
            }
            LocalVisionMode.CAPTURE_ONLY -> {
                LocalVisionObjectDetector.stop("YOLO stopped: capture only")
                LocalVisionLaneDetector.stop("Lanes stopped: capture only")
            }
            LocalVisionMode.OFF -> {
                LocalVisionObjectDetector.stop("Local vision off")
                LocalVisionLaneDetector.stop("Local vision off")
            }
        }
        TelemetryLog.event("FRAME_HUB", "mode=$newMode")
    }

    fun applyObjectDetectionEnabled(enabled: Boolean) {
        objectsEnabled = enabled
        val context = applicationContext
        if (enabled && mode == LocalVisionMode.ACTIVE && context != null) {
            LocalVisionObjectDetector.start(context)
        } else {
            LocalVisionObjectDetector.stop("Objects off; lanes take priority")
        }
        TelemetryLog.event("FRAME_HUB", "objectsEnabled=$enabled")
    }

    fun applyCaptureMode(newMode: LocalVisionCaptureMode) {
        if (captureMode == newMode) return
        captureMode = newMode
        TelemetryLog.event("FRAME_HUB", "captureMode=$newMode")
        GrabadoraHdCameraClient.restartVisionProbeForCaptureMode()
    }
}

internal data class VisionFrameHubState(
    val mode: LocalVisionMode = LocalVisionMode.ACTIVE,
    val sourceName: String = "--",
    val status: String = "Waiting for frame",
    val offeredFrames: Long = 0L,
    val publishedFrames: Long = 0L,
    val rejectedModeFrames: Long = 0L,
    val conversionErrors: Long = 0L,
    val lastSequence: Long = 0L,
    val lastFrameElapsedRealtime: Long = 0L,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val fps: Float = 0f,
    val copyLastMs: Float = 0f,
    val copyAverageMs: Float = 0f,
    val copyP95Ms: Float = 0f,
    val copyMaximumMs: Float = 0f,
)

internal class RgbaDownscaleScratch {
    private var bytes = ByteArray(0)

    fun bytes(size: Int): ByteArray {
        if (bytes.size < size) bytes = ByteArray(size)
        return bytes
    }
}

internal fun copySharedRgbFrame(
    image: Image,
    width: Int,
    height: Int,
    scratch: RgbaDownscaleScratch = RgbaDownscaleScratch(),
): VisionRgbFrame {
    require(image.planes.size == 1) { "planes=${image.planes.size}" }
    val plane = image.planes.single()
    require(plane.pixelStride >= 3) { "pixelStride=${plane.pixelStride}" }
    val source = plane.buffer.duplicate()
    val sourceBase = source.position()
    val rowBytes = (image.width * plane.pixelStride).coerceAtMost(plane.rowStride)
    val row = scratch.bytes(rowBytes)
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val sourceY = y * image.height / height
        val rowStart = sourceBase + sourceY * plane.rowStride
        require(rowStart >= sourceBase && rowStart + rowBytes <= source.limit()) {
            "RGBA row out of range y=$sourceY start=$rowStart bytes=$rowBytes limit=${source.limit()}"
        }
        source.position(rowStart)
        source.get(row, 0, rowBytes)
        for (x in 0 until width) {
            val sourceX = x * image.width / width
            val position = sourceX * plane.pixelStride
            require(position + 2 < rowBytes) { "Shared RGB out of range" }
            val r = row[position].toInt() and 0xff
            val g = row[position + 1].toInt() and 0xff
            val b = row[position + 2].toInt() and 0xff
            pixels[y * width + x] = (r shl 16) or (g shl 8) or b
        }
    }
    return VisionRgbFrame(width, height, pixels)
}

internal fun copyRgbxToVisionFrame(
    buffer: ByteBuffer,
    sourceWidth: Int,
    sourceHeight: Int,
    width: Int,
    height: Int,
    flipVertical: Boolean = false,
    scratch: RgbaDownscaleScratch = RgbaDownscaleScratch(),
): VisionRgbFrame {
    require(sourceWidth > 0 && sourceHeight > 0) { "Tamano UVC invalido" }
    val source = buffer.duplicate()
    val required = sourceWidth.toLong() * sourceHeight * 4L
    require(source.remaining().toLong() >= required) {
        "RGBX corto ${source.remaining()}/$required"
    }
    val base = source.position()
    val sourceRowBytes = sourceWidth * 4
    val row = scratch.bytes(sourceRowBytes)
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val outputSourceY = y * sourceHeight / height
        val sourceY = if (flipVertical) sourceHeight - 1 - outputSourceY else outputSourceY
        val rowStart = base + sourceY * sourceRowBytes
        source.position(rowStart)
        source.get(row, 0, sourceRowBytes)
        for (x in 0 until width) {
            val sourceX = x * sourceWidth / width
            val position = sourceX * 4
            val r = row[position].toInt() and 0xff
            val g = row[position + 1].toInt() and 0xff
            val b = row[position + 2].toInt() and 0xff
            pixels[y * width + x] = (r shl 16) or (g shl 8) or b
        }
    }
    return VisionRgbFrame(width, height, pixels)
}
