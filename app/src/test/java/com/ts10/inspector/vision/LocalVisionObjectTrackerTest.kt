package com.ts10.inspector.vision

import com.ts10.inspector.dashboard.isForwardCameraPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVisionObjectTrackerTest {
    @Test
    fun `keeps front vision active during QF transient camera position`() {
        assertTrue(isForwardCameraPosition(null))
        assertTrue(isForwardCameraPosition(-1))
        assertTrue(isForwardCameraPosition(0))
        assertEquals(false, isForwardCameraPosition(1))
    }

    @Test
    fun `requires two observations before publishing a track`() {
        val tracker = LocalVisionObjectTracker()

        val first = tracker.update(1_000L, listOf(detection(RoadObjectKind.CAR, 0.10f, 0.40f)))
        val second = tracker.update(2_000L, listOf(detection(RoadObjectKind.CAR, 0.11f, 0.40f)))

        assertTrue(first.objects.isEmpty())
        assertEquals(1, first.tentativeCount)
        assertEquals(1L, second.objects.single().id)
        assertEquals(1L, second.totalConfirmed)
        assertEquals(HorizontalMotion.STABLE, second.objects.single().horizontalMotion)
    }

    @Test
    fun `preserves identity through one missed inference`() {
        val tracker = LocalVisionObjectTracker()
        tracker.update(1_000L, listOf(detection(RoadObjectKind.CAR, 0.10f, 0.40f)))
        val confirmed = tracker.update(2_000L, listOf(detection(RoadObjectKind.CAR, 0.11f, 0.40f)))
        val missed = tracker.update(3_000L, emptyList())
        val recovered = tracker.update(4_000L, listOf(detection(RoadObjectKind.CAR, 0.12f, 0.40f)))

        assertEquals(confirmed.objects.single().id, missed.objects.single().id)
        assertEquals(1, missed.objects.single().missedInferences)
        assertEquals(confirmed.objects.single().id, recovered.objects.single().id)
        assertEquals(0, recovered.objects.single().missedInferences)
    }

    @Test
    fun `expires a track after two missed inferences`() {
        val tracker = LocalVisionObjectTracker()
        tracker.update(1_000L, listOf(detection(RoadObjectKind.CAR, 0.10f, 0.40f)))
        tracker.update(2_000L, listOf(detection(RoadObjectKind.CAR, 0.10f, 0.40f)))
        tracker.update(3_000L, emptyList())

        val expired = tracker.update(4_000L, emptyList())

        assertTrue(expired.objects.isEmpty())
        assertEquals(listOf(1L), expired.expiredIds)
        assertEquals(1L, expired.totalExpired)
    }

    @Test
    fun `keeps an intermittently detected traffic light visible`() {
        val tracker = LocalVisionObjectTracker()
        val light = detection(RoadObjectKind.TRAFFIC_LIGHT, 0.60f, 0.15f)
        tracker.update(1_000L, listOf(light))
        tracker.update(1_500L, listOf(light))

        repeat(5) { index ->
            val update = tracker.update(2_000L + index * 500L, emptyList())
            assertEquals(1, update.objects.size)
        }
    }

    @Test
    fun `reports pedestrian moving right and left`() {
        val rightTracker = LocalVisionObjectTracker()
        rightTracker.update(1_000L, listOf(detection(RoadObjectKind.PERSON, 0.10f, 0.25f)))
        val movingRight = rightTracker.update(
            2_000L,
            listOf(detection(RoadObjectKind.PERSON, 0.28f, 0.25f)),
        )
        val leftTracker = LocalVisionObjectTracker()
        leftTracker.update(1_000L, listOf(detection(RoadObjectKind.PERSON, 0.70f, 0.25f)))
        val movingLeft = leftTracker.update(
            2_000L,
            listOf(detection(RoadObjectKind.PERSON, 0.50f, 0.25f)),
        )

        assertEquals(HorizontalMotion.RIGHT, movingRight.objects.single().horizontalMotion)
        assertEquals(HorizontalMotion.LEFT, movingLeft.objects.single().horizontalMotion)
    }

    @Test
    fun `reports approaching object from growing area`() {
        val tracker = LocalVisionObjectTracker()
        tracker.update(
            1_000L,
            listOf(detection(RoadObjectKind.TRUCK, 0.35f, 0.35f, width = 0.16f, height = 0.18f)),
        )

        val approaching = tracker.update(
            2_000L,
            listOf(detection(RoadObjectKind.TRUCK, 0.31f, 0.30f, width = 0.25f, height = 0.30f)),
        )

        assertEquals(RelativeRoadMotion.APPROACHING, approaching.objects.single().relativeMotion)
        assertEquals(RoadLaneRelation.UNKNOWN, approaching.objects.single().laneRelation)
    }

    @Test
    fun `does not associate different classes`() {
        val tracker = LocalVisionObjectTracker()
        tracker.update(1_000L, listOf(detection(RoadObjectKind.CAR, 0.20f, 0.30f)))

        val update = tracker.update(2_000L, listOf(detection(RoadObjectKind.TRUCK, 0.20f, 0.30f)))

        assertTrue(update.objects.isEmpty())
        assertEquals(2L, update.totalCreated)
        assertEquals(2, update.tentativeCount)
    }

    @Test
    fun `publishes ego lane relation from a confirmed corridor`() {
        val tracker = LocalVisionObjectTracker()
        val lane = RoadLaneGeometry(
            left = RoadLaneBoundary(0.44f, 0.43f, 0.20f, 0.94f, 0.8f),
            right = RoadLaneBoundary(0.56f, 0.43f, 0.80f, 0.94f, 0.8f),
            confidence = 0.8f,
        )
        tracker.update(1_000L, listOf(detection(RoadObjectKind.CAR, 0.45f, 0.55f)), lane)

        val confirmed = tracker.update(
            2_000L,
            listOf(detection(RoadObjectKind.CAR, 0.46f, 0.55f)),
            lane,
        )

        assertEquals(RoadLaneRelation.EGO, confirmed.objects.single().laneRelation)
    }

    private fun detection(
        kind: RoadObjectKind,
        left: Float,
        top: Float,
        width: Float = 0.12f,
        height: Float = 0.24f,
    ) = LocalVisionDetection(
        kind = kind,
        score = 0.80f,
        box = NormalizedDetectionBox(left, top, left + width, top + height),
    )
}
