package com.ts10.inspector.vision

import java.util.Locale
import kotlin.math.abs

/** One 10 Hz observation of everything that can reveal a lane change. */
internal data class LaneChangeSample(
    val nowMs: Long,
    val speedKmh: Float,
    /** Steering-wheel angle, left-positive as decoded from the Astra CAN; null without CAN. */
    val steeringDeg: Float?,
    val leftIndicator: Boolean,
    val rightIndicator: Boolean,
    /** Stable camera lane, only while the forward camera is the vision source. */
    val lane: RoadLaneGeometry?,
    /** OSM lanes in the travel direction; null when the road is unknown. */
    val lanesInTravelDirection: Int?,
)

/**
 * Keeps the OSM lane index (counted from the right) across lane changes.
 *
 * Replay of real TS10 captures (2026-11-15/23) showed that:
 * - the camera lane detector alone is too weak (confidence 0.15-0.38, gaps during the manoeuvre);
 * - the steering angle cannot measure metres (1.5 deg resolution and a +3..+4.5 deg straight-ahead
 *   offset: one step of bias error flips the integrated lateral displacement), but its *shape* is
 *   reliable: a lane change first steers towards the indicated side and then counter-steers,
 *   measured against the steering held just before the indicator;
 * - junction turns are excluded by the steering already being turned or exceeding 30 deg.
 *
 * Decision: an indicator opens the window; the steering S-shape or the camera (line crossing, or
 * excursion and recentring towards that side) confirms it; OSM bounds the index. Every window ends
 * in one `LANE_CHANGE` log line with all the evidence, so real drives can tune the thresholds.
 */
internal class LaneChangeDetector(private val log: (String) -> Unit = {}) {
    var laneFromRight = 0
        private set
    private var laneCount = 1
    private var pendingCount: Int? = null
    private var pendingSinceMs = 0L

    private class SteeringPoint(val atMs: Long, val deg: Float)
    private val steeringHistory = ArrayDeque<SteeringPoint>()

    private class Episode(val side: Int, val startMs: Long, val baselineDeg: Float?, val startKmh: Float) {
        var lastIndicatorMs = startMs
        var towardMs: Long? = null
        var counterMs: Long? = null
        var maxSteerDeltaDeg = 0f
        var minKmh = startKmh
        var steeringSeen = false
        var cameraExcursionFrames = 0
        var cameraExcursion = false
        var cameraRecentred = false
        var cameraCrossing = false
        var cameraFrames = 0
    }
    private var episode: Episode? = null
    private var lastCameraOffset: Float? = null
    private var lastCameraMs = 0L

    fun reset() {
        laneFromRight = 0
        laneCount = 1
        pendingCount = null
        steeringHistory.clear()
        episode = null
        lastCameraOffset = null
    }

    fun update(sample: LaneChangeSample): Int {
        updateLaneCount(sample)
        sample.steeringDeg?.let { steeringHistory.addLast(SteeringPoint(sample.nowMs, it)) }
        while (steeringHistory.isNotEmpty() && sample.nowMs - steeringHistory.first().atMs > HISTORY_MS) {
            steeringHistory.removeFirst()
        }
        val cameraOffset = cameraOffset(sample)

        val side = when {
            sample.leftIndicator && sample.rightIndicator -> 0 // Hazard lights never imply a change.
            sample.leftIndicator -> LEFT
            sample.rightIndicator -> RIGHT
            else -> null
        }
        var current = episode
        if (side == 0 && current != null) {
            close(current, sample, "hazard")
            current = null
        }
        if (side != null && side != 0) {
            if (current != null && current.side != side) {
                close(current, sample, "side-changed")
                current = null
            }
            if (current == null) current = open(side, sample)
            current.lastIndicatorMs = sample.nowMs
        }
        current ?: run {
            lastCameraOffset = cameraOffset
            return laneFromRight
        }

        observeSteering(current, sample)
        observeCamera(current, cameraOffset, sample.nowMs)
        lastCameraOffset = cameraOffset
        if (cameraOffset != null) lastCameraMs = sample.nowMs

        val steeringS = current.towardMs != null && current.counterMs != null
        val camera = current.cameraCrossing || (current.cameraExcursion && current.cameraRecentred)
        if (steeringS || camera) {
            close(current, sample, null)
            // A held indicator may cross several lanes: watch for the next one from here, against
            // the same straight-ahead steering and without replaying the manoeuvre just counted.
            if (side != null && side != 0) episode = open(side, sample, carriedBaseline = current.baselineDeg)
        } else if (sample.nowMs - current.lastIndicatorMs > CLOSE_AFTER_INDICATOR_MS) {
            close(current, sample, "no-confirmation")
        }
        return laneFromRight
    }

    /**
     * Más carriles se aplican al momento; menos (o vía desconocida) solo si se mantiene
     * [LANE_DROP_HOLD_MS]. En la TS10 OSM saltaba unos segundos a un ramal de un carril y eso
     * devolvía el coche al carril derecho, y el siguiente cambio real a la derecha se rechazaba.
     */
    private fun updateLaneCount(sample: LaneChangeSample) {
        val observed = sample.lanesInTravelDirection?.coerceIn(1, MAX_LANES)
        when {
            observed == null || observed == laneCount -> pendingCount = null
            observed > laneCount -> {
                laneCount = observed
                pendingCount = null
            }
            pendingCount != observed -> {
                pendingCount = observed
                pendingSinceMs = sample.nowMs
            }
            sample.nowMs - pendingSinceMs >= LANE_DROP_HOLD_MS -> {
                laneCount = observed
                laneFromRight = laneFromRight.coerceIn(0, observed - 1)
                pendingCount = null
            }
        }
    }

    private fun open(side: Int, sample: LaneChangeSample, carriedBaseline: Float? = null): Episode {
        val replay = carriedBaseline == null
        val baseline = carriedBaseline ?: steeringHistory
            .filter { sample.nowMs - it.atMs in BASELINE_FROM_MS..BASELINE_TO_MS }
            .map { it.deg }
            .sorted()
            .let { if (it.isEmpty()) null else it[it.size / 2] }
        return Episode(side, sample.nowMs, baseline, sample.speedKmh).also {
            episode = it
            // The driver often starts steering just before the first flash reaches the CAN.
            if (replay) steeringHistory.filter { point -> sample.nowMs - point.atMs <= PRE_START_MS }
                .forEach { point -> observeSteeringValue(it, point.deg, point.atMs, sample.speedKmh) }
        }
    }

    private fun observeSteering(episode: Episode, sample: LaneChangeSample) {
        episode.minKmh = minOf(episode.minKmh, sample.speedKmh)
        val deg = sample.steeringDeg ?: return
        observeSteeringValue(episode, deg, sample.nowMs, sample.speedKmh)
    }

    private fun observeSteeringValue(episode: Episode, deg: Float, atMs: Long, kmh: Float) {
        val baseline = episode.baselineDeg ?: return
        episode.steeringSeen = true
        val delta = deg - baseline
        episode.maxSteerDeltaDeg = maxOf(episode.maxSteerDeltaDeg, abs(delta))
        val towards = episode.side * delta
        val towardThreshold = if (kmh >= HIGH_SPEED_KMH) TOWARD_HIGH_SPEED_DEG else TOWARD_DEG
        if (!steeringGatesOk(episode)) return
        val toward = episode.towardMs
        if (toward == null) {
            if (towards >= towardThreshold) episode.towardMs = atMs
        } else if (episode.counterMs == null && atMs > toward && towards <= -COUNTER_DEG) {
            if (atMs - toward <= MAX_COUNTER_DELAY_MS) episode.counterMs = atMs else episode.towardMs = null
        }
    }

    private fun steeringGatesOk(episode: Episode): Boolean {
        val baseline = episode.baselineDeg ?: return false
        return abs(baseline) <= MAX_BASELINE_DEG &&
            episode.maxSteerDeltaDeg <= MAX_STEER_DELTA_DEG &&
            episode.minKmh >= MIN_SPEED_KMH
    }

    /** Lane corridor centre minus image centre: positive when the car drifts to the left. */
    private fun cameraOffset(sample: LaneChangeSample): Float? {
        val geometry = sample.lane?.takeIf { it.confidence >= MIN_CAMERA_CONFIDENCE } ?: return null
        val center = (geometry.left.xAt(CAMERA_SAMPLE_Y) + geometry.right.xAt(CAMERA_SAMPLE_Y)) / 2f
        return center - 0.5f
    }

    private fun observeCamera(episode: Episode, offset: Float?, nowMs: Long) {
        offset ?: return
        episode.cameraFrames++
        val previous = lastCameraOffset?.takeIf { nowMs - lastCameraMs <= CAMERA_GAP_MS }
        // The detector jumps to the new lane when the car crosses the line: the corridor that was
        // shifted towards one side reappears shifted towards the other.
        if (previous != null && episode.side * previous >= DEPARTURE_OFFSET &&
            episode.side * offset <= -DEPARTURE_OFFSET / 2f && abs(offset - previous) >= CROSSING_JUMP
        ) {
            episode.cameraCrossing = true
        }
        if (episode.side * offset >= DEPARTURE_OFFSET) {
            episode.cameraExcursionFrames++
            if (episode.cameraExcursionFrames >= REQUIRED_EXCURSION_FRAMES) episode.cameraExcursion = true
        } else if (abs(offset) < DEPARTURE_OFFSET / 2f && episode.cameraExcursion) {
            episode.cameraRecentred = true
        } else if (episode.side * offset < 0f) {
            episode.cameraExcursionFrames = 0
        }
    }

    /** [rejection] null commits the change. */
    private fun close(episode: Episode, sample: LaneChangeSample, rejection: String?) {
        if (this.episode === episode) this.episode = null
        val before = laneFromRight
        val reason = rejection ?: when {
            laneCount <= 1 -> "osm-single-lane"
            laneFromRight + episode.side !in 0 until laneCount -> "osm-edge"
            else -> null
        }
        if (reason == null) laneFromRight += episode.side
        log(
            String.format(
                Locale.US,
                "decision=%s side=%s durationMs=%d speedKmh=%.0f minKmh=%.0f steering=%s baseline=%s " +
                    "maxSteerDelta=%.1f toward=%s counter=%s camera=%s cameraFrames=%d lanes=%d fromRight=%d->%d",
                if (reason == null) "commit" else "reject:$reason",
                if (episode.side == LEFT) "left" else "right",
                sample.nowMs - episode.startMs,
                episode.startKmh,
                episode.minKmh,
                when {
                    !episode.steeringSeen -> "none"
                    episode.towardMs != null && episode.counterMs != null -> "s-shape"
                    !steeringGatesOk(episode) -> "turn-or-slow"
                    episode.towardMs != null -> "toward-only"
                    else -> "flat"
                },
                episode.baselineDeg?.let { String.format(Locale.US, "%.1f", it) } ?: "--",
                episode.maxSteerDeltaDeg,
                episode.towardMs?.let { "+${it - episode.startMs}ms" } ?: "--",
                episode.counterMs?.let { "+${it - episode.startMs}ms" } ?: "--",
                when {
                    episode.cameraCrossing -> "crossing"
                    episode.cameraExcursion && episode.cameraRecentred -> "excursion-recentred"
                    episode.cameraExcursion -> "excursion"
                    else -> "none"
                },
                episode.cameraFrames,
                laneCount,
                before,
                laneFromRight,
            ),
        )
    }

    private companion object {
        const val LEFT = 1
        const val RIGHT = -1
        const val MAX_LANES = 6
        const val LANE_DROP_HOLD_MS = 10_000L
        const val HISTORY_MS = 4_000L
        const val BASELINE_FROM_MS = 300L
        const val BASELINE_TO_MS = 2_500L
        const val PRE_START_MS = 1_000L
        const val CLOSE_AFTER_INDICATOR_MS = 4_000L
        const val MIN_SPEED_KMH = 20f
        const val HIGH_SPEED_KMH = 60f
        /** Lane changes in the captures steered 4.5-6 deg at 40-50 km/h; above 60 km/h far less. */
        const val TOWARD_DEG = 3f
        const val TOWARD_HIGH_SPEED_DEG = 1.5f
        const val COUNTER_DEG = 1.5f
        const val MAX_COUNTER_DELAY_MS = 6_000L
        /** Junction turns start with the wheel already turned or exceed this deflection. */
        const val MAX_BASELINE_DEG = 20f
        const val MAX_STEER_DELTA_DEG = 30f
        const val MIN_CAMERA_CONFIDENCE = 0.11f
        const val CAMERA_SAMPLE_Y = 0.78f
        const val DEPARTURE_OFFSET = 0.09f
        const val CROSSING_JUMP = 0.15f
        const val CAMERA_GAP_MS = 800L
        const val REQUIRED_EXCURSION_FRAMES = 3
    }
}
