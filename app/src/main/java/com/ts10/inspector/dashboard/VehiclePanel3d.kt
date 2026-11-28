package com.ts10.inspector.dashboard

import com.ts10.inspector.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.environment.OsmRoadContextProvider
import com.ts10.inspector.environment.RoadLineStyle
import com.ts10.inspector.launcher.LauncherRuntime
import com.ts10.inspector.launcher.LauncherState
import com.ts10.inspector.settings.DashboardDrivingZoomMode
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.vehicle.VehicleSignalHub
import com.ts10.inspector.vehicle.VehicleSignals
import com.ts10.inspector.vehicle.can.SwWindowAction
import com.ts10.inspector.vehicle3d.ParkedViewState
import com.ts10.inspector.vehicle3d.arrangeRoadObjects
import com.ts10.inspector.vehicle3d.Vehicle3dFocus
import com.ts10.inspector.vehicle3d.Vehicle3dOffscreenEngine
import com.ts10.inspector.vehicle3d.Vehicle3dRoadObject
import com.ts10.inspector.vehicle3d.Vehicle3dRoadObjectKind
import com.ts10.inspector.vehicle3d.Vehicle3dSceneInput
import com.ts10.inspector.camera.GrabadoraHdCameraClient
import com.ts10.inspector.vision.LaneChangeEstimator
import com.ts10.inspector.vision.LocalVisionObjectDetector
import com.ts10.inspector.vision.LocalVisionPipeline
import com.ts10.inspector.vision.RoadLaneRelation
import com.ts10.inspector.vision.RoadObjectKind
import com.ts10.inspector.vision.TrackedRoadObject
import com.ts10.inspector.vision.projectTrackedRoadObject
import com.ts10.inspector.vision.resolveRenderedRoadCurve
import com.ts10.inspector.vision.roadCurveFromSteering

@Composable
internal fun Vehicle3dDashboardView(
    bitmap: ImageBitmap,
    layout: LauncherLayout,
    darkTheme: Boolean,
) {
    Box(
        modifier = Modifier
            .offset(y = layout.dp(RoadSceneTop))
            .size(layout.dp(VehicleSceneWidth), layout.dp(VehiclePageHeight))
            .background(if (darkTheme) Color(0xFF050607) else Color(0xFFF4F2EF)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(R.string.vehicle_3d_view),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Feeds the OpenGL vehicle from confirmed launcher and CAN state. Kept in its own composable so the
 * frequent CAN/launcher updates only recompose this tiny scope, not the whole vehicle panel.
 */
@Composable
internal fun Vehicle3dSceneFeeder(
    paintArgb: Int,
    floorArgb: Int?,
    dark: Boolean,
    topView: Boolean,
    focus: VehicleSceneFocus,
    drivingZoomMode: DashboardDrivingZoomMode,
) {
    val launcherState by LauncherRuntime.state.collectAsStateWithLifecycle()
    val signals by VehicleSignalHub.state.collectAsStateWithLifecycle()
    val osmRoad by OsmRoadContextProvider.state.collectAsStateWithLifecycle()
    val activeLane by LaneChangeEstimator.laneFromRight.collectAsStateWithLifecycle()
    val cameraState by GrabadoraHdCameraClient.state.collectAsStateWithLifecycle()
    val localVision by LocalVisionObjectDetector.state.collectAsStateWithLifecycle()
    val forwardCamera = isForwardCameraPosition(cameraState.selectedPosition)
    val road = osmRoad.road
    val parked by ParkedViewState.parked.collectAsStateWithLifecycle()
    val focus3d = when (focus) {
        VehicleSceneFocus.ENGINE -> Vehicle3dFocus.ENGINE
        VehicleSceneFocus.CABIN -> Vehicle3dFocus.CABIN
        VehicleSceneFocus.FUEL -> Vehicle3dFocus.FUEL
        else -> Vehicle3dFocus.NONE
    }.takeIf { parked } ?: Vehicle3dFocus.NONE
    val base = vehicle3dSceneInput(launcherState, signals, paintArgb, dark)
    val input = base.copy(
        drivingView = !parked,
        floorArgb = floorArgb,
        topView = topView,
        focus = focus3d,
        drivingZoom = drivingZoomMode.sceneScaleForSpeed(launcherState.speedKmh),
        // The engine close-up opens the bonnet; the real bonnet state still wins when it is open.
        hoodOpen = base.hoodOpen || focus3d == Vehicle3dFocus.ENGINE,
        roadLanes = road?.lanesInTravelDirection ?: 0,
        activeLaneFromRight = activeLane,
        roadCurve = resolveRenderedRoadCurve(
            cameraCurve = 0f,
            osmCurve = road?.curvature ?: 0f,
            steeringCurve = roadCurveFromSteering(signals.steeringAngleDegrees?.value, launcherState.speedKmh),
        ),
        roadOneway = road?.oneway ?: true,
        roadCenterLine = road?.centerLineStyle ?: RoadLineStyle.SOLID,
        roadLaneLines = road?.laneLineStyles.orEmpty(),
        roadObjects = if (forwardCamera && LocalVisionPipeline.objectsEnabled) {
            arrangeRoadObjects(vehicle3dRoadObjects(localVision.trackedObjects), road?.lanesInTravelDirection ?: 0, activeLane)
        } else {
            emptyList()
        },
    )
    val visible = LocalDashboardVisible.current
    // Con el panel oculto no se renderiza; al mostrarlo se envia el estado actual.
    LaunchedEffect(input, visible) { if (visible) Vehicle3dOffscreenEngine.update(input) }
}

/**
 * Places confirmed tracks on the 3D road. Traffic lights and signs keep their fixed indicators.
 * Distance comes from where the box touches the ground (flat-road pinhole guess, visual only).
 */
internal fun vehicle3dRoadObjects(tracks: List<TrackedRoadObject>): List<Vehicle3dRoadObject> =
    tracks.mapNotNull { track ->
        val kind = when (track.kind) {
            RoadObjectKind.CAR -> Vehicle3dRoadObjectKind.CAR
            RoadObjectKind.BUS, RoadObjectKind.TRUCK -> Vehicle3dRoadObjectKind.LARGE_VEHICLE
            RoadObjectKind.BICYCLE, RoadObjectKind.MOTORCYCLE -> Vehicle3dRoadObjectKind.TWO_WHEELER
            RoadObjectKind.PERSON -> Vehicle3dRoadObjectKind.PERSON
            RoadObjectKind.TRAFFIC_LIGHT, RoadObjectKind.STOP_SIGN -> return@mapNotNull null
        }
        val projection = projectTrackedRoadObject(track)
        val belowHorizon = (projection.proximity * ROAD_GROUND_SPAN).coerceAtLeast(0.001f)
        Vehicle3dRoadObject(
            id = track.id,
            kind = kind,
            lateralLanes = projection.lateral,
            aheadMeters = (CAMERA_GROUND_FACTOR / belowHorizon - CAMERA_TO_BUMPER_METERS)
                .coerceIn(MIN_OBJECT_GAP_METERS, MAX_OBJECT_AHEAD_METERS),
            egoLane = track.laneRelation == RoadLaneRelation.EGO,
        )
    }
        .sortedBy { it.aheadMeters }
        .take(MAX_3D_ROAD_OBJECTS)

/** Image fraction between the horizon (0.40) and the nearest ground row (0.96) of the projection. */
private const val ROAD_GROUND_SPAN = 0.56f
/** Camera height times focal length (image heights) of a wide dashcam, in metres. */
private const val CAMERA_GROUND_FACTOR = 0.75f
private const val CAMERA_TO_BUMPER_METERS = 1.6f
private const val MIN_OBJECT_GAP_METERS = 2.5f
private const val MAX_OBJECT_AHEAD_METERS = 44f
private const val MAX_3D_ROAD_OBJECTS = 6

/** Falso mientras el servicio mantiene el panel oculto tras una app a pantalla completa. */
internal val LocalDashboardVisible = compositionLocalOf { true }

internal fun vehicle3dSceneInput(
    state: LauncherState,
    signals: VehicleSignals,
    paintArgb: Int,
    dark: Boolean,
) = Vehicle3dSceneInput(
    paintArgb = paintArgb,
    dark = dark,
    speedKmh = state.speedKmh.toFloat(),
    steeringWheelDegrees = signals.steeringAngleDegrees?.value ?: 0f,
    // Only the front doors exist on the GTC; rear-door flags are ignored on purpose.
    doorLeftOpen = state.frontLeftDoorOpen,
    doorRightOpen = state.frontRightDoorOpen,
    hoodOpen = state.bonnetOpen,
    tailgateOpen = state.trunkOpen,
    // 0x160 carries no side and no position: the last order moves the driver's window fully.
    windowLeftDown = signals.windowAction == SwWindowAction.OPENING,
    positionLights = state.positionLights,
    dippedBeam = state.dippedBeam,
    highBeam = state.highBeam,
    brake = signals.serviceBrakePressed == true,
    reverse = signals.reverse == true,
    leftIndicator = state.leftBlinker || state.hazard,
    rightIndicator = state.rightBlinker || state.hazard,
    frontFog = signals.frontFogOn == true,
    rearFog = signals.rearFogOn == true,
)
