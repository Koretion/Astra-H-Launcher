package com.ts10.inspector.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class LocalVisionLaneDetectorTest {
    @Test
    fun `finds a perspective corridor in a synthetic road frame`() {
        val width = 160
        val height = 90
        val luma = ByteArray(width * height) { 25 }
        drawLine(luma, width, height, 0.45f, 0.43f, 0.18f, 0.94f)
        drawLine(luma, width, height, 0.55f, 0.43f, 0.82f, 0.94f)

        val lane = detectLaneGeometry(luma, width, height)

        assertNotNull(lane)
        assertTrue(lane!!.left.nearX < lane.left.farX)
        assertTrue(lane.right.nearX > lane.right.farX)
        assertTrue(lane.confidence > 0.30f)
    }

    @Test
    fun `rejects a frame without lane edges`() {
        assertNull(detectLaneGeometry(ByteArray(160 * 90) { 80 }, 160, 90))
    }

    @Test
    fun `requires three coherent observations before publishing a lane`() {
        val tracker = LocalVisionLaneTracker()
        val lane = lane()

        assertNull(tracker.update(lane))
        assertNull(tracker.update(lane))
        assertNotNull(tracker.update(lane))
        assertEquals(3, tracker.coherentFrames)
    }

    @Test
    fun `keeps confirmed lane while an incoherent replacement is unconfirmed`() {
        val tracker = LocalVisionLaneTracker()
        val original = lane()
        repeat(3) { tracker.update(original) }

        val replacement = shiftedLane(0.20f)
        val retained = tracker.update(replacement)

        assertNotNull(retained)
        assertTrue(retained!!.left.farX < replacement.left.farX)
    }

    @Test
    fun `switches to a new geometry only after three coherent observations`() {
        val tracker = LocalVisionLaneTracker()
        repeat(3) { tracker.update(lane()) }
        val replacement = shiftedLane(0.20f)

        tracker.update(replacement)
        tracker.update(replacement)
        val switched = tracker.update(replacement)

        assertNotNull(switched)
        assertEquals(replacement.left.farX, switched!!.left.farX, 0.001f)
    }

    @Test
    fun `expires a confirmed lane after six missing observations`() {
        val tracker = LocalVisionLaneTracker()
        repeat(3) { tracker.update(lane()) }

        repeat(5) { assertNotNull(tracker.update(null)) }
        assertNull(tracker.update(null))
    }

    @Test
    fun `assigns objects to left ego and right lanes`() {
        val lane = lane()

        assertEquals(
            RoadLaneRelation.LEFT,
            roadLaneRelation(NormalizedDetectionBox(0.05f, 0.60f, 0.15f, 0.85f), lane),
        )
        assertEquals(
            RoadLaneRelation.EGO,
            roadLaneRelation(NormalizedDetectionBox(0.44f, 0.60f, 0.56f, 0.85f), lane),
        )
        assertEquals(
            RoadLaneRelation.RIGHT,
            roadLaneRelation(NormalizedDetectionBox(0.85f, 0.60f, 0.95f, 0.85f), lane),
        )
    }

    @Test
    fun `does not assign an overhead sign to a lane`() {
        assertEquals(
            RoadLaneRelation.UNKNOWN,
            roadLaneRelation(NormalizedDetectionBox(0.45f, 0.05f, 0.55f, 0.20f), lane()),
        )
    }

    @Test
    fun `derives signed road curvature while keeping the car static`() {
        assertEquals(0f, roadCurveFromLane(lane()), 0.001f)
        assertTrue(roadCurveFromLane(shiftedFarLane(-0.10f)) < 0f)
    }

    @Test
    fun `steering curves only the moving road horizon with Astra sign convention`() {
        assertEquals(0f, roadCurveFromSteering(90f, 0), 0.001f)
        assertEquals(0f, roadCurveFromSteering(1.5f, 50), 0.001f)
        assertTrue(roadCurveFromSteering(90f, 30) < 0f)
        assertTrue(roadCurveFromSteering(-90f, 30) > 0f)
        assertTrue(
            kotlin.math.abs(roadCurveFromSteering(90f, 50)) >
                kotlin.math.abs(roadCurveFromSteering(90f, 10)),
        )
    }

    @Test
    fun `steering prediction dominates but retains mapped road context`() {
        assertEquals(0.08f, resolveRenderedRoadCurve(0f, 0.08f, 0f), 0.001f)
        val resolved = resolveRenderedRoadCurve(cameraCurve = -0.08f, osmCurve = 0f, steeringCurve = -0.12f)
        assertTrue(resolved < -0.08f)
        assertTrue(resolved > -0.12f)
    }

    @Test
    fun `curved road remains tangent to the car and reaches its bend at the horizon`() {
        assertEquals(-0.16f, roadCurveOffsetFraction(-0.16f, 0f), 0.001f)
        assertEquals(-0.08f, roadCurveOffsetFraction(-0.16f, 0.5f), 0.001f)
        assertEquals(0f, roadCurveOffsetFraction(-0.16f, 1f), 0.001f)
        assertTrue(
            kotlin.math.abs(roadCurveOffsetFraction(-0.16f, 0.25f)) >
                kotlin.math.abs(roadCurveOffsetFraction(-0.16f, 0.75f)),
        )
    }

    @Test
    fun `weak stable geometry cannot steer the rendered road`() {
        val weak = lane().copy(confidence = 0.17f)

        assertEquals(0f, roadCurveFromLane(weak), 0.001f)
    }

    private fun lane() = RoadLaneGeometry(
        left = RoadLaneBoundary(0.44f, 0.43f, 0.18f, 0.94f, 0.8f),
        right = RoadLaneBoundary(0.56f, 0.43f, 0.82f, 0.94f, 0.8f),
        confidence = 0.8f,
    )

    private fun shiftedLane(offset: Float): RoadLaneGeometry {
        val lane = lane()
        return lane.copy(
            left = lane.left.copy(farX = lane.left.farX + offset, nearX = lane.left.nearX + offset),
            right = lane.right.copy(farX = lane.right.farX + offset, nearX = lane.right.nearX + offset),
        )
    }

    private fun shiftedFarLane(offset: Float): RoadLaneGeometry {
        val lane = lane()
        return lane.copy(
            left = lane.left.copy(farX = lane.left.farX + offset),
            right = lane.right.copy(farX = lane.right.farX + offset),
        )
    }

    private fun drawLine(
        image: ByteArray,
        width: Int,
        height: Int,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
    ) {
        repeat(80) { index ->
            val t = index / 79f
            val x = ((startX + (endX - startX) * t) * (width - 1)).roundToInt()
            val y = ((startY + (endY - startY) * t) * (height - 1)).roundToInt()
            for (offset in -1..1) {
                val px = (x + offset).coerceIn(0, width - 1)
                image[y * width + px] = 245.toByte()
            }
        }
    }
}
