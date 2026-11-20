package com.ts10.inspector.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.ts10.inspector.camera.AdasLaneBoundary
import com.ts10.inspector.camera.AdasPoint
import com.ts10.inspector.camera.AdasSceneState
import com.ts10.inspector.camera.AdasWarning
import com.ts10.inspector.environment.OsmRoadContext
import com.ts10.inspector.environment.RoadLineStyle
import com.ts10.inspector.vision.RoadLaneGeometry
import com.ts10.inspector.vision.RoadLaneRelation
import com.ts10.inspector.vision.RoadObjectKind
import com.ts10.inspector.vision.TrackedRoadObject
import com.ts10.inspector.vision.TrafficLightColor
import com.ts10.inspector.vision.laneReliableForRoadRendering
import com.ts10.inspector.vision.projectTrackedRoadObject
import com.ts10.inspector.vision.roadCurveOffsetFraction
import kotlinx.coroutines.flow.map

/** Draws only temporally confirmed local results on the dashboard's relative road plane. */
internal fun DrawScope.drawLocalVisionScene(
    objects: List<TrackedRoadObject>,
    lane: RoadLaneGeometry?,
    osmRoad: OsmRoadContext?,
    roadScale: Float,
    roadCenterOffsetX: Float,
    roadCurve: Float,
    darkTheme: Boolean,
    maximumObjectBottomY: Float,
) {
    // OSM supplies topology, curvature and lane count, but it must not suppress markings that
    // the camera has actually confirmed. Both sources describe different useful layers.
    lane?.takeIf(::laneReliableForRoadRendering)?.let { geometry ->
        val laneColor = if (darkTheme) Color(0xFF7ED6FF) else Color(0xFF0077B8)
        fun point(x: Float, y: Float) = Offset(x * size.width, y * size.height)
        drawLine(
            laneColor.copy(alpha = 0.72f),
            point(geometry.left.farX, geometry.left.farY),
            point(geometry.left.nearX, geometry.left.nearY),
            size.width * 0.006f,
            StrokeCap.Round,
        )
        drawLine(
            laneColor.copy(alpha = 0.72f),
            point(geometry.right.farX, geometry.right.farY),
            point(geometry.right.nearX, geometry.right.nearY),
            size.width * 0.006f,
            StrokeCap.Round,
        )
    }

    objects.forEach { tracked ->
        val projection = projectTrackedRoadObject(tracked)
        val yFraction = 0.13f + projection.proximity * 0.62f
        val projectedCenter = if (tracked.kind == RoadObjectKind.TRAFFIC_LIGHT) {
            // Traffic lights are overhead/roadside objects, so vehicle-ground projection pushes
            // them outside the scene (the real log reached lateral=+/-2.20). Preserve their
            // camera-relative side and height instead.
            Offset(
                x = ((tracked.box.left + tracked.box.right) / 2f)
                    .coerceIn(0.04f, 0.96f) * size.width,
                y = (0.05f + tracked.box.bottom.coerceIn(0f, 0.72f) * 0.62f) * size.height,
            )
        } else if (osmRoad != null) {
            val perspective = yFraction.coerceIn(0f, 1f)
            val laneWidth = osmLaneWidthAt(perspective, roadScale)
            Offset(
                x = size.width / 2f + roadCenterOffsetX +
                    roadCurveOffsetFraction(roadCurve, perspective) * size.width +
                    projection.lateral * laneWidth,
                y = size.height * yFraction,
            )
        } else {
            Offset(
                size.width * (0.5f + projection.lateral * 0.20f),
                size.height * yFraction,
            )
        }
        val alpha = (0.38f + tracked.confidence * 0.55f).coerceIn(0.38f, 0.93f)
        when (tracked.kind) {
            RoadObjectKind.PERSON -> {
                val height = size.height * (0.045f + projection.visualScale * 0.105f)
                val center = projectedCenter.copy(
                    y = projectedCenter.y.coerceAtMost(maximumObjectBottomY - height * 0.38f),
                )
                val color = Color(0xFFFFB547).copy(alpha = alpha)
                drawCircle(color, height * 0.12f, Offset(center.x, center.y - height * 0.37f))
                val torso = Path().apply {
                    moveTo(center.x - height * 0.12f, center.y - height * 0.20f)
                    lineTo(center.x + height * 0.12f, center.y - height * 0.20f)
                    lineTo(center.x + height * 0.09f, center.y + height * 0.10f)
                    lineTo(center.x - height * 0.09f, center.y + height * 0.10f)
                    close()
                }
                drawPath(torso, color)
                drawLine(color, Offset(center.x - height * 0.08f, center.y - height * 0.12f), Offset(center.x - height * 0.22f, center.y + height * 0.08f), height * 0.055f, StrokeCap.Round)
                drawLine(color, Offset(center.x + height * 0.08f, center.y - height * 0.12f), Offset(center.x + height * 0.22f, center.y + height * 0.08f), height * 0.055f, StrokeCap.Round)
                drawLine(color, Offset(center.x - height * 0.05f, center.y + height * 0.08f), Offset(center.x - height * 0.15f, center.y + height * 0.39f), height * 0.065f, StrokeCap.Round)
                drawLine(color, Offset(center.x + height * 0.05f, center.y + height * 0.08f), Offset(center.x + height * 0.15f, center.y + height * 0.39f), height * 0.065f, StrokeCap.Round)
            }

            RoadObjectKind.TRAFFIC_LIGHT -> {
                val center = projectedCenter
                val activeColor = when (tracked.trafficLightColor) {
                    TrafficLightColor.RED -> Color(0xFFFF3B30)
                    TrafficLightColor.AMBER -> Color(0xFFFFB000)
                    TrafficLightColor.GREEN -> Color(0xFF32D16D)
                    TrafficLightColor.UNKNOWN -> Color(0xFFB7C1CC)
                }
                val housingWidth = size.width * 0.038f
                val housingHeight = housingWidth * 2.55f
                drawLine(Color(0xFF56606A).copy(alpha = alpha), center.copy(y = center.y + housingHeight * 0.45f), center.copy(y = center.y + housingHeight), housingWidth * 0.15f)
                drawRoundRect(
                    Color(0xFF252A30).copy(alpha = alpha),
                    Offset(center.x - housingWidth / 2f, center.y - housingHeight / 2f),
                    Size(housingWidth, housingHeight),
                    CornerRadius(housingWidth * 0.18f),
                )
                listOf(TrafficLightColor.RED, TrafficLightColor.AMBER, TrafficLightColor.GREEN).forEachIndexed { index, light ->
                    val lit = tracked.trafficLightColor == light
                    val lampColor = when (light) {
                        TrafficLightColor.RED -> Color(0xFFFF3B30)
                        TrafficLightColor.AMBER -> Color(0xFFFFB000)
                        TrafficLightColor.GREEN -> Color(0xFF32D16D)
                        TrafficLightColor.UNKNOWN -> activeColor
                    }
                    drawCircle(
                        lampColor.copy(alpha = if (lit) alpha else alpha * 0.18f),
                        housingWidth * 0.25f,
                        Offset(center.x, center.y - housingHeight * 0.28f + index * housingHeight * 0.28f),
                    )
                }
            }

            RoadObjectKind.STOP_SIGN -> {
                val center = projectedCenter
                drawCircle(Color(0xFFE43D48).copy(alpha = alpha), size.width * 0.015f, center)
            }

            else -> {
                val width = size.width * (0.025f + projection.visualScale * 0.055f)
                val height = width * when (tracked.kind) {
                    RoadObjectKind.BICYCLE, RoadObjectKind.MOTORCYCLE -> 1.25f
                    RoadObjectKind.BUS, RoadObjectKind.TRUCK -> 1.95f
                    else -> 1.58f
                }
                val center = projectedCenter.copy(
                    y = projectedCenter.y.coerceAtMost(maximumObjectBottomY - height / 2f),
                )
                val color = if (tracked.laneRelation == RoadLaneRelation.EGO) Color(0xFFFF5A63) else Color(0xFF52C7FF)
                drawDetectedRoadVehicle(center, width, height, color, alpha, tracked.kind, darkTheme)
            }
        }
    }
}

internal fun DrawScope.drawDetectedRoadVehicle(
    center: Offset,
    width: Float,
    height: Float,
    color: Color,
    alpha: Float,
    kind: RoadObjectKind,
    darkTheme: Boolean,
) {
    if (kind == RoadObjectKind.BICYCLE || kind == RoadObjectKind.MOTORCYCLE) {
        drawOval(
            color.copy(alpha = alpha * 0.32f),
            Offset(center.x - width * 0.22f, center.y - height / 2f),
            Size(width * 0.44f, height),
        )
        drawLine(
            color.copy(alpha = alpha),
            Offset(center.x, center.y - height * 0.40f),
            Offset(center.x, center.y + height * 0.40f),
            width * 0.12f,
            StrokeCap.Round,
        )
        return
    }
    val top = center.y - height / 2f
    val left = center.x - width / 2f
    val bodyColor = if (darkTheme) Color(0xFF9DA8B2) else Color(0xFF56616B)
    val glass = if (darkTheme) Color(0xFF15212B) else Color(0xFFD8E2E9)
    val wheel = if (darkTheme) Color(0xFF090C10) else Color(0xFF252A2F)
    val longBody = kind == RoadObjectKind.BUS || kind == RoadObjectKind.TRUCK

    drawOval(
        color = Color.Black.copy(alpha = alpha * 0.22f),
        topLeft = Offset(left - width * 0.08f, top + height * 0.10f),
        size = Size(width * 1.16f, height * 0.94f),
    )
    val body = Path().apply {
        moveTo(center.x, top)
        cubicTo(
            center.x + width * 0.30f, top,
            center.x + width * 0.43f, top + height * 0.10f,
            center.x + width * 0.44f, top + height * 0.24f,
        )
        lineTo(center.x + width * 0.50f, top + height * 0.72f)
        cubicTo(
            center.x + width * 0.48f, top + height * 0.91f,
            center.x + width * 0.28f, top + height,
            center.x, top + height,
        )
        cubicTo(
            center.x - width * 0.28f, top + height,
            center.x - width * 0.48f, top + height * 0.91f,
            center.x - width * 0.50f, top + height * 0.72f,
        )
        lineTo(center.x - width * 0.44f, top + height * 0.24f)
        cubicTo(
            center.x - width * 0.43f, top + height * 0.10f,
            center.x - width * 0.30f, top,
            center.x, top,
        )
        close()
    }
    drawPath(body, bodyColor.copy(alpha = alpha * 0.88f))
    drawPath(
        body,
        color.copy(alpha = alpha),
        style = Stroke(width = (width * 0.07f).coerceAtLeast(1f)),
    )

    // Four visible tyres make the scale and top-down orientation immediately readable.
    val wheelWidth = width * 0.13f
    val wheelHeight = height * if (longBody) 0.16f else 0.19f
    listOf(0.24f, 0.69f).forEach { longitudinal ->
        listOf(-1f, 1f).forEach { side ->
            drawRoundRect(
                color = wheel.copy(alpha = alpha),
                topLeft = Offset(
                    center.x + side * width * 0.50f - wheelWidth / 2f,
                    top + height * longitudinal - wheelHeight / 2f,
                ),
                size = Size(wheelWidth, wheelHeight),
                cornerRadius = CornerRadius(wheelWidth / 2f),
            )
        }
    }

    // Windscreen, roof and rear glass give the silhouette the same readable depth as the car art.
    drawRoundRect(
        glass.copy(alpha = alpha * 0.92f),
        Offset(center.x - width * 0.31f, top + height * 0.18f),
        Size(width * 0.62f, height * if (longBody) 0.20f else 0.24f),
        CornerRadius(width * 0.11f),
    )
    if (!longBody) {
        drawRoundRect(
            glass.copy(alpha = alpha * 0.74f),
            Offset(center.x - width * 0.29f, top + height * 0.58f),
            Size(width * 0.58f, height * 0.17f),
            CornerRadius(width * 0.09f),
        )
    }
    drawLine(
        color = Color.White.copy(alpha = alpha * 0.26f),
        start = Offset(center.x - width * 0.20f, top + height * 0.09f),
        end = Offset(center.x - width * 0.30f, top + height * 0.54f),
        strokeWidth = (width * 0.035f).coerceAtLeast(0.8f),
        cap = StrokeCap.Round,
    )
    listOf(-1f, 1f).forEach { side ->
        drawRoundRect(
            color = Color(0xFFFF3B30).copy(alpha = alpha * 0.94f),
            topLeft = Offset(
                center.x + side * width * 0.30f - width * 0.09f,
                top + height * 0.86f,
            ),
            size = Size(width * 0.18f, height * 0.045f),
            cornerRadius = CornerRadius(width * 0.04f),
        )
    }
}

internal fun DrawScope.drawAdasScene(
    state: AdasSceneState,
    darkTheme: Boolean,
    showDirectionGuide: Boolean,
    roadCurve: Float,
) {
    val blue = if (darkTheme) Color(0xFF1697FF) else Color(0xFF0077D8)
    fun map(point: AdasPoint): Offset = Offset(
        x = (point.x / state.logicalWidth.coerceAtLeast(1)) * size.width,
        y = (point.y / state.logicalHeight.coerceAtLeast(1)) * size.height,
    )
    fun map(boundary: AdasLaneBoundary): RenderedLaneBoundary = RenderedLaneBoundary(
        far = map(boundary.far),
        near = map(boundary.near),
    )

    val detectedLeft = state.lane?.left?.let(::map)
    val detectedRight = state.lane?.right?.let(::map)
    val corridor = resolveAdasLaneCorridor(detectedLeft, detectedRight)
    val corridorCurve = if (detectedLeft == null && detectedRight == null) {
        roadCurve
    } else {
        roadCurve * 0.45f
    }

    if (detectedLeft != null || detectedRight != null) {
        drawPath(
            corridor.asCurvedPath(corridorCurve, size.width),
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.28f to if (darkTheme) {
                    Color(0xFF68737E).copy(alpha = 0.10f)
                } else {
                    Color(0xFF84909B).copy(alpha = 0.08f)
                },
                1f to if (darkTheme) {
                    Color(0xFFB4BEC8).copy(alpha = 0.20f)
                } else {
                    Color(0xFF66717B).copy(alpha = 0.13f)
                },
                startY = corridor.leftFar.y.coerceAtMost(corridor.rightFar.y),
                endY = corridor.leftNear.y.coerceAtLeast(corridor.rightNear.y),
            ),
        )
    }

    if (showDirectionGuide) {
        val guide = corridor.inset(leftFraction = 0.27f, rightFraction = 0.73f)
        drawPath(
            guide.asCurvedPath(corridorCurve, size.width),
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to blue.copy(alpha = 0.03f),
                    0.35f to blue.copy(alpha = 0.14f),
                    1f to blue.copy(alpha = 0.38f),
                ),
                startY = guide.leftFar.y,
                endY = guide.leftNear.y,
            ),
        )
        drawPath(
            path = guide.left.asCurvedPath(corridorCurve, size.width),
            color = blue.copy(alpha = 0.94f),
            style = Stroke(width = size.width * 0.008f, cap = StrokeCap.Round),
        )
        drawPath(
            path = guide.right.asCurvedPath(corridorCurve, size.width),
            color = blue.copy(alpha = 0.94f),
            style = Stroke(width = size.width * 0.008f, cap = StrokeCap.Round),
        )
    }

    val normalBoundaryColor = if (darkTheme) Color.White.copy(alpha = 0.82f) else Color(0xFF52606D)
    fun boundaryColor(left: Boolean): Color {
        if (!state.warningActive) return normalBoundaryColor
        return when (state.lastWarning) {
            AdasWarning.LDW -> Color(0xFFFFB020)
            AdasWarning.LDW_LEFT -> if (left) Color(0xFFFFB020) else normalBoundaryColor
            AdasWarning.LDW_RIGHT -> if (!left) Color(0xFFFFB020) else normalBoundaryColor
            else -> normalBoundaryColor
        }
    }
    detectedLeft?.let { boundary ->
        drawPath(
            path = boundary.asCurvedPath(corridorCurve, size.width),
            color = boundaryColor(left = true),
            style = Stroke(width = size.width * 0.007f, cap = StrokeCap.Round),
        )
    }
    detectedRight?.let { boundary ->
        drawPath(
            path = boundary.asCurvedPath(corridorCurve, size.width),
            color = boundaryColor(left = false),
            style = Stroke(width = size.width * 0.007f, cap = StrokeCap.Round),
        )
    }

    state.vehicles.forEach { vehicle ->
        drawGhostVehicle(vehicle, darkTheme, roadCurve)
    }
}

private data class RenderedLaneBoundary(
    val far: Offset,
    val near: Offset,
)

private fun RenderedLaneBoundary.asCurvedPath(
    roadCurve: Float,
    canvasWidth: Float,
): Path = Path().apply {
    val samples = 24
    (0..samples).forEachIndexed { index, step ->
        val perspective = step / samples.toFloat()
        val point = lerpOffset(far, near, perspective)
        val x = point.x + roadCurveOffsetFraction(roadCurve, perspective) * canvasWidth
        if (index == 0) moveTo(x, point.y) else lineTo(x, point.y)
    }
}

private data class RenderedLaneCorridor(
    val leftFar: Offset,
    val leftNear: Offset,
    val rightFar: Offset,
    val rightNear: Offset,
) {
    val left: RenderedLaneBoundary get() = RenderedLaneBoundary(leftFar, leftNear)
    val right: RenderedLaneBoundary get() = RenderedLaneBoundary(rightFar, rightNear)

    fun asCurvedPath(roadCurve: Float, canvasWidth: Float): Path = Path().apply {
        val samples = 24
        for (step in 0..samples) {
            val perspective = step / samples.toFloat()
            val point = lerpOffset(leftFar, leftNear, perspective)
            val x = point.x + roadCurveOffsetFraction(roadCurve, perspective) * canvasWidth
            if (step == 0) moveTo(x, point.y) else lineTo(x, point.y)
        }
        for (step in samples downTo 0) {
            val perspective = step / samples.toFloat()
            val point = lerpOffset(rightFar, rightNear, perspective)
            lineTo(
                point.x + roadCurveOffsetFraction(roadCurve, perspective) * canvasWidth,
                point.y,
            )
        }
        close()
    }

    fun inset(leftFraction: Float, rightFraction: Float): RenderedLaneCorridor = RenderedLaneCorridor(
        leftFar = lerpOffset(leftFar, rightFar, leftFraction),
        leftNear = lerpOffset(leftNear, rightNear, leftFraction),
        rightFar = lerpOffset(leftFar, rightFar, rightFraction),
        rightNear = lerpOffset(leftNear, rightNear, rightFraction),
    )
}

private fun DrawScope.resolveAdasLaneCorridor(
    detectedLeft: RenderedLaneBoundary?,
    detectedRight: RenderedLaneBoundary?,
): RenderedLaneCorridor {
    val fallback = RenderedLaneCorridor(
        leftFar = Offset(size.width * 0.475f, size.height * 0.09f),
        leftNear = Offset(size.width * 0.345f, size.height * 0.92f),
        rightFar = Offset(size.width * 0.525f, size.height * 0.09f),
        rightNear = Offset(size.width * 0.655f, size.height * 0.92f),
    )
    val farWidth = fallback.rightFar.x - fallback.leftFar.x
    val nearWidth = fallback.rightNear.x - fallback.leftNear.x
    val left = detectedLeft ?: detectedRight?.let { right ->
        RenderedLaneBoundary(
            far = Offset(right.far.x - farWidth, right.far.y),
            near = Offset(right.near.x - nearWidth, right.near.y),
        )
    }
    val right = detectedRight ?: detectedLeft?.let { leftBoundary ->
        RenderedLaneBoundary(
            far = Offset(leftBoundary.far.x + farWidth, leftBoundary.far.y),
            near = Offset(leftBoundary.near.x + nearWidth, leftBoundary.near.y),
        )
    }
    return RenderedLaneCorridor(
        leftFar = left?.far ?: fallback.leftFar,
        leftNear = left?.near ?: fallback.leftNear,
        rightFar = right?.far ?: fallback.rightFar,
        rightNear = right?.near ?: fallback.rightNear,
    )
}

private fun lerpOffset(start: Offset, end: Offset, fraction: Float): Offset = Offset(
    x = start.x + (end.x - start.x) * fraction,
    y = start.y + (end.y - start.y) * fraction,
)

internal fun DrawScope.drawRoadDepth(
    darkTheme: Boolean,
    roadScale: Float,
    roadCenterOffsetX: Float,
    roadCurve: Float,
) {
    val topY = 0f
    val bottomY = size.height
    val center = size.width / 2f + roadCenterOffsetX
    val samples = 28
    fun edgeX(perspective: Float, side: Float): Float {
        val halfWidth = size.width * (0.06f + 0.42f * perspective) * roadScale
        val bend = roadCurveOffsetFraction(roadCurve, perspective) * size.width
        return center + bend + side * halfWidth
    }
    val road = Path().apply {
        for (step in 0..samples) {
            val perspective = step / samples.toFloat()
            val x = edgeX(perspective, -1f)
            val y = topY + (bottomY - topY) * perspective
            if (step == 0) moveTo(x, y) else lineTo(x, y)
        }
        for (step in samples downTo 0) {
            val perspective = step / samples.toFloat()
            lineTo(
                edgeX(perspective, 1f),
                topY + (bottomY - topY) * perspective,
            )
        }
        close()
    }

    val roadColor = if (darkTheme) {
        Color(0xFF8F949B).copy(alpha = 0.25f)
    } else {
        Color(0xFF484D55).copy(alpha = 0.22f)
    }

    drawPath(
        road,
        brush = Brush.verticalGradient(
            0f to Color.Transparent,
            0.16f to roadColor.copy(alpha = roadColor.alpha * 0.38f),
            0.58f to roadColor.copy(alpha = roadColor.alpha * 0.82f),
            1f to roadColor,
            startY = topY,
            endY = bottomY,
        ),
    )
}

internal fun DrawScope.drawOsmLaneLayout(
    road: OsmRoadContext?,
    darkTheme: Boolean,
    roadScale: Float,
    roadCenterOffsetX: Float,
    activeLaneFromRight: Int,
    roadCurve: Float,
    speedKmh: Int,
    motionProgress: Float,
) {
    val laneCount = road?.lanesInTravelDirection?.coerceIn(1, 6) ?: return
    val lineColor = if (darkTheme) Color.White.copy(alpha = 0.66f) else Color(0xFF4E5964).copy(alpha = 0.62f)
    val activeLane = if (darkTheme) Color(0xFF1697FF) else Color(0xFF0077D8)
    val farY = size.height * 0.02f
    val nearY = size.height
    val roadCenter = size.width / 2f + roadCenterOffsetX
    val laneIndex = activeLaneFromRight.coerceIn(0, laneCount - 1)
    val samples = 28

    fun xAtBoundary(boundary: Int, perspective: Float): Float {
        val width = osmLaneWidthAt(perspective, roadScale)
        val curvedCenter = roadCenter +
            roadCurveOffsetFraction(roadCurve, perspective) * size.width
        // The car remains fixed; moving to a lane on the left reveals the lanes to its right.
        val right = curvedCenter + (laneIndex + 0.5f) * width
        return right - (laneCount - boundary) * width
    }

    fun buildBoundaryPath(boundary: Int): Path = Path().apply {
        for (step in 0..samples) {
            val perspective = step / samples.toFloat()
            val x = xAtBoundary(boundary, perspective)
            val y = farY + (nearY - farY) * perspective
            if (step == 0) moveTo(x, y) else lineTo(x, y)
        }
    }

    fun laneRibbon(leftBoundary: Int, rightBoundary: Int): Path = Path().apply {
        for (step in 0..samples) {
            val perspective = step / samples.toFloat()
            val y = farY + (nearY - farY) * perspective
            val x = xAtBoundary(leftBoundary, perspective)
            if (step == 0) moveTo(x, y) else lineTo(x, y)
        }
        for (step in samples downTo 0) {
            val perspective = step / samples.toFloat()
            lineTo(
                xAtBoundary(rightBoundary, perspective),
                farY + (nearY - farY) * perspective,
            )
        }
        close()
    }

    fun shoulderPath(left: Boolean): Path {
        val boundary = if (left) 0 else laneCount
        val direction = if (left) -1f else 1f
        return Path().apply {
            for (step in 0..samples) {
                val perspective = step / samples.toFloat()
                val y = farY + (nearY - farY) * perspective
                val x = xAtBoundary(boundary, perspective)
                if (step == 0) moveTo(x, y) else lineTo(x, y)
            }
            for (step in samples downTo 0) {
                val perspective = step / samples.toFloat()
                val width = osmLaneWidthAt(perspective, roadScale)
                lineTo(
                    xAtBoundary(boundary, perspective) +
                        direction * width * (0.65f - perspective * 0.31f),
                    farY + (nearY - farY) * perspective,
                )
            }
            close()
        }
    }
    val shoulderColor = if (darkTheme) Color(0xFF78828B) else Color(0xFFB8BEC4)
    drawPath(shoulderPath(left = true), shoulderColor.copy(alpha = 0.28f))
    drawPath(shoulderPath(left = false), shoulderColor.copy(alpha = 0.28f))

    val activeRightBoundary = laneCount - laneIndex
    val activeLeftBoundary = activeRightBoundary - 1
    val activeLanePath = laneRibbon(activeLeftBoundary, activeRightBoundary)
    drawPath(
        activeLanePath,
        Brush.verticalGradient(
            0f to Color.Transparent,
            0.45f to activeLane.copy(alpha = 0.04f),
            1f to activeLane.copy(alpha = 0.13f),
            startY = farY,
            endY = nearY,
        ),
    )

    if (road.isRoundabout) {
        val markerRadius = size.width * 0.052f
        val markerCenter = Offset(
            x = roadCenter + roadCurveOffsetFraction(roadCurve, 0.18f) * size.width,
            y = size.height * 0.18f,
        )
        drawArc(
            color = activeLane.copy(alpha = 0.88f),
            startAngle = 35f,
            sweepAngle = 285f,
            useCenter = false,
            topLeft = Offset(markerCenter.x - markerRadius, markerCenter.y - markerRadius),
            size = Size(markerRadius * 2f, markerRadius * 2f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = size.width * 0.009f,
                cap = StrokeCap.Round,
            ),
        )
        drawCircle(
            color = activeLane.copy(alpha = 0.13f),
            radius = markerRadius * 0.62f,
            center = markerCenter,
        )
    }

    for (boundary in 0..laneCount) {
        val style = when (boundary) {
            0 -> if (road.oneway) RoadLineStyle.SOLID else road.centerLineStyle
            laneCount -> RoadLineStyle.SOLID
            else -> road.laneLineStyles.getOrNull(boundary - 1) ?: RoadLineStyle.DASHED
        }
        if (style == RoadLineStyle.NONE) continue
        val exterior = boundary == 0 || boundary == laneCount
        val strokeWidth = size.width * when {
            exterior -> 0.011f
            style == RoadLineStyle.DOUBLE_SOLID -> 0.010f
            else -> 0.006f
        }
        val pathEffect = if (style == RoadLineStyle.DASHED || style == RoadLineStyle.UNKNOWN) {
            val dash = size.height * 0.045f
            val gap = size.height * 0.035f
            val phase = if (speedKmh > 0) -motionProgress * (dash + gap) else 0f
            PathEffect.dashPathEffect(floatArrayOf(dash, gap), phase)
        } else null
        val boundaryPath = buildBoundaryPath(boundary)
        val boundaryColor = when {
            boundary == 0 && !road.oneway -> Color(0xFFFFC928).copy(alpha = 0.90f)
            exterior -> Color(0xFFE64A4F).copy(alpha = 0.82f)
            else -> lineColor
        }
        drawPath(
            boundaryPath,
            boundaryColor,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = strokeWidth,
                cap = StrokeCap.Butt,
                pathEffect = pathEffect,
            ),
        )
        if (style == RoadLineStyle.DOUBLE_SOLID) {
            val offset = size.width * 0.012f
            val secondPath = Path().apply {
                for (step in 0..samples) {
                    val perspective = step / samples.toFloat()
                    val x = xAtBoundary(boundary, perspective) + offset
                    val y = farY + (nearY - farY) * perspective
                    if (step == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(
                secondPath,
                boundaryColor,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = strokeWidth * 0.48f,
                    cap = StrokeCap.Butt,
                ),
            )
        }
    }
}

private fun DrawScope.osmLaneWidthAt(perspective: Float, roadScale: Float): Float {
    val far = size.width * 0.055f
    // At the car, a lane must remain visibly wider than the 71 px moving vehicle body.
    val near = size.width * 0.30f
    return (far + (near - far) * perspective.coerceIn(0f, 1f)) * roadScale
}

internal fun DrawScope.drawRoadAtmosphere(darkTheme: Boolean, background: Color) {
    val mistColor = if (darkTheme) {
        Color.White.copy(alpha = 0.075f)
    } else {
        Color.White.copy(alpha = 0.16f)
    }
    val sideFade = background.copy(alpha = if (darkTheme) 0.34f else 0.18f)

    drawRect(
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.22f to mistColor.copy(alpha = mistColor.alpha * 0.45f),
                0.58f to mistColor,
                1f to Color.Transparent,
            ),
        ),
    )
    drawRect(
        brush = Brush.horizontalGradient(
            colorStops = arrayOf(
                0f to sideFade,
                0.18f to sideFade.copy(alpha = sideFade.alpha * 0.55f),
                0.34f to Color.Transparent,
                0.66f to Color.Transparent,
                0.82f to sideFade.copy(alpha = sideFade.alpha * 0.55f),
                1f to sideFade,
            ),
        ),
    )
    drawRect(
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to background.copy(alpha = if (darkTheme) 0.20f else 0.08f),
                0.12f to Color.Transparent,
                0.82f to Color.Transparent,
                1f to background.copy(alpha = if (darkTheme) 0.08f else 0.04f),
            ),
        ),
    )
}
