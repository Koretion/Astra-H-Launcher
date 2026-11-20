package com.ts10.inspector.dashboard

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.R
import com.ts10.inspector.camera.AdasDetectedVehicle
import com.ts10.inspector.camera.AdasSceneState
import com.ts10.inspector.camera.AdasWarning
import com.ts10.inspector.camera.GrabadoraHdAdasClient
import com.ts10.inspector.camera.GrabadoraHdCameraClient
import com.ts10.inspector.camera.withWarningVehicleFallback
import com.ts10.inspector.diagnostics.TelemetryLog
import com.ts10.inspector.environment.OsmRoadContext
import com.ts10.inspector.environment.OsmRoadContextProvider
import com.ts10.inspector.launcher.LauncherRuntime
import com.ts10.inspector.launcher.VehicleVisualUiState
import com.ts10.inspector.settings.DashboardDrivingZoomMode
import com.ts10.inspector.settings.DashboardVehicleColorMode
import com.ts10.inspector.settings.sceneScaleForMotion
import com.ts10.inspector.ui.DashboardColors
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.vehicle.VehicleSignalHub
import com.ts10.inspector.vehicle.fuel.distanceMeters
import com.ts10.inspector.vehicle3d.Vehicle3dOffscreenEngine
import com.ts10.inspector.vision.LocalVisionLaneDetector
import com.ts10.inspector.vision.LocalVisionObjectDetector
import com.ts10.inspector.vision.LocalVisionPipeline
import com.ts10.inspector.vision.RoadLaneGeometry
import com.ts10.inspector.vision.RoadLanePositionTracker
import com.ts10.inspector.vision.RoadObjectKind
import com.ts10.inspector.vision.TrackedRoadObject
import com.ts10.inspector.vision.TrafficLightColor
import com.ts10.inspector.vision.resolveRenderedRoadCurve
import com.ts10.inspector.vision.roadCurveFromLane
import com.ts10.inspector.vision.roadCurveFromSteering
import com.ts10.inspector.vision.roadCurveOffsetFraction
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

@Composable
internal fun RuntimeVehicleView(
    layout: LauncherLayout,
    colors: DashboardColors,
    vehicleColorMode: DashboardVehicleColorMode,
    drivingZoomMode: DashboardDrivingZoomMode,
    vehicleSceneMode: VehicleSceneMode,
) {
    val state by LauncherRuntime.vehicleVisualState.collectAsStateWithLifecycle()
    val signals by VehicleSignalHub.state.collectAsStateWithLifecycle()
    val blinkers by remember {
        LauncherRuntime.state.map { it.leftBlinker to it.rightBlinker }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(false to false)
    val steeringAngle = signals.steeringAngleDegrees?.value
    val adasState by GrabadoraHdAdasClient.state.collectAsStateWithLifecycle()
    val cameraState by GrabadoraHdCameraClient.state.collectAsStateWithLifecycle()
    val localVision by LocalVisionObjectDetector.state.collectAsStateWithLifecycle()
    val localLanes by LocalVisionLaneDetector.state.collectAsStateWithLifecycle()
    val osmRoad by OsmRoadContextProvider.state.collectAsStateWithLifecycle()
    val lanePositionTracker = remember { RoadLanePositionTracker() }
    var activeLaneFromRight by remember { mutableStateOf(0) }
    val forwardCameraSelected = isForwardCameraPosition(cameraState.selectedPosition)
    LaunchedEffect(blinkers) {
        lanePositionTracker.noteIndicators(
            left = blinkers.first,
            right = blinkers.second,
        )
    }
    LaunchedEffect(localLanes.stableLane, osmRoad.road?.lanesInTravelDirection, forwardCameraSelected) {
        val nextLane = if (forwardCameraSelected) {
            lanePositionTracker.update(localLanes.stableLane, osmRoad.road?.lanesInTravelDirection)
        } else {
            lanePositionTracker.reset()
            0
        }
        if (nextLane != activeLaneFromRight) {
            TelemetryLog.event(
                "LANE_POSITION",
                "fromRight=$nextLane lanes=${osmRoad.road?.lanesInTravelDirection ?: 1}",
            )
        }
        activeLaneFromRight = nextLane
        vehicle3dActiveLaneFromRight.value = nextLane
    }
    val offscreenRequested = vehicleSceneMode == VehicleSceneMode.THREE_D
    val offscreenState by Vehicle3dOffscreenEngine.state.collectAsStateWithLifecycle()
    // The OpenGL vehicle replaces the road scene both parked and moving (owner's choice): it shows
    // wheels, steering, doors and lights live. The 2D scene remains the fallback.
    if (offscreenRequested && offscreenState.bitmap != null) {
        Vehicle3dDashboardView(
            bitmap = offscreenState.bitmap!!.asImageBitmap(),
            layout = layout,
            darkTheme = colors.dark,
        )
    } else if (offscreenRequested && offscreenState.status != "fallback") {
        // While the GLB loads (about a second at boot) keep the scene empty: the 2D artwork in
        // its own colour would flash before the 3D car in the configured paint.
        Box(
            Modifier
                .offset(y = layout.dp(RoadSceneTop))
                .size(layout.dp(VehicleSceneWidth), layout.dp(RoadSceneHeight))
                .background(if (colors.dark) Color.Black else Color(0xFFF3F4F6)),
        )
    } else {
        VehicleView(
            state,
            adasState,
            if (forwardCameraSelected && LocalVisionPipeline.objectsEnabled) {
                localVision.trackedObjects.filterNot { it.kind == RoadObjectKind.TRAFFIC_LIGHT }
            } else {
                emptyList()
            },
            if (forwardCameraSelected) localLanes.stableLane else null,
            osmRoad.road,
            activeLaneFromRight,
            steeringAngle,
            layout,
            colors.dark,
            colors.background,
            vehicleColorMode,
            drivingZoomMode,
        )
    }
    SteeringWheelIndicator(
        steeringAngleDegrees = steeringAngle,
        layout = layout,
        foreground = colors.primary,
    )
}

/** A confirmed traffic light always occupies the same dashboard position, independent of zoom. */
@Composable
internal fun RuntimeTrafficLightIndicator(layout: LauncherLayout) {
    if (!LocalVisionPipeline.objectsEnabled) return
    val cameraState by GrabadoraHdCameraClient.state.collectAsStateWithLifecycle()
    val vision by LocalVisionObjectDetector.state.collectAsStateWithLifecycle()
    val launcherState by LauncherRuntime.state.collectAsStateWithLifecycle()
    val vehicleMoving = launcherState.speedKmh > 0
    val forwardCameraSelected = isForwardCameraPosition(cameraState.selectedPosition)
    val trackedLight = if (forwardCameraSelected) {
        vision.trackedObjects
            .asSequence()
            .filter {
                it.kind == RoadObjectKind.TRAFFIC_LIGHT &&
                    it.trafficLightColor != TrafficLightColor.UNKNOWN
            }
            .maxByOrNull { it.confidence * it.trafficLightColorConfidence }
    } else {
        null
    }
    val directLight = if (forwardCameraSelected) {
        vision.detections
            .asSequence()
            .filter {
                it.kind == RoadObjectKind.TRAFFIC_LIGHT &&
                    it.score >= TrafficLightDirectMinScore &&
                    it.trafficLightColor != TrafficLightColor.UNKNOWN &&
                    it.trafficLightColorConfidence >= TrafficLightDirectMinColorConfidence
            }
            .maxByOrNull { it.score * it.trafficLightColorConfidence }
    } else {
        null
    }
    val observedColor = trackedLight?.trafficLightColor ?: directLight?.trafficLightColor
    var displayedColor by remember { mutableStateOf<TrafficLightColor?>(null) }
    var displayRevision by remember { mutableStateOf(0L) }
    LaunchedEffect(observedColor, vision.lastResultElapsedRealtime, forwardCameraSelected) {
        if (!forwardCameraSelected) {
            displayedColor = null
        } else if (observedColor != null) {
            displayedColor = observedColor
            displayRevision += 1L
        }
    }
    LaunchedEffect(displayRevision, vehicleMoving) {
        // A light seen while stopped remains visible indefinitely. Starting to move (or seeing
        // it again while moving) starts a fresh six-second grace period.
        if (displayRevision == 0L || !vehicleMoving || displayedColor == null) {
            return@LaunchedEffect
        }
        val revision = displayRevision
        delay(TrafficLightDisplayHoldMs)
        if (displayRevision == revision && vehicleMoving) displayedColor = null
    }
    val lightColor = displayedColor ?: return

    Canvas(
        modifier = Modifier
            // VehiclePanel is only 308 design units wide. The former x=331 placed the whole
            // indicator outside its clipped bounds even though detections reached this composable.
            .offset(layout.dp(TrafficLightIndicatorLeft), layout.dp(169))
            .size(layout.dp(19), layout.dp(46))
            .graphicsLayer { alpha = 0.58f },
    ) {
        val housingWidth = size.width
        val housingHeight = size.height
        drawRoundRect(
            color = Color(0xFF4E5967).copy(alpha = 0.78f),
            topLeft = Offset.Zero,
            size = Size(housingWidth, housingHeight),
            cornerRadius = CornerRadius(housingWidth * 0.16f),
        )
        val lights = listOf(TrafficLightColor.RED, TrafficLightColor.AMBER, TrafficLightColor.GREEN)
        lights.forEachIndexed { index, color ->
            val active = lightColor == color
            val lampColor = when (color) {
                TrafficLightColor.RED -> Color(0xFFFF2828)
                TrafficLightColor.AMBER -> Color(0xFFFFB21A)
                TrafficLightColor.GREEN -> Color(0xFF26DF63)
                TrafficLightColor.UNKNOWN -> Color.Transparent
            }
            val center = Offset(size.width / 2f, size.height * (0.19f + index * 0.31f))
            if (active) drawCircle(lampColor.copy(alpha = 0.28f), size.width * 0.39f, center)
            drawCircle(
                color = if (active) lampColor else Color(0xFF222A31).copy(alpha = 0.78f),
                radius = size.width * 0.28f,
                center = center,
            )
            drawCircle(
                color = if (active) Color.White.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.05f),
                radius = size.width * 0.24f,
                center = center.copy(y = center.y - size.width * 0.025f),
                style = Stroke(width = size.width * 0.035f),
            )
        }
    }
}

/** QF reports -1 transiently while renegotiating the same front camera; only 1 means rear. */
internal fun isForwardCameraPosition(position: Int?): Boolean = position != 1

@Composable
private fun VehicleView(
    state: VehicleVisualUiState,
    adasState: AdasSceneState,
    localRoadObjects: List<TrackedRoadObject>,
    localLane: RoadLaneGeometry?,
    osmRoad: OsmRoadContext?,
    activeLaneFromRight: Int,
    steeringAngleDegrees: Float?,
    layout: LauncherLayout,
    darkTheme: Boolean,
    background: Color,
    vehicleColorMode: DashboardVehicleColorMode,
    drivingZoomMode: DashboardDrivingZoomMode,
) {
    val artworkResources = remember(vehicleColorMode, darkTheme) {
        vehicleArtworkResources(vehicleColorMode, darkTheme)
    }
    val bodyImage = ImageBitmap.imageResource(artworkResources.body)
    val leftDoorClosed = ImageBitmap.imageResource(artworkResources.leftDoorClosed)
    val rightDoorClosed = ImageBitmap.imageResource(artworkResources.rightDoorClosed)
    val leftDoorOpenImage = ImageBitmap.imageResource(artworkResources.leftDoorOpen)
    val rightDoorOpenImage = ImageBitmap.imageResource(artworkResources.rightDoorOpen)
    val frontLightsImage = ImageBitmap.imageResource(R.drawable.vehicle_front_lights)
    val rearLightsImage = ImageBitmap.imageResource(R.drawable.vehicle_rear_lights)
    var movingVisual by remember { mutableStateOf(false) }
    var automaticDrivingScale by remember(drivingZoomMode) {
        mutableStateOf(DashboardDrivingZoomMode.STANDARD_1X.sceneScale)
    }
    LaunchedEffect(state.speedKmh, drivingZoomMode) {
        if (state.speedKmh > 0) {
            movingVisual = true
            if (drivingZoomMode == DashboardDrivingZoomMode.AUTOMATIC_1X_2X) {
                automaticDrivingScale = drivingZoomMode.sceneScaleForSpeed(state.speedKmh)
            }
        } else {
            delay(VehicleStoppedZoomDelayMs)
            if (state.speedKmh == 0) movingVisual = false
        }
    }
    val stoppedProgress by animateFloatAsState(
        targetValue = if (movingVisual) 0f else 1f,
        animationSpec = tween(VehicleSceneAnimationDurationMs, easing = FastOutSlowInEasing),
        label = "Vehicle stopped zoom progress",
    )
    val drivingScale by animateFloatAsState(
        targetValue = drivingZoomMode.sceneScaleForMotion(
            speedKmh = state.speedKmh,
            lastMovingScale = automaticDrivingScale,
        ),
        animationSpec = tween(VehicleSceneAnimationDurationMs, easing = FastOutSlowInEasing),
        label = "Vehicle driving zoom scale",
    )
    val sceneScale = drivingScale + (VehicleStoppedArtworkScale - drivingScale) * stoppedProgress
    val adasScale = drivingScale + (1f - drivingScale) * stoppedProgress
    val roadMarkingDurationMs = if (state.speedKmh > 0) {
        (36_000 / state.speedKmh.coerceIn(1, 140)).coerceIn(260, 2_400)
    } else {
        2_400
    }
    // Parado u oculto, las marcas no se mueven: sin transicion infinita el Canvas no se redibuja cada frame.
    val roadMotionState = if (state.speedKmh > 0 && LocalDashboardVisible.current) {
        val roadMotion = rememberInfiniteTransition(label = "road markings")
        roadMotion.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(roadMarkingDurationMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "road marking phase",
        )
    } else {
        null
    }
    val cameraCurve = roadCurveFromLane(localLane)
    val osmCurve = osmRoad?.curvature ?: 0f
    val steeringCurve by animateFloatAsState(
        targetValue = roadCurveFromSteering(
            steeringAngleDegrees = steeringAngleDegrees,
            speedKmh = state.speedKmh,
        ),
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "steering road curve",
    )
    val roadCurve = resolveRenderedRoadCurve(cameraCurve, osmCurve, steeringCurve)
    Canvas(
        modifier = Modifier.offset(y = layout.dp(RoadSceneTop))
            .size(layout.dp(VehicleSceneWidth), layout.dp(RoadSceneHeight)),
    ) {
        val vehicleSceneOffsetY = layout.dp(VehicleSceneTop - RoadSceneTop).toPx()
        val roadScale = drivingScale + (VehicleStoppedRoadScale - drivingScale) * stoppedProgress
        val roadCenterOffsetX = size.width * VehicleRoadCenterOffsetFraction
        clipRect {
            drawRoadAtmosphere(darkTheme, background)
            drawRoadDepth(darkTheme, roadScale, roadCenterOffsetX, roadCurve)
            drawOsmLaneLayout(
                road = osmRoad,
                darkTheme = darkTheme,
                roadScale = roadScale,
                roadCenterOffsetX = roadCenterOffsetX,
                activeLaneFromRight = activeLaneFromRight,
                roadCurve = roadCurve,
                speedKmh = state.speedKmh,
                motionProgress = roadMotionState?.value ?: 0f,
            )
            // Keep the approach gradients but clear the climate and media control area.
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to background,
                        .22f to Color.Transparent,
                        .68f to Color.Transparent,
                        1f to background,
                    ),
                ),
            )
        }
        val artworkWidth = size.width * (VehicleArtworkWidth / VehicleSceneWidth) * sceneScale
        val artworkScale = artworkWidth / VehicleArtworkCanvasWidth
        val artworkHeight = VehicleArtworkCanvasHeight * artworkScale
        val artworkLeft = (size.width - artworkWidth) / 2f +
            (VehicleArtworkCenterCorrectionX + VehicleArtworkRightNudgeX) * artworkScale
        val movingArtworkTop = vehicleSceneOffsetY + VehicleSceneHeight / RoadSceneHeight * size.height -
            artworkHeight - layout.dp(VehicleArtworkBottomMargin).toPx()
        val stoppedArtworkTop = vehicleSceneOffsetY +
            (VehicleSceneHeight / RoadSceneHeight * size.height - artworkHeight) / 2f +
            layout.dp(VehicleStoppedArtworkLowering).toPx()
        val artworkTop = movingArtworkTop + (stoppedArtworkTop - movingArtworkTop) * stoppedProgress

        fun drawVehicleLayer(image: ImageBitmap, offsetX: Float, offsetY: Float) {
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(
                    (artworkLeft + offsetX * artworkScale).roundToInt(),
                    (artworkTop + offsetY * artworkScale).roundToInt(),
                ),
                dstSize = IntSize(
                    (image.width * artworkScale).roundToInt(),
                    (image.height * artworkScale).roundToInt(),
                ),
            )
        }

        clipRect {
            withTransform({
                scale(scaleX = adasScale, scaleY = adasScale, pivot = center)
            }) {
                drawAdasScene(
                    state = adasState.withWarningVehicleFallback(),
                    darkTheme = darkTheme,
                    showDirectionGuide = state.speedKmh > 0,
                    roadCurve = roadCurve,
                )
                drawLocalVisionScene(
                    objects = localRoadObjects,
                    lane = localLane,
                    osmRoad = osmRoad,
                    roadScale = roadScale,
                    roadCenterOffsetX = roadCenterOffsetX,
                    roadCurve = roadCurve,
                    darkTheme = darkTheme,
                    maximumObjectBottomY = artworkTop - size.width * 0.012f,
                )
            }
        }
        drawVehicleShadow(
            artworkLeft = artworkLeft,
            artworkTop = artworkTop,
            artworkScale = artworkScale,
            stoppedProgress = stoppedProgress,
            darkTheme = darkTheme,
        )
        if (darkTheme) drawVehicleLayer(frontLightsImage, VehicleFrontLightsOffsetX, VehicleFrontLightsOffsetY)
        drawVehicleLayer(bodyImage, VehicleBodyOffsetX, 0f)
        if (darkTheme) drawVehicleLayer(rearLightsImage, VehicleRearLightsOffsetX, VehicleRearLightsOffsetY)
        drawVehicleLayer(
            if (state.frontLeftDoorOpen) leftDoorOpenImage else leftDoorClosed,
            VehicleLeftDoorOffsetX,
            VehicleLeftDoorOffsetY,
        )
        drawVehicleLayer(
            if (state.frontRightDoorOpen) rightDoorOpenImage else rightDoorClosed,
            VehicleRightDoorOffsetX,
            VehicleRightDoorOffsetY,
        )
    }
    if (adasState.warningActive && adasState.lastWarning != AdasWarning.NONE) {
        val warningColor = when (adasState.lastWarning) {
            AdasWarning.FCW -> Color(0xFFE23D46)
            AdasWarning.FVSA -> Color(0xFF268C55)
            AdasWarning.LDW,
            AdasWarning.LDW_LEFT,
            AdasWarning.LDW_RIGHT,
            AdasWarning.VB,
            -> Color(0xFFF0A020)

            AdasWarning.NONE -> Color.Transparent
        }
        Box(
            modifier = Modifier
                .offset(x = layout.dp(39), y = layout.dp(RoadSceneTop + 8f))
                .size(width = layout.dp(230), height = layout.dp(25))
                .background(warningColor.copy(alpha = 0.92f), RoundedCornerShape(layout.dp(13))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(adasState.lastWarning.text),
                color = Color.White,
                fontSize = layout.sp(10),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
    DoorStatusOverlay(state, layout, sceneScale, stoppedProgress)
}

/** Steering wheel icon rotated with the decoded HS-CAN steering angle. */
@Composable
private fun SteeringWheelIndicator(
    steeringAngleDegrees: Float?,
    layout: LauncherLayout,
    foreground: Color,
) {
    var lastKnownAngle by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(steeringAngleDegrees) {
        if (steeringAngleDegrees != null) lastKnownAngle = steeringAngleDegrees
    }
    val displayedAngle by animateFloatAsState(
        // The decoded Astra angle is left-positive; Canvas rotation is clockwise-positive.
        targetValue = -(lastKnownAngle ?: 0f),
        animationSpec = tween(90, easing = LinearEasing),
        label = "HS-CAN steering wheel",
    )
    Image(
        painter = painterResource(R.drawable.icon_steering_wheel),
        contentDescription = stringResource(R.string.road_steering_angle),
        colorFilter = ColorFilter.tint(
            if (lastKnownAngle == null) foreground.copy(alpha = 0.28f) else foreground,
        ),
        modifier = Modifier
            .offset(x = layout.dp(24), y = layout.dp(299))
            .size(layout.dp(29))
            .graphicsLayer { rotationZ = displayedAngle },
    )
}

internal fun DrawScope.drawGhostVehicle(
    vehicle: AdasDetectedVehicle,
    darkTheme: Boolean,
    roadCurve: Float,
) {
    val proximity = (1f - vehicle.distanceMeters / 90f).coerceIn(0.08f, 1f)
    val centerY = size.height * (0.18f + proximity * 0.54f)
    val centerX = size.width * (
        0.5f + vehicle.horizontalPosition.coerceIn(-1.35f, 1.35f) * 0.27f +
            roadCurveOffsetFraction(roadCurve, centerY / size.height)
        )
    val bodyWidth = size.width * (0.045f + proximity * 0.075f)
    val bodyHeight = bodyWidth * 1.75f
    val danger = vehicle.confidenceState >= 3 ||
        (vehicle.distanceMeters < 12f && kotlin.math.abs(vehicle.horizontalPosition) < 0.55f)
    val outline = if (danger) {
        Color(0xFFFF4C55)
    } else if (darkTheme) {
        Color(0xFFC8D2DC)
    } else {
        Color(0xFF52606D)
    }
    drawDetectedRoadVehicle(
        center = Offset(centerX, centerY),
        width = bodyWidth,
        height = bodyHeight,
        color = outline,
        alpha = if (danger) 0.98f else 0.88f,
        kind = RoadObjectKind.CAR,
        darkTheme = darkTheme,
    )
}

private data class VehicleArtworkResources(
    val body: Int,
    val leftDoorClosed: Int,
    val rightDoorClosed: Int,
    val leftDoorOpen: Int,
    val rightDoorOpen: Int,
)

private fun vehicleArtworkResources(
    mode: DashboardVehicleColorMode,
    darkTheme: Boolean,
): VehicleArtworkResources = when (mode) {
    DashboardVehicleColorMode.WHITE -> WhiteVehicleArtwork
    DashboardVehicleColorMode.RED -> RedVehicleArtwork
    DashboardVehicleColorMode.BLACK -> BlackVehicleArtwork
    DashboardVehicleColorMode.THEME -> if (darkTheme) BlackVehicleArtwork else WhiteVehicleArtwork
}

private val WhiteVehicleArtwork = VehicleArtworkResources(
    body = R.drawable.vehicle_white_body,
    leftDoorClosed = R.drawable.vehicle_white_left_door_closed,
    rightDoorClosed = R.drawable.vehicle_white_right_door_closed,
    leftDoorOpen = R.drawable.vehicle_white_left_door_open,
    rightDoorOpen = R.drawable.vehicle_white_right_door_open,
)

private val RedVehicleArtwork = VehicleArtworkResources(
    body = R.drawable.vehicle_red_body,
    leftDoorClosed = R.drawable.vehicle_red_left_door_closed,
    rightDoorClosed = R.drawable.vehicle_red_right_door_closed,
    leftDoorOpen = R.drawable.vehicle_red_left_door_open,
    rightDoorOpen = R.drawable.vehicle_red_right_door_open,
)

private val BlackVehicleArtwork = VehicleArtworkResources(
    body = R.drawable.vehicle_black_body,
    leftDoorClosed = R.drawable.vehicle_black_left_door_closed,
    rightDoorClosed = R.drawable.vehicle_black_right_door_closed,
    leftDoorOpen = R.drawable.vehicle_black_left_door_open,
    rightDoorOpen = R.drawable.vehicle_black_right_door_open,
)

@Composable
private fun DoorStatusOverlay(
    state: VehicleVisualUiState,
    layout: LauncherLayout,
    sceneScale: Float,
    stoppedProgress: Float,
) {
    Canvas(
        modifier = Modifier.offset(y = layout.dp(VehicleSceneTop))
            .size(layout.dp(VehicleSceneWidth), layout.dp(VehicleSceneHeight)),
    ) {
        fun marker(startX: Float, startY: Float, endX: Float, endY: Float) {
            fun scenePoint(x: Float, y: Float): Offset {
                val centeredX = .5f + (x - .5f) * sceneScale
                val centeredY = .5f + (y - .5f) * sceneScale
                // The vehicle is additionally centered vertically while stopped.
                val verticalOffset = -0.31f * stoppedProgress
                return Offset(size.width * centeredX, size.height * (centeredY + verticalOffset))
            }
            drawLine(
                Color(0xFFE23A32),
                scenePoint(startX, startY),
                scenePoint(endX, endY),
                layout.dp(4).toPx(),
                StrokeCap.Round,
            )
        }
        if (state.rearLeftDoorOpen) marker(.28f, .59f, .17f, .69f)
        if (state.rearRightDoorOpen) marker(.72f, .59f, .83f, .69f)
        if (state.trunkOpen) marker(.38f, .78f, .62f, .78f)
        if (state.bonnetOpen) marker(.40f, .30f, .60f, .30f)
    }
}

private fun DrawScope.drawVehicleShadow(
    artworkLeft: Float,
    artworkTop: Float,
    artworkScale: Float,
    stoppedProgress: Float,
    darkTheme: Boolean,
) {
    val bodyWidth = VehicleBodyImageWidth * artworkScale
    val bodyHeight = VehicleBodyImageHeight * artworkScale
    val bodyLeft = artworkLeft + VehicleBodyOffsetX * artworkScale
    val shadowWidth = bodyWidth * (VehicleGroundShadowWidthFactor + stoppedProgress * 0.04f)
    val shadowHeight = bodyHeight * (VehicleGroundShadowHeightFactor + stoppedProgress * 0.01f)
    val shadowLeft = bodyLeft + (bodyWidth - shadowWidth) / 2f
    val shadowTop = artworkTop + bodyHeight * VehicleGroundShadowOffsetY
    val shadowCenter = Offset(shadowLeft + shadowWidth / 2f, shadowTop + shadowHeight / 2f)
    if (darkTheme) {
        drawOval(
            brush = Brush.radialGradient(
                0f to Color(0xFFB8C0C9).copy(alpha = 0.16f),
                0.48f to Color(0xFF6F7780).copy(alpha = 0.09f),
                1f to Color.Transparent,
                center = shadowCenter,
                radius = shadowWidth / 2f,
            ),
            topLeft = Offset(shadowLeft, shadowTop),
            size = androidx.compose.ui.geometry.Size(shadowWidth, shadowHeight),
        )
    }
    val contactWidth = shadowWidth * 0.78f
    val contactHeight = shadowHeight * 0.52f
    drawOval(
        brush = Brush.radialGradient(
            0f to Color.Black.copy(alpha = if (darkTheme) 0.72f else 0.48f),
            0.62f to Color.Black.copy(alpha = if (darkTheme) 0.36f else 0.24f),
            1f to Color.Transparent,
            center = Offset(shadowCenter.x, shadowCenter.y + shadowHeight * 0.10f),
            radius = contactWidth / 2f,
        ),
        topLeft = Offset(
            shadowCenter.x - contactWidth / 2f,
            shadowCenter.y - contactHeight / 2f + shadowHeight * 0.10f,
        ),
        size = androidx.compose.ui.geometry.Size(contactWidth, contactHeight),
    )
}
