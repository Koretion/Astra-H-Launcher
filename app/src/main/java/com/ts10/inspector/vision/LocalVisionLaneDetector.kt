package com.ts10.inspector.vision

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import com.ts10.inspector.diagnostics.TelemetryLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Low-cost classical lane probe. Results remain diagnostic until real-road validation. */
internal object LocalVisionLaneDetector {
    private const val FRAME_INTERVAL_MS = 200L
    private const val SAMPLE_WIDTH = 160
    private const val SAMPLE_HEIGHT = 90
    private const val MAX_HISTORY = 60

    private val mutableState = MutableStateFlow(LocalVisionLaneState())
    val state: StateFlow<LocalVisionLaneState> = mutableState.asStateFlow()
    private val thread = HandlerThread("TS10-lane-detector", Process.THREAD_PRIORITY_BACKGROUND)
        .apply { start() }
    private val handler = Handler(thread.looper)
    private val busy = AtomicBoolean(false)
    private val tracker = LocalVisionLaneTracker()

    @Volatile private var started = false
    @Volatile private var lastAcceptedElapsed = 0L

    fun start() {
        started = true
        tracker.reset()
        mutableState.value = LocalVisionLaneState(status = "Lanes ready; waiting for frame")
    }

    fun stop(reason: String) {
        started = false
        busy.set(false)
        mutableState.value = mutableState.value.copy(active = false, status = reason)
    }

    fun shouldAcceptFrame(nowElapsed: Long): Boolean =
        started && !busy.get() && nowElapsed - lastAcceptedElapsed >= FRAME_INTERVAL_MS

    /** Queues immutable shared pixels; conversion and detection stay off the camera callback. */
    fun offer(frame: SharedVisionFrame) {
        val timestampElapsed = frame.capturedElapsedRealtime
        if (!shouldAcceptFrame(timestampElapsed)) return
        if (!busy.compareAndSet(false, true)) return
        lastAcceptedElapsed = timestampElapsed
        val offered = mutableState.value.offeredFrames + 1
        mutableState.value = mutableState.value.copy(offeredFrames = offered, active = true)
        handler.post {
            val luma = runCatching { copyDownsampledLuma(frame.rgb, SAMPLE_WIDTH, SAMPLE_HEIGHT) }
                .getOrElse { error ->
                    busy.set(false)
                    mutableState.value = mutableState.value.copy(
                        status = "Lanes: ${error.javaClass.simpleName}",
                        errors = (mutableState.value.errors + error.message.orEmpty()).takeLast(20),
                    )
                    return@post
                }
            val startedAt = SystemClock.elapsedRealtime()
            val raw = detectLaneGeometry(luma, SAMPLE_WIDTH, SAMPLE_HEIGHT)
            val stable = tracker.update(raw)
            val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
            val previous = mutableState.value
            val historyLine = stable?.logLine()?.takeIf {
                previous.history.lastOrNull()?.substringAfter(' ') != it
            }
            mutableState.value = previous.copy(
                active = true,
                status = when {
                    stable != null -> "Stable lane ${(stable.confidence * 100).roundToInt()}%"
                    raw != null -> "Lane candidate ${tracker.coherentFrames}/3"
                    else -> "No reliable lane geometry"
                },
                processedFrames = previous.processedFrames + 1,
                rejectedFrames = previous.rejectedFrames +
                    (imageFrameGap(previous.offeredFrames, offered)),
                lastLatencyMs = elapsed,
                maximumLatencyMs = max(previous.maximumLatencyMs, elapsed),
                lastResultElapsedRealtime = timestampElapsed,
                rawLane = raw,
                stableLane = stable,
                coherentFrames = tracker.coherentFrames,
                history = if (historyLine == null) previous.history else {
                    (previous.history + "${System.currentTimeMillis()} $historyLine").takeLast(MAX_HISTORY)
                },
            )
            if (historyLine != null) TelemetryLog.event("LANE", historyLine)
            busy.set(false)
        }
    }

    fun snapshotForReport(): LocalVisionLaneState = mutableState.value

    fun clearObservations(reason: String) {
        handler.post {
            tracker.reset()
            mutableState.value = mutableState.value.copy(
                active = false,
                status = reason,
                lastResultElapsedRealtime = 0L,
                rawLane = null,
                stableLane = null,
                coherentFrames = 0,
            )
        }
    }

    private fun imageFrameGap(previousOffered: Long, offered: Long): Long =
        (offered - previousOffered - 1L).coerceAtLeast(0L)
}

internal class LocalVisionLaneTracker {
    var coherentFrames: Int = 0
        private set
    private var stable: RoadLaneGeometry? = null
    private var candidate: RoadLaneGeometry? = null
    private var candidateFrames = 0
    private var misses = 0

    fun reset() {
        coherentFrames = 0
        stable = null
        candidate = null
        candidateFrames = 0
        misses = 0
    }

    fun update(observed: RoadLaneGeometry?): RoadLaneGeometry? {
        if (observed == null) {
            misses += 1
            if (misses > 5) {
                coherentFrames = 0
                stable = null
                candidate = null
                candidateFrames = 0
            }
            return stable?.copy(confidence = stable!!.confidence * staleLaneConfidence(misses))
        }
        misses = 0
        val previous = stable
        if (previous != null && laneGeometryDistance(previous, observed) <= MAX_GEOMETRY_DISTANCE) {
            coherentFrames += 1
            stable = previous.interpolate(observed, 0.35f)
            candidate = null
            candidateFrames = 0
            return stable
        }

        val previousCandidate = candidate
        if (previousCandidate == null || laneGeometryDistance(previousCandidate, observed) > MAX_GEOMETRY_DISTANCE) {
            candidate = observed
            candidateFrames = 1
        } else {
            candidate = previousCandidate.interpolate(observed, 0.35f)
            candidateFrames += 1
        }

        if (candidateFrames >= REQUIRED_COHERENT_FRAMES) {
            stable = candidate
            coherentFrames = candidateFrames
            candidate = null
            candidateFrames = 0
        } else if (stable == null) {
            coherentFrames = candidateFrames
        }
        return stable
    }

    private companion object {
        const val REQUIRED_COHERENT_FRAMES = 3
        const val MAX_GEOMETRY_DISTANCE = 0.18f
    }
}

/** Searches perspective-constrained line hypotheses instead of fitting every road edge. */
internal fun detectLaneGeometry(luma: ByteArray, width: Int, height: Int): RoadLaneGeometry? {
    if (width < 32 || height < 24 || luma.size < width * height) return null
    val gradient = IntArray(width * height)
    for (y in 1 until height - 1) {
        for (x in 1 until width - 1) {
            val dx = unsigned(luma[y * width + x + 1]) - unsigned(luma[y * width + x - 1])
            val dy = unsigned(luma[(y + 1) * width + x]) - unsigned(luma[(y - 1) * width + x])
            gradient[y * width + x] = abs(dx) + abs(dy)
        }
    }
    val left = bestLaneBoundary(luma, gradient, width, height, left = true) ?: return null
    val right = bestLaneBoundary(luma, gradient, width, height, left = false) ?: return null
    val farWidth = right.farX - left.farX
    val nearWidth = right.nearX - left.nearX
    if (farWidth !in 0.08f..0.46f || nearWidth !in 0.32f..0.96f || nearWidth <= farWidth) return null
    return RoadLaneGeometry(
        left = left,
        right = right,
        confidence = ((left.confidence + right.confidence) / 2f).coerceIn(0f, 1f),
    )
}

private fun bestLaneBoundary(
    luma: ByteArray,
    gradient: IntArray,
    width: Int,
    height: Int,
    left: Boolean,
): RoadLaneBoundary? {
    val horizonY = 0.43f
    val nearY = 0.94f
    val farRange = if (left) 0.30f..0.48f else 0.52f..0.70f
    val nearRange = if (left) 0.03f..0.43f else 0.57f..0.97f
    var bestScore = 0f
    var bestCoverage = 0f
    var bestFar = 0f
    var bestNear = 0f
    var farX = farRange.start
    while (farX <= farRange.endInclusive + 0.001f) {
        var nearX = nearRange.start
        while (nearX <= nearRange.endInclusive + 0.001f) {
            val perspectiveValid = if (left) nearX < farX - 0.07f else nearX > farX + 0.07f
            if (perspectiveValid) {
                var strong = 0
                var total = 0f
                var brightContrastTotal = 0f
                val samples = 34
                repeat(samples) { index ->
                    val t = index.toFloat() / (samples - 1)
                    val y = horizonY + (nearY - horizonY) * t
                    val x = farX + (nearX - farX) * t
                    val px = (x * (width - 1)).roundToInt().coerceIn(3, width - 4)
                    val py = (y * (height - 1)).roundToInt().coerceIn(2, height - 3)
                    var local = 0
                    for (offset in -2..2) local = max(local, gradient[py * width + px + offset])
                    val centerLuma = unsigned(luma[py * width + px])
                    val sideLuma = (
                        unsigned(luma[py * width + px - 3]) +
                            unsigned(luma[py * width + px + 3])
                        ) / 2
                    val brightContrast = ((centerLuma - sideLuma) / 96f).coerceIn(0f, 1f)
                    if (local >= 64 && (centerLuma >= 105 || brightContrast >= 0.22f)) strong += 1
                    total += (local / 220f).coerceIn(0f, 1f)
                    brightContrastTotal += brightContrast
                }
                val coverage = strong.toFloat() / samples
                val score = total / samples * 0.50f + coverage * 0.35f +
                    brightContrastTotal / samples * 0.15f
                if (coverage >= 0.30f && score > bestScore) {
                    bestScore = score
                    bestCoverage = coverage
                    bestFar = farX
                    bestNear = nearX
                }
            }
            nearX += 0.025f
        }
        farX += 0.02f
    }
    if (bestScore < 0.28f) return null
    return RoadLaneBoundary(bestFar, horizonY, bestNear, nearY, (bestScore * bestCoverage).coerceIn(0f, 1f))
}

internal fun roadLaneRelation(box: NormalizedDetectionBox, lane: RoadLaneGeometry?): RoadLaneRelation {
    lane ?: return RoadLaneRelation.UNKNOWN
    // A published lane has already passed the temporal coherence filter. The real TS10 logs
    // commonly settle around 0.12-0.17, so requiring 0.18 left genuine leading vehicles unknown.
    if (lane.confidence < 0.11f || box.bottom < lane.left.farY) return RoadLaneRelation.UNKNOWN
    val y = box.bottom.coerceIn(lane.left.farY, lane.left.nearY)
    val leftX = lane.left.xAt(y)
    val rightX = lane.right.xAt(y)
    val center = (box.left + box.right) / 2f
    return when {
        center < leftX -> RoadLaneRelation.LEFT
        center > rightX -> RoadLaneRelation.RIGHT
        else -> RoadLaneRelation.EGO
    }
}

/** Signed visual bend: negative curves to the left, positive to the right. */
internal fun roadCurveFromLane(lane: RoadLaneGeometry?): Float {
    lane?.takeIf(::laneReliableForRoadRendering) ?: return 0f
    val farCenter = (lane.left.farX + lane.right.farX) / 2f
    val nearCenter = (lane.left.nearX + lane.right.nearX) / 2f
    return (farCenter - nearCenter).coerceIn(-0.18f, 0.18f)
}

/**
 * Converts the Astra steering-wheel angle into a signed visual bend. The decoded CAN angle is
 * left-positive, while the road renderer uses negative values for a left bend. Speed controls
 * how far the predicted path can turn during the visible look-ahead; at rest the road stays
 * straight and the vehicle artwork never moves.
 */
internal fun roadCurveFromSteering(
    steeringAngleDegrees: Float?,
    speedKmh: Int,
): Float {
    val angle = steeringAngleDegrees ?: return 0f
    if (speedKmh <= 0 || kotlin.math.abs(angle) <= STEERING_CURVE_DEADBAND_DEGREES) return 0f
    val effectiveAngle = kotlin.math.abs(angle) - STEERING_CURVE_DEADBAND_DEGREES
    val speedWeight = (speedKmh / STEERING_CURVE_FULL_SPEED_KMH)
        .coerceIn(STEERING_CURVE_MIN_SPEED_WEIGHT, 1f)
    val magnitude = (
        effectiveAngle / STEERING_CURVE_REFERENCE_ANGLE_DEGREES *
            STEERING_CURVE_MAX * speedWeight
        ).coerceAtMost(STEERING_CURVE_MAX)
    return if (angle > 0f) -magnitude else magnitude
}

/** Steering is the immediate prediction; camera/OSM geometry gently anchors the far horizon. */
internal fun resolveRenderedRoadCurve(
    cameraCurve: Float,
    osmCurve: Float,
    steeringCurve: Float,
): Float {
    val mappedCurve = if (kotlin.math.abs(cameraCurve) >= CAMERA_CURVE_MINIMUM) {
        cameraCurve
    } else {
        osmCurve
    }.coerceIn(-STEERING_CURVE_MAX, STEERING_CURVE_MAX)
    if (kotlin.math.abs(steeringCurve) < STEERING_CURVE_MINIMUM) return mappedCurve
    return (
        steeringCurve * STEERING_CURVE_WEIGHT +
            mappedCurve * (1f - STEERING_CURVE_WEIGHT)
        ).coerceIn(-STEERING_CURVE_MAX, STEERING_CURVE_MAX)
}

/**
 * Horizontal displacement of the road centre at a perspective position (0 = horizon, 1 = car).
 * The smoothstep profile keeps the road tangent to the car at the bottom instead of drawing a
 * diagonal trapezoid, while retaining the full predicted displacement at the horizon.
 */
internal fun roadCurveOffsetFraction(
    roadCurve: Float,
    perspective: Float,
): Float {
    val distance = 1f - perspective.coerceIn(0f, 1f)
    val smoothDistance = distance * distance * (3f - 2f * distance)
    return roadCurve * smoothDistance
}

private const val CAMERA_CURVE_MINIMUM = 0.025f
private const val STEERING_CURVE_MINIMUM = 0.004f
private const val STEERING_CURVE_DEADBAND_DEGREES = 2f
private const val STEERING_CURVE_FULL_SPEED_KMH = 45f
private const val STEERING_CURVE_MIN_SPEED_WEIGHT = 0.25f
private const val STEERING_CURVE_REFERENCE_ANGLE_DEGREES = 135f
private const val STEERING_CURVE_MAX = 0.28f
private const val STEERING_CURVE_WEIGHT = 0.85f

/**
 * The classical probe often finds stable high-contrast edges that are not road markings
 * (kerbs, shadows or vehicle edges). Keep those observations for diagnostics/object grouping,
 * but do not let weak geometry move the rendered OSM road or the remembered lane position.
 */
internal fun laneReliableForRoadRendering(lane: RoadLaneGeometry): Boolean =
    lane.confidence >= MIN_RENDER_LANE_CONFIDENCE

internal data class RoadLaneGeometry(
    val left: RoadLaneBoundary,
    val right: RoadLaneBoundary,
    val confidence: Float,
) {
    fun logLine(): String = String.format(
        Locale.US,
        "confidence=%.2f left=[%.3f,%.3f->%.3f,%.3f] right=[%.3f,%.3f->%.3f,%.3f]",
        confidence, left.farX, left.farY, left.nearX, left.nearY,
        right.farX, right.farY, right.nearX, right.nearY,
    )

    fun interpolate(other: RoadLaneGeometry, alpha: Float) = RoadLaneGeometry(
        left.interpolate(other.left, alpha),
        right.interpolate(other.right, alpha),
        lerpLane(confidence, other.confidence, alpha),
    )
}

internal data class RoadLaneBoundary(
    val farX: Float,
    val farY: Float,
    val nearX: Float,
    val nearY: Float,
    val confidence: Float,
) {
    fun xAt(y: Float): Float {
        val t = ((y - farY) / (nearY - farY).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
        return farX + (nearX - farX) * t
    }

    fun interpolate(other: RoadLaneBoundary, alpha: Float) = RoadLaneBoundary(
        lerpLane(farX, other.farX, alpha), lerpLane(farY, other.farY, alpha),
        lerpLane(nearX, other.nearX, alpha), lerpLane(nearY, other.nearY, alpha),
        lerpLane(confidence, other.confidence, alpha),
    )
}

internal data class LocalVisionLaneState(
    val active: Boolean = false,
    val status: String = "Lanes not started",
    val offeredFrames: Long = 0,
    val processedFrames: Long = 0,
    val rejectedFrames: Long = 0,
    val lastLatencyMs: Long = 0,
    val maximumLatencyMs: Long = 0,
    val lastResultElapsedRealtime: Long = 0,
    val coherentFrames: Int = 0,
    val rawLane: RoadLaneGeometry? = null,
    val stableLane: RoadLaneGeometry? = null,
    val history: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
)

private fun laneGeometryDistance(a: RoadLaneGeometry, b: RoadLaneGeometry): Float =
    abs(a.left.farX - b.left.farX) + abs(a.left.nearX - b.left.nearX) +
        abs(a.right.farX - b.right.farX) + abs(a.right.nearX - b.right.nearX)

private fun staleLaneConfidence(misses: Int): Float {
    var factor = 1f
    repeat(misses.coerceAtLeast(0)) { factor *= 0.85f }
    return factor
}

private fun lerpLane(a: Float, b: Float, alpha: Float) = a + (b - a) * alpha
private fun unsigned(value: Byte) = value.toInt() and 0xff

private const val MIN_RENDER_LANE_CONFIDENCE = 0.28f

private fun copyDownsampledLuma(frame: VisionRgbFrame, targetWidth: Int, targetHeight: Int): ByteArray {
    val result = ByteArray(targetWidth * targetHeight)
    for (y in 0 until targetHeight) {
        val sourceY = y * frame.height / targetHeight
        for (x in 0 until targetWidth) {
            val sourceX = x * frame.width / targetWidth
            val rgb = frame.rgb(sourceX, sourceY)
            val r = rgb shr 16 and 0xff
            val g = rgb shr 8 and 0xff
            val b = rgb and 0xff
            result[y * targetWidth + x] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
        }
    }
    return result
}
