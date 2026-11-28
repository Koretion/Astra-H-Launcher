package com.ts10.inspector.dashboard

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.R
import com.ts10.inspector.diagnostics.VolumeInteractionDiagnostics
import com.ts10.inspector.launcher.LauncherActions
import com.ts10.inspector.panel.rememberInstalledApplications
import com.ts10.inspector.settings.DashboardPreferences
import com.ts10.inspector.ui.DashboardAssetIcon
import com.ts10.inspector.ui.DashboardColors
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.ui.LocalDashboardColors
import com.ts10.inspector.vehicle3d.ParkedViewState
import com.ts10.inspector.vehicle3d.Vehicle3dOffscreenEngine

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
    val parked by ParkedViewState.parked.collectAsStateWithLifecycle()
    val sceneFocus = if (parked) requestedFocus else VehicleSceneFocus.NONE
    // Las vistas de Ajustes que mueven la cámara 3D (motor, interior, combustible) llevan a la
    // primera página, donde está el coche; neumáticos lleva a la suya. Al cerrar Ajustes se vuelve
    // a la página en la que se estaba antes del primer salto.
    var pageBeforeFocus by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(sceneFocus) {
        val targetPage = when (sceneFocus) {
            VehicleSceneFocus.NONE -> null
            VehicleSceneFocus.TIRES -> TIRE_PAGE
            VehicleSceneFocus.ENGINE, VehicleSceneFocus.CABIN, VehicleSceneFocus.FUEL -> VEHICLE_PAGE
        }
        val current = vehiclePager.currentPage
        if (targetPage == null) {
            pageBeforeFocus?.let { vehiclePager.animateScrollToPage(it) }
            pageBeforeFocus = null
        } else if (current % VEHICLE_PAGES != targetPage) {
            if (pageBeforeFocus == null) pageBeforeFocus = current
            vehiclePager.animateScrollToPage(current - current % VEHICLE_PAGES + targetPage)
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
            paintArgb = settings.vehiclePaintArgb(colors.primary.toArgb()),
            floorArgb = settings.floorColorArgb,
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
                .size(layout.dp(VehicleSceneWidth), layout.dp(VehiclePageHeight))
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
                    .size(layout.dp(VehicleSceneWidth), layout.dp(VehiclePageHeight))
                    .clipToBounds(),
            ) {
                when (page % VEHICLE_PAGES) {
                    0 -> CarouselVehicleScene(layout) {
                        RuntimeVehicleView(
                            layout,
                            colors,
                            settings.vehicleColorMode,
                            settings.drivingZoomMode,
                            settings.vehicleSceneMode,
                        )
                    }
                    // Las páginas de datos, en el hueco bajo los botones.
                    else -> Box(
                        Modifier
                            .offset(y = layout.dp(CarouselTop - RoadSceneTop))
                            .size(layout.dp(VehicleSceneWidth), layout.dp(CarouselHeight)),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (page % VEHICLE_PAGES) {
                            1 -> TripCidPage(layout, colors)
                            2 -> EnergyCidPage(layout, colors)
                            else -> TireCidPage(layout, colors, offscreen3d)
                        }
                    }
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

/**
 * La escena del coche (2D o 3D) se dibuja en coordenadas del panel desde [RoadSceneTop], tal cual:
 * parada, la franja bajo los botones queda del color del fondo; al conducir la carretera sube por
 * ella y se difumina hasta la línea de la fecha.
 */
@Composable
private fun CarouselVehicleScene(layout: LauncherLayout, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(SceneFadeVertical, blendMode = BlendMode.DstIn)
                drawRect(EdgeFadeHorizontal, blendMode = BlendMode.DstIn)
            },
    ) {
        // Anclada arriba: si no, Compose centra una caja más alta que su padre y la escena sube.
        Box(
            Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .offset(y = -layout.dp(RoadSceneTop))
                .requiredSize(layout.dp(VehicleSceneWidth), layout.dp(RoadSceneTop + RoadSceneHeight)),
        ) { content() }
    }
}

/** Transparente en la línea de la fecha, opaca al llegar bajo los botones y un borde suave abajo. */
private val SceneFadeVertical = Brush.verticalGradient(
    0f to Color.Transparent,
    (CarouselTop - RoadSceneTop) / VehiclePageHeight to Color.Black,
    0.95f to Color.Black,
    1f to Color.Transparent,
)
private val EdgeFadeHorizontal = Brush.horizontalGradient(
    0f to Color.Transparent,
    0.06f to Color.Black,
    0.94f to Color.Black,
    1f to Color.Transparent,
)

private const val VEHICLE_PAGES = 4
private const val VIRTUAL_PAGE_LOOPS = 1000
private const val TIRE_PAGE = 3
private const val VEHICLE_PAGE = 0

@Composable
private fun VehiclePagerDots(page: Int, layout: LauncherLayout, colors: DashboardColors) {
    Row(
        modifier = Modifier
            .offset(layout.dp(126), layout.dp(PagerDotsTop))
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
