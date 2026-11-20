package com.ts10.inspector.vision

import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max

/**
 * Lightweight detection-to-detection tracker.
 *
 * It deliberately owns no Android objects and runs on the detector thread. The tracker provides
 * stable identities and motion hints. Lane relation is published only while the local lane probe
 * has a temporally confirmed corridor; metric distance remains unknown until calibration.
 */
internal class LocalVisionObjectTracker {
    private val tracks = mutableListOf<MutableRoadTrack>()
    private var nextTrackId = 1L
    private var confirmedTrackIds = mutableSetOf<Long>()

    var totalCreated: Long = 0L
        private set
    var totalConfirmed: Long = 0L
        private set
    var totalExpired: Long = 0L
        private set

    fun reset() {
        tracks.clear()
        nextTrackId = 1L
        confirmedTrackIds.clear()
        totalCreated = 0L
        totalConfirmed = 0L
        totalExpired = 0L
    }

    fun update(
        timestampMs: Long,
        detections: List<LocalVisionDetection>,
        lane: RoadLaneGeometry? = null,
    ): RoadTrackingUpdate {
        val assignments = associateRoadDetections(tracks, detections)
        val matchedTrackIndexes = assignments.mapTo(mutableSetOf()) { it.trackIndex }
        val matchedDetectionIndexes = assignments.mapTo(mutableSetOf()) { it.detectionIndex }

        assignments.forEach { assignment ->
            tracks[assignment.trackIndex].observe(timestampMs, detections[assignment.detectionIndex])
        }
        tracks.indices.filterNot(matchedTrackIndexes::contains).forEach { tracks[it].misses += 1 }

        detections.indices.filterNot(matchedDetectionIndexes::contains).forEach { detectionIndex ->
            val detection = detections[detectionIndex]
            tracks += MutableRoadTrack(
                id = nextTrackId++,
                kind = detection.kind,
                box = detection.box,
                lastRawBox = detection.box,
                confidence = detection.score,
                firstSeenMs = timestampMs,
                lastSeenMs = timestampMs,
                trafficLightColor = detection.trafficLightColor,
                trafficLightColorConfidence = detection.trafficLightColorConfidence,
                trafficLightColorHits = if (detection.trafficLightColor == TrafficLightColor.UNKNOWN) 0 else 1,
            )
            totalCreated += 1
        }

        val expired = tracks.filter { track ->
            val maxMisses = if (track.kind == RoadObjectKind.TRAFFIC_LIGHT) {
                MAX_TRAFFIC_LIGHT_MISSED_INFERENCES
            } else {
                MAX_MISSED_INFERENCES
            }
            val maxAgeMs = if (track.kind == RoadObjectKind.TRAFFIC_LIGHT) {
                MAX_TRAFFIC_LIGHT_TRACK_AGE_MS
            } else {
                MAX_TRACK_AGE_MS
            }
            track.misses > maxMisses || timestampMs - track.lastSeenMs > maxAgeMs
        }
        totalExpired += expired.size
        val expiredIds = expired.map { it.id }
        tracks.removeAll(expired.toSet())
        confirmedTrackIds.removeAll(expiredIds.toSet())

        val newlyConfirmed = tracks
            .filter { it.hits >= CONFIRMATION_HITS && confirmedTrackIds.add(it.id) }
            .map { it.id }
        totalConfirmed += newlyConfirmed.size

        val visible = tracks
            .filter { it.id in confirmedTrackIds }
            .map { it.snapshot(timestampMs, lane) }
            .sortedWith(compareBy<TrackedRoadObject> { it.box.bottom }.reversed().thenBy { it.id })

        return RoadTrackingUpdate(
            objects = visible,
            tentativeCount = tracks.count { it.id !in confirmedTrackIds },
            newlyConfirmedIds = newlyConfirmed,
            expiredIds = expiredIds,
            totalCreated = totalCreated,
            totalConfirmed = totalConfirmed,
            totalExpired = totalExpired,
        )
    }
}

private data class MutableRoadTrack(
    val id: Long,
    val kind: RoadObjectKind,
    var box: NormalizedDetectionBox,
    var lastRawBox: NormalizedDetectionBox,
    var confidence: Float,
    val firstSeenMs: Long,
    var lastSeenMs: Long,
    var hits: Int = 1,
    var misses: Int = 0,
    var horizontalVelocity: Float = 0f,
    var areaVelocity: Float = 0f,
    var trafficLightColor: TrafficLightColor = TrafficLightColor.UNKNOWN,
    var trafficLightColorConfidence: Float = 0f,
    var trafficLightColorHits: Int = 0,
) {
    fun observe(timestampMs: Long, detection: LocalVisionDetection) {
        val elapsedSeconds = ((timestampMs - lastSeenMs).coerceAtLeast(1L) / 1_000f)
        val rawHorizontalVelocity =
            (detection.box.centerX() - lastRawBox.centerX()) / elapsedSeconds
        val previousArea = lastRawBox.area().coerceAtLeast(0.0001f)
        val rawAreaVelocity =
            ((detection.box.area() - previousArea) / previousArea) / elapsedSeconds
        val velocityAlpha = if (hits <= 1) 1f else VELOCITY_SMOOTHING_ALPHA
        horizontalVelocity = lerp(horizontalVelocity, rawHorizontalVelocity, velocityAlpha)
        areaVelocity = lerp(areaVelocity, rawAreaVelocity, velocityAlpha)
        if (kind == RoadObjectKind.TRAFFIC_LIGHT) {
            when {
                detection.trafficLightColor == TrafficLightColor.UNKNOWN -> {
                    trafficLightColorConfidence *= 0.85f
                }
                detection.trafficLightColor == trafficLightColor -> {
                    trafficLightColorHits += 1
                    trafficLightColorConfidence = lerp(
                        trafficLightColorConfidence,
                        detection.trafficLightColorConfidence,
                        0.45f,
                    )
                }
                trafficLightColorHits <= 1 ||
                    detection.trafficLightColorConfidence > trafficLightColorConfidence + 0.20f -> {
                    trafficLightColor = detection.trafficLightColor
                    trafficLightColorConfidence = detection.trafficLightColorConfidence
                    trafficLightColorHits = 1
                }
                else -> trafficLightColorHits -= 1
            }
        }
        box = box.interpolate(detection.box, BOX_SMOOTHING_ALPHA)
        lastRawBox = detection.box
        confidence = lerp(confidence, detection.score, CONFIDENCE_SMOOTHING_ALPHA)
        lastSeenMs = timestampMs
        hits += 1
        misses = 0
    }

    fun snapshot(nowMs: Long, lane: RoadLaneGeometry?): TrackedRoadObject = TrackedRoadObject(
        id = id,
        kind = kind,
        confidence = confidence,
        box = box,
        hits = hits,
        missedInferences = misses,
        ageMs = (nowMs - firstSeenMs).coerceAtLeast(0L),
        lastSeenAgeMs = (nowMs - lastSeenMs).coerceAtLeast(0L),
        horizontalMotion = when {
            horizontalVelocity <= -HORIZONTAL_MOTION_THRESHOLD -> HorizontalMotion.LEFT
            horizontalVelocity >= HORIZONTAL_MOTION_THRESHOLD -> HorizontalMotion.RIGHT
            hits >= CONFIRMATION_HITS -> HorizontalMotion.STABLE
            else -> HorizontalMotion.UNKNOWN
        },
        relativeMotion = when {
            areaVelocity >= AREA_MOTION_THRESHOLD -> RelativeRoadMotion.APPROACHING
            areaVelocity <= -AREA_MOTION_THRESHOLD -> RelativeRoadMotion.RECEDING
            hits >= CONFIRMATION_HITS -> RelativeRoadMotion.STABLE
            else -> RelativeRoadMotion.UNKNOWN
        },
        horizontalVelocity = horizontalVelocity,
        areaVelocity = areaVelocity,
        laneRelation = roadLaneRelation(box, lane),
        trafficLightColor = trafficLightColor.takeIf {
            trafficLightColorHits >= TRAFFIC_LIGHT_CONFIRMATION_HITS &&
                trafficLightColorConfidence >= TRAFFIC_LIGHT_MIN_CONFIDENCE
        } ?: TrafficLightColor.UNKNOWN,
        trafficLightColorConfidence = trafficLightColorConfidence,
    )
}

private data class RoadTrackAssignment(
    val trackIndex: Int,
    val detectionIndex: Int,
    val quality: Float,
)

private fun associateRoadDetections(
    tracks: List<MutableRoadTrack>,
    detections: List<LocalVisionDetection>,
): List<RoadTrackAssignment> {
    val candidates = buildList {
        tracks.forEachIndexed { trackIndex, track ->
            detections.forEachIndexed detectionLoop@{ detectionIndex, detection ->
                if (track.kind != detection.kind) return@detectionLoop
                val iou = intersectionOverUnion(track.box, detection.box)
                val centerDistance = hypot(
                    track.box.centerX() - detection.box.centerX(),
                    track.box.centerY() - detection.box.centerY(),
                )
                val sizeRatio = minOf(track.box.area(), detection.box.area()) /
                    max(track.box.area(), detection.box.area()).coerceAtLeast(0.0001f)
                if (
                    iou < MIN_ASSOCIATION_IOU &&
                    (centerDistance > MAX_ASSOCIATION_CENTER_DISTANCE || sizeRatio < MIN_SIZE_RATIO)
                ) {
                    return@detectionLoop
                }
                val proximity = (1f - centerDistance / MAX_ASSOCIATION_CENTER_DISTANCE).coerceIn(0f, 1f)
                add(RoadTrackAssignment(trackIndex, detectionIndex, iou * 0.65f + proximity * 0.25f + sizeRatio * 0.10f))
            }
        }
    }.sortedByDescending { it.quality }

    val usedTracks = mutableSetOf<Int>()
    val usedDetections = mutableSetOf<Int>()
    return buildList {
        candidates.forEach { candidate ->
            if (candidate.trackIndex in usedTracks || candidate.detectionIndex in usedDetections) {
                return@forEach
            }
            usedTracks += candidate.trackIndex
            usedDetections += candidate.detectionIndex
            add(candidate)
        }
    }
}

internal data class RoadTrackingUpdate(
    val objects: List<TrackedRoadObject>,
    val tentativeCount: Int,
    val newlyConfirmedIds: List<Long>,
    val expiredIds: List<Long>,
    val totalCreated: Long,
    val totalConfirmed: Long,
    val totalExpired: Long,
)

internal data class TrackedRoadObject(
    val id: Long,
    val kind: RoadObjectKind,
    val confidence: Float,
    val box: NormalizedDetectionBox,
    val hits: Int,
    val missedInferences: Int,
    val ageMs: Long,
    val lastSeenAgeMs: Long,
    val horizontalMotion: HorizontalMotion,
    val relativeMotion: RelativeRoadMotion,
    val horizontalVelocity: Float,
    val areaVelocity: Float,
    val laneRelation: RoadLaneRelation,
    val trafficLightColor: TrafficLightColor = TrafficLightColor.UNKNOWN,
    val trafficLightColorConfidence: Float = 0f,
) {
    fun logLine(): String = String.format(
        Locale.US,
        "#%d %s %.1f%% box=[%.3f,%.3f,%.3f,%.3f] horizontal=%s relative=%s " +
            "hits=%d misses=%d vx=%.3f areaRate=%.3f lane=%s light=%s(%.0f%%) projection=[lat=%.2f,near=%.2f]",
        id,
        kind.label,
        confidence * 100f,
        box.left,
        box.top,
        box.right,
        box.bottom,
        horizontalMotion.logLabel,
        relativeMotion.logLabel,
        hits,
        missedInferences,
        horizontalVelocity,
        areaVelocity,
        laneRelation.name.lowercase(Locale.US),
        trafficLightColor.logLabel,
        trafficLightColorConfidence * 100f,
        projectTrackedRoadObject(this).lateral,
        projectTrackedRoadObject(this).proximity,
    )
}

internal enum class HorizontalMotion(val logLabel: String) {
    LEFT("left"), RIGHT("right"), STABLE("stable"), UNKNOWN("unknown")
}

internal enum class RelativeRoadMotion(val logLabel: String) {
    APPROACHING("approaching"), RECEDING("receding"), STABLE("stable"), UNKNOWN("unknown")
}

internal enum class RoadLaneRelation { LEFT, EGO, RIGHT, UNKNOWN }

private fun NormalizedDetectionBox.centerX(): Float = (left + right) / 2f
private fun NormalizedDetectionBox.centerY(): Float = (top + bottom) / 2f
private fun NormalizedDetectionBox.area(): Float =
    (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)

private fun NormalizedDetectionBox.interpolate(
    target: NormalizedDetectionBox,
    alpha: Float,
): NormalizedDetectionBox = NormalizedDetectionBox(
    left = lerp(left, target.left, alpha),
    top = lerp(top, target.top, alpha),
    right = lerp(right, target.right, alpha),
    bottom = lerp(bottom, target.bottom, alpha),
)

private fun lerp(start: Float, end: Float, alpha: Float): Float =
    start + (end - start) * alpha

private const val CONFIRMATION_HITS = 2
private const val MAX_MISSED_INFERENCES = 1
private const val MAX_TRACK_AGE_MS = 3_000L
private const val MAX_TRAFFIC_LIGHT_MISSED_INFERENCES = 6
private const val MAX_TRAFFIC_LIGHT_TRACK_AGE_MS = 5_000L
private const val MIN_ASSOCIATION_IOU = 0.12f
private const val MAX_ASSOCIATION_CENTER_DISTANCE = 0.30f
private const val MIN_SIZE_RATIO = 0.30f
private const val BOX_SMOOTHING_ALPHA = 0.65f
private const val CONFIDENCE_SMOOTHING_ALPHA = 0.50f
private const val VELOCITY_SMOOTHING_ALPHA = 0.55f
private const val HORIZONTAL_MOTION_THRESHOLD = 0.025f
private const val AREA_MOTION_THRESHOLD = 0.15f
private const val TRAFFIC_LIGHT_CONFIRMATION_HITS = 2
private const val TRAFFIC_LIGHT_MIN_CONFIDENCE = 0.25f
