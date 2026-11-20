package com.ts10.inspector.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVisionSemanticsTest {
    @Test
    fun `classifies vertical traffic light colors`() {
        assertEquals(TrafficLightColor.RED, classifyTrafficLight(lightFrame(0), fullBox()).color)
        assertEquals(TrafficLightColor.AMBER, classifyTrafficLight(lightFrame(1), fullBox()).color)
        assertEquals(TrafficLightColor.GREEN, classifyTrafficLight(lightFrame(2), fullBox()).color)
    }

    @Test
    fun `returns unknown for an unlit housing`() {
        val frame = VisionRgbFrame(18, 42, IntArray(18 * 42) { rgb(28, 30, 31) })

        assertEquals(TrafficLightColor.UNKNOWN, classifyTrafficLight(frame, fullBox()).color)
    }

    @Test
    fun `finds a red circular speed sign candidate without guessing value`() {
        val width = 96
        val height = 64
        val pixels = IntArray(width * height) { rgb(65, 70, 72) }
        val cx = 70
        val cy = 25
        val radius = 10
        for (y in 0 until height) for (x in 0 until width) {
            val distanceSquared = (x - cx) * (x - cx) + (y - cy) * (y - cy)
            if (distanceSquared in (radius - 2) * (radius - 2)..radius * radius) {
                pixels[y * width + x] = rgb(220, 28, 30)
            } else if (distanceSquared < (radius - 2) * (radius - 2)) {
                pixels[y * width + x] = rgb(230, 230, 218)
            }
        }

        val candidates = detectSpeedSignCandidates(VisionRgbFrame(width, height, pixels))

        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.first().box.left > 0.5f)
    }

    @Test
    fun `projects a lower object closer on the relative road plane`() {
        val far = projectTrackedRoadObject(track(0.48f, 0.30f, 0.56f, 0.52f))
        val near = projectTrackedRoadObject(track(0.40f, 0.48f, 0.62f, 0.90f))

        assertTrue(near.proximity > far.proximity)
        assertTrue(near.visualScale > far.visualScale)
    }

    @Test
    fun `traffic light state requires two compatible tracked observations`() {
        val tracker = LocalVisionObjectTracker()
        val first = tracker.update(1_000L, listOf(lightDetection(TrafficLightColor.RED)))
        val second = tracker.update(2_000L, listOf(lightDetection(TrafficLightColor.RED)))

        assertTrue(first.objects.isEmpty())
        assertEquals(TrafficLightColor.RED, second.objects.single().trafficLightColor)
    }

    private fun lightFrame(activeSection: Int): VisionRgbFrame {
        val width = 18
        val height = 42
        val pixels = IntArray(width * height) { rgb(20, 22, 23) }
        val centers = intArrayOf(7, 21, 35)
        val active = when (activeSection) {
            0 -> rgb(255, 28, 22)
            1 -> rgb(255, 185, 18)
            else -> rgb(25, 245, 65)
        }
        val cy = centers[activeSection]
        for (y in cy - 4..cy + 4) for (x in 5..13) {
            if ((x - 9) * (x - 9) + (y - cy) * (y - cy) <= 16) pixels[y * width + x] = active
        }
        return VisionRgbFrame(width, height, pixels)
    }

    private fun lightDetection(color: TrafficLightColor) = LocalVisionDetection(
        RoadObjectKind.TRAFFIC_LIGHT,
        0.8f,
        NormalizedDetectionBox(0.45f, 0.10f, 0.55f, 0.40f),
        trafficLightColor = color,
        trafficLightColorConfidence = 0.8f,
    )

    private fun track(left: Float, top: Float, right: Float, bottom: Float) = TrackedRoadObject(
        id = 1,
        kind = RoadObjectKind.CAR,
        confidence = 0.8f,
        box = NormalizedDetectionBox(left, top, right, bottom),
        hits = 3,
        missedInferences = 0,
        ageMs = 2_000,
        lastSeenAgeMs = 0,
        horizontalMotion = HorizontalMotion.STABLE,
        relativeMotion = RelativeRoadMotion.STABLE,
        horizontalVelocity = 0f,
        areaVelocity = 0f,
        laneRelation = RoadLaneRelation.UNKNOWN,
    )

    private fun fullBox() = NormalizedDetectionBox(0f, 0f, 1f, 1f)
    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b
}
