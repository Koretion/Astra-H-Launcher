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
import com.ts10.inspector.vehicle3d.VEHICLE_3D_DRIVING_KMH
import com.ts10.inspector.vehicle3d.Vehicle3dFocus
import com.ts10.inspector.vehicle3d.Vehicle3dOffscreenEngine
import com.ts10.inspector.vehicle3d.Vehicle3dSceneInput
import com.ts10.inspector.vision.resolveRenderedRoadCurve
import com.ts10.inspector.vision.roadCurveFromSteering
import kotlinx.coroutines.flow.MutableStateFlow

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
    dark: Boolean,
    topView: Boolean,
    focus: VehicleSceneFocus,
    drivingZoomMode: DashboardDrivingZoomMode,
) {
    val launcherState by LauncherRuntime.state.collectAsStateWithLifecycle()
    val signals by VehicleSignalHub.state.collectAsStateWithLifecycle()
    val osmRoad by OsmRoadContextProvider.state.collectAsStateWithLifecycle()
    val activeLane by vehicle3dActiveLaneFromRight.collectAsStateWithLifecycle()
    val road = osmRoad.road
    val parked = launcherState.speedKmh < VEHICLE_3D_DRIVING_KMH
    val focus3d = when (focus) {
        VehicleSceneFocus.ENGINE -> Vehicle3dFocus.ENGINE
        VehicleSceneFocus.CABIN -> Vehicle3dFocus.CABIN
        VehicleSceneFocus.FUEL -> Vehicle3dFocus.FUEL
        else -> Vehicle3dFocus.NONE
    }.takeIf { parked } ?: Vehicle3dFocus.NONE
    val base = vehicle3dSceneInput(launcherState, signals, paintArgb, dark)
    val input = base.copy(
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
    )
    val visible = LocalDashboardVisible.current
    // Con el panel oculto no se renderiza; al mostrarlo se envia el estado actual.
    LaunchedEffect(input, visible) { if (visible) Vehicle3dOffscreenEngine.update(input) }
}

/** Falso mientras el servicio mantiene el panel oculto tras una app a pantalla completa. */
internal val LocalDashboardVisible = compositionLocalOf { true }

/** Lane chosen by the road view's tracker, shared with the OpenGL road. */
internal val vehicle3dActiveLaneFromRight = MutableStateFlow(0)

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
