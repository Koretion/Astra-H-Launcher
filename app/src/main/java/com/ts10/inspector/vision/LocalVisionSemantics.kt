package com.ts10.inspector.vision

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Small RGB copy detached from Camera/ImageReader lifetime, used by semantic classifiers. */
internal data class VisionRgbFrame(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    init {
        require(width > 0 && height > 0 && pixels.size >= width * height)
    }

    fun rgb(x: Int, y: Int): Int = pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
}

internal enum class TrafficLightColor(val logLabel: String) {
    RED("red"), AMBER("amber"), GREEN("green"), UNKNOWN("unknown")
}

internal data class TrafficLightClassification(
    val color: TrafficLightColor,
    val confidence: Float,
    val redEvidence: Float,
    val amberEvidence: Float,
    val greenEvidence: Float,
) {
    fun logLine(): String = String.format(
        Locale.US,
        "light=%s %.0f%% rgbEvidence=[%.2f,%.2f,%.2f]",
        color.logLabel,
        confidence * 100f,
        redEvidence,
        amberEvidence,
        greenEvidence,
    )
}

/**
 * Classifies the illuminated lamp inside an already detected traffic-light box. It deliberately
 * requires saturated, bright evidence and a clear margin; UNKNOWN is safer than inventing a state.
 */
internal fun classifyTrafficLight(
    frame: VisionRgbFrame,
    box: NormalizedDetectionBox,
): TrafficLightClassification {
    val left = (box.left * frame.width).toInt().coerceIn(0, frame.width - 1)
    val top = (box.top * frame.height).toInt().coerceIn(0, frame.height - 1)
    val right = (box.right * frame.width).toInt().coerceIn(left + 1, frame.width)
    val bottom = (box.bottom * frame.height).toInt().coerceIn(top + 1, frame.height)
    val insetX = ((right - left) * 0.12f).toInt()
    val insetY = ((bottom - top) * 0.05f).toInt()
    val x0 = (left + insetX).coerceAtMost(right - 1)
    val x1 = (right - insetX).coerceAtLeast(x0 + 1)
    val y0 = (top + insetY).coerceAtMost(bottom - 1)
    val y1 = (bottom - insetY).coerceAtLeast(y0 + 1)
    val height = (y1 - y0).coerceAtLeast(1)

    var red = 0f
    var amber = 0f
    var green = 0f
    var samples = 0
    for (y in y0 until y1) {
        val vertical = (y - y0).toFloat() / height
        for (x in x0 until x1) {
            val rgb = frame.rgb(x, y)
            val r = (rgb shr 16 and 0xff) / 255f
            val g = (rgb shr 8 and 0xff) / 255f
            val b = (rgb and 0xff) / 255f
            val brightest = max(r, max(g, b))
            val darkest = min(r, min(g, b))
            val saturation = brightest - darkest
            if (brightest < 0.28f || saturation < 0.12f) continue
            val redDominance = (r - max(g, b)).coerceAtLeast(0f)
            val greenDominance = (g - max(r, b)).coerceAtLeast(0f)
            val amberDominance = (min(r, g) - b - abs(r - g) * 0.45f).coerceAtLeast(0f)
            // Positional weighting supports the common vertical red/amber/green housing while
            // retaining enough weight for horizontal or angled lights.
            red += redDominance * brightest * if (vertical < 0.48f) 1.35f else 0.70f
            amber += amberDominance * brightest * if (vertical in 0.18f..0.82f) 1.20f else 0.75f
            green += greenDominance * brightest * if (vertical > 0.48f) 1.35f else 0.70f
            samples += 1
        }
    }
    val area = ((x1 - x0) * (y1 - y0)).coerceAtLeast(1)
    val densityBoost = sqrt((samples.toFloat() / area * 8f).coerceIn(0f, 1f))
    val evidence = floatArrayOf(red, amber, green).map { it / area * 8f * densityBoost }
    val ranked = evidence.withIndex().sortedByDescending { it.value }
    val best = ranked.first()
    val second = ranked.getOrNull(1)?.value ?: 0f
    val margin = (best.value - second).coerceAtLeast(0f)
    val confidence = (best.value * 1.8f + margin * 1.4f).coerceIn(0f, 1f)
    val color = if (best.value >= MIN_LIGHT_EVIDENCE && margin >= MIN_LIGHT_MARGIN) {
        when (best.index) {
            0 -> TrafficLightColor.RED
            1 -> TrafficLightColor.AMBER
            else -> TrafficLightColor.GREEN
        }
    } else TrafficLightColor.UNKNOWN
    return TrafficLightClassification(color, confidence, evidence[0], evidence[1], evidence[2])
}

internal data class SpeedSignCandidate(
    val box: NormalizedDetectionBox,
    val confidence: Float,
    val redCoverage: Float,
) {
    fun logLine(): String = String.format(
        Locale.US,
        "speed-sign-candidate %.0f%% red=%.2f box=[%.3f,%.3f,%.3f,%.3f]",
        confidence * 100f,
        redCoverage,
        box.left,
        box.top,
        box.right,
        box.bottom,
    )
}

/** Finds red, near-square rings in the road-sign region; it does not guess the number. */
internal fun detectSpeedSignCandidates(frame: VisionRgbFrame): List<SpeedSignCandidate> {
    val width = frame.width
    val height = frame.height
    val limitY = (height * 0.82f).toInt()
    val mask = BooleanArray(width * limitY)
    for (y in 0 until limitY) for (x in 0 until width) {
        val rgb = frame.rgb(x, y)
        val r = rgb shr 16 and 0xff
        val g = rgb shr 8 and 0xff
        val b = rgb and 0xff
        mask[y * width + x] = r >= 92 && r >= g * 1.22f && r >= b * 1.16f && r - min(g, b) >= 32
    }
    val visited = BooleanArray(mask.size)
    val queue = IntArray(mask.size)
    val found = mutableListOf<SpeedSignCandidate>()
    for (start in mask.indices) {
        if (!mask[start] || visited[start]) continue
        var head = 0
        var tail = 0
        queue[tail++] = start
        visited[start] = true
        var minX = width
        var maxX = 0
        var minY = limitY
        var maxY = 0
        var count = 0
        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            minX = min(minX, x); maxX = max(maxX, x)
            minY = min(minY, y); maxY = max(maxY, y)
            count += 1
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until limitY) continue
                val next = ny * width + nx
                if (mask[next] && !visited[next]) {
                    visited[next] = true
                    queue[tail++] = next
                }
            }
        }
        val componentWidth = maxX - minX + 1
        val componentHeight = maxY - minY + 1
        if (componentWidth !in 4..(width / 4) || componentHeight !in 4..(height / 3)) continue
        val aspect = componentWidth.toFloat() / componentHeight
        if (aspect !in 0.62f..1.38f) continue
        val boxArea = componentWidth * componentHeight
        val coverage = count.toFloat() / boxArea
        if (coverage !in 0.08f..0.62f) continue
        val centerMarginX = max(1, componentWidth / 4)
        val centerMarginY = max(1, componentHeight / 4)
        var paleCenter = 0
        var centerSamples = 0
        for (y in minY + centerMarginY..maxY - centerMarginY) {
            for (x in minX + centerMarginX..maxX - centerMarginX) {
                val rgb = frame.rgb(x, y)
                val r = rgb shr 16 and 0xff
                val g = rgb shr 8 and 0xff
                val b = rgb and 0xff
                if (max(r, max(g, b)) - min(r, min(g, b)) < 70 && max(r, max(g, b)) > 45) paleCenter += 1
                centerSamples += 1
            }
        }
        val centerRatio = paleCenter.toFloat() / centerSamples.coerceAtLeast(1)
        if (centerRatio < 0.25f) continue
        val confidence = (
            0.35f +
                (1f - abs(1f - aspect)).coerceIn(0f, 1f) * 0.25f +
                (coverage / 0.28f).coerceIn(0f, 1f) * 0.20f +
                centerRatio.coerceIn(0f, 1f) * 0.20f
            ).coerceIn(0f, 1f)
        found += SpeedSignCandidate(
            NormalizedDetectionBox(
                minX.toFloat() / width,
                minY.toFloat() / height,
                (maxX + 1f) / width,
                (maxY + 1f) / height,
            ),
            confidence,
            coverage,
        )
    }
    return found.sortedByDescending { it.confidence }.take(MAX_SPEED_SIGN_CANDIDATES)
}

internal data class LocalRoadProjection(
    val kind: RoadObjectKind,
    val lateral: Float,
    val proximity: Float,
    val visualScale: Float,
    val confidence: Float,
    val trafficLightColor: TrafficLightColor,
)

/** Relative road-plane projection. It intentionally publishes no uncalibrated metric distance. */
internal fun projectTrackedRoadObject(track: TrackedRoadObject): LocalRoadProjection {
    val groundY = track.box.bottom.coerceIn(ROAD_HORIZON_Y, ROAD_NEAR_Y)
    val proximity = ((groundY - ROAD_HORIZON_Y) / (ROAD_NEAR_Y - ROAD_HORIZON_Y)).coerceIn(0f, 1f)
    val roadHalfWidth = (0.06f + proximity * 0.43f).coerceAtLeast(0.06f)
    val centerX = (track.box.left + track.box.right) / 2f
    val lateral = ((centerX - 0.5f) / roadHalfWidth).coerceIn(-2.2f, 2.2f)
    val boxHeight = (track.box.bottom - track.box.top).coerceAtLeast(0f)
    return LocalRoadProjection(
        track.kind,
        lateral,
        proximity,
        (0.25f + proximity * 0.75f + boxHeight * 0.35f).coerceIn(0.2f, 1.25f),
        track.confidence,
        track.trafficLightColor,
    )
}

private const val MIN_LIGHT_EVIDENCE = 0.055f
private const val MIN_LIGHT_MARGIN = 0.018f
private const val MAX_SPEED_SIGN_CANDIDATES = 6
private const val ROAD_HORIZON_Y = 0.40f
private const val ROAD_NEAR_Y = 0.96f
