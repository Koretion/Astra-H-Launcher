package com.ts10.inspector.dashboard

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.R
import com.ts10.inspector.diagnostics.VolumeInteractionDiagnostics
import com.ts10.inspector.launcher.LauncherActions
import com.ts10.inspector.launcher.LauncherRuntime
import com.ts10.inspector.panel.rememberInstalledApplications
import com.ts10.inspector.settings.DashboardPreferences
import com.ts10.inspector.ui.DashboardAssetIcon
import com.ts10.inspector.ui.DashboardColors
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.ui.LocalDashboardColors
import com.ts10.inspector.vehicle3d.VEHICLE_3D_DRIVING_KMH
import com.ts10.inspector.vehicle3d.Vehicle3dOffscreenEngine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun VehiclePanel(
    actions: LauncherActions,
    layout: LauncherLayout,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by DashboardPreferences.get(context).state.collectAsStateWithLifecycle()
    val installedApplications = rememberInstalledApplications()
    val colors = LocalDashboardColors.current
    // Infinite carousel: many virtual pages mapped onto the real ones, starting in the middle, so
    // swiping past the last page lands directly on the first one (and vice versa).
    val vehiclePager = rememberPagerState(
        initialPage = VEHICLE_PAGES * (VIRTUAL_PAGE_LOOPS / 2),
        pageCount = { VEHICLE_PAGES * VIRTUAL_PAGE_LOOPS },
    )
    val vehiclePage = vehiclePager.currentPage % VEHICLE_PAGES
    val requestedFocus by VehicleSceneFocusRequest.focus.collectAsStateWithLifecycle()
    val parked by remember {
        LauncherRuntime.state.map { it.speedKmh < VEHICLE_3D_DRIVING_KMH }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(true)
    val sceneFocus = if (parked) requestedFocus else VehicleSceneFocus.NONE
    // The tyre settings show the tyre carousel page; closing them returns to the previous page.
    var pageBeforeTires by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(sceneFocus == VehicleSceneFocus.TIRES) {
        if (sceneFocus == VehicleSceneFocus.TIRES) {
            if (vehiclePager.currentPage % VEHICLE_PAGES != TIRE_PAGE) {
                pageBeforeTires = vehiclePager.currentPage
                val current = vehiclePager.currentPage
                vehiclePager.animateScrollToPage(current - current % VEHICLE_PAGES + TIRE_PAGE)
            }
        } else {
            pageBeforeTires?.let { vehiclePager.animateScrollToPage(it) }
            pageBeforeTires = null
        }
    }
    LaunchedEffect(Unit) { VolumeInteractionDiagnostics.start(context) }
    // The OpenGL engine lives at panel level, not inside pager page 0: the tyre page reuses it for
    // the aerial view and the pager disposes pages that are off screen.
    val offscreen3d = settings.vehicleSceneMode == VehicleSceneMode.THREE_D
    DisposableEffect(offscreen3d) {
        if (offscreen3d) Vehicle3dOffscreenEngine.start(context)
        onDispose { if (offscreen3d) Vehicle3dOffscreenEngine.stop() }
    }
    if (offscreen3d) {
        Vehicle3dSceneFeeder(
            paintArgb = settings.vehiclePaint.argb ?: colors.primary.toArgb(),
            dark = colors.dark,
            topView = vehiclePage == TIRE_PAGE,
            focus = sceneFocus,
            drivingZoomMode = settings.drivingZoomMode,
        )
    }
    Box(modifier = modifier.background(colors.background)) {
        RuntimeClockAndWeather(layout, colors, actions.onWeather)
        HorizontalPager(
            state = vehiclePager,
            modifier = Modifier
                .offset(y = layout.dp(RoadSceneTop))
                .size(layout.dp(VehicleSceneWidth), layout.dp(343 - RoadSceneTop))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        var active = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            val pressed = event.changes.any { it.pressed }
                            if (pressed != active) {
                                active = pressed
                                VolumeInteractionDiagnostics.carouselGesture(active)
                            }
                        }
                    }
                },
        ) { page ->
            Box(
                Modifier
                    .offset(y = -layout.dp(RoadSceneTop))
                    .size(layout.dp(VehicleSceneWidth), layout.dp(343)),
            ) {
                when (page % VEHICLE_PAGES) {
                    0 -> RuntimeVehicleView(
                        layout,
                        colors,
                        settings.vehicleColorMode,
                        settings.drivingZoomMode,
                        settings.vehicleSceneMode,
                    )
                    1 -> TripCidPage(layout, colors)
                    2 -> EnergyCidPage(layout, colors)
                    else -> TireCidPage(layout, colors, offscreen3d)
                }
            }
        }
        VehiclePagerDots(vehiclePage, layout, colors)
        // The sign belongs to the road scene. On the other CID pages it covered useful content,
        // most noticeably the front-left tire card.
        RuntimeSpeed(
            layout = layout,
            foreground = colors.primary,
            showSpeedLimit = vehiclePage == 0,
            hideSpeedLimitWhenParked = offscreen3d,
        )
        EconomicShiftIndicator(layout, colors)
        RuntimeClimate(layout, colors)
        RuntimeMediaSection(actions, installedApplications, layout, colors)
        QuickActions(actions, layout, colors)
        RuntimeTrafficLightIndicator(layout)
        FuelRangePopup(layout, colors)
        RefuelDetectedPopup(layout, colors)
    }
}

private const val VEHICLE_PAGES = 4
private const val VIRTUAL_PAGE_LOOPS = 1000
private const val TIRE_PAGE = 3

@Composable
private fun VehiclePagerDots(page: Int, layout: LauncherLayout, colors: DashboardColors) {
    Row(
        modifier = Modifier
            .offset(layout.dp(126), layout.dp(331))
            .size(layout.dp(56), layout.dp(7)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(VEHICLE_PAGES) { index ->
            Box(
                Modifier
                    .size(layout.dp(if (page == index) 8 else 6))
                    .background(
                        if (page == index) colors.primary.copy(alpha = 0.78f)
                        else colors.muted.copy(alpha = 0.38f),
                        CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun QuickActions(actions: LauncherActions, layout: LauncherLayout, colors: DashboardColors) {
    ActionButton(R.drawable.icon_phone, stringResource(R.string.action_phone), actions.onPhone, 28, layout, colors)
    ActionButton(R.drawable.icon_voice_assistant, stringResource(R.string.action_voice_assistant), actions.onVoiceAssistant, 91, layout, colors)
    ActionButton(R.drawable.icon_maps, stringResource(R.string.action_maps), actions.onNavigation, 154, layout, colors)
    ActionButton(R.drawable.icon_app_list, stringResource(R.string.action_applications), actions.onApplications, 217, layout, colors)
    ActionButton(
        R.drawable.icon_dashcam,
        stringResource(R.string.action_dashcam),
        actions.onOpenCameraFullScreen,
        280,
        layout,
        colors,
    )
}

@Composable
private fun ActionButton(
    drawable: Int,
    contentDescription: String,
    onClick: () -> Unit,
    centerX: Int,
    layout: LauncherLayout,
    colors: DashboardColors,
) {
    Box(
        modifier = Modifier
            .offset(layout.dp(centerX - 20), layout.dp(113))
            .size(layout.dp(40))
            .background(colors.actionBackground, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        DashboardAssetIcon(
            drawable = drawable,
            contentDescription = contentDescription,
            tint = colors.actionForeground,
            modifier = Modifier.size(layout.dp(25)),
        )
    }
}
