package com.ts10.inspector.dashboard

import com.ts10.inspector.ui.rememberDisplayFormats
import com.ts10.inspector.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.launcher.LauncherRuntime
import com.ts10.inspector.ui.DashboardColors
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.vehicle.VehicleMileageTracker
import com.ts10.inspector.vehicle.VehicleSignalHub
import com.ts10.inspector.vehicle.fuel.FuelRangeEstimator
import com.ts10.inspector.vehicle.obd.calculateConsumptionLitersPer100Km
import com.ts10.inspector.vehicle.tires.DdsCalibrationState
import com.ts10.inspector.vehicle.tires.TireMonitoringRepository
import com.ts10.inspector.vehicle.tires.TireMonitoringState
import com.ts10.inspector.vehicle.tires.TirePosition
import com.ts10.inspector.vehicle3d.Vehicle3dFrame
import com.ts10.inspector.vehicle3d.Vehicle3dOffscreenEngine
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
internal fun TripCidPage(layout: LauncherLayout, colors: DashboardColors) {
    val formats = rememberDisplayFormats()
    val mileage by VehicleMileageTracker.state.collectAsStateWithLifecycle()
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(mileage.currentTripActive, mileage.currentTripStartedElapsedMs) {
        while (mileage.currentTripActive) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(1_000L)
        }
    }
    val tripEnd = if (mileage.currentTripActive) now
    else mileage.currentTripStoppedElapsedMs ?: now
    val elapsedMs = (tripEnd - mileage.currentTripStartedElapsedMs).coerceAtLeast(0L)
    CidPageFrame(stringResource(R.string.cid_trip), layout, colors) {
        CidPrimaryMetric(
            mileage.vehicleOdometerKm?.let { String.format(java.util.Locale.getDefault(), "%.0f", formats.distance(it)) } ?: "--",
            stringResource(R.string.cid_odometer),
            layout,
            colors,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            CidSecondaryMetric(
                formats.distanceText(mileage.currentTripKm),
                stringResource(R.string.cid_this_trip),
                layout,
                colors,
            )
            CidSecondaryMetric(formatTripElapsed(elapsedMs), stringResource(R.string.cid_time), layout, colors)
        }
    }
}

@Composable
internal fun EnergyCidPage(layout: LauncherLayout, colors: DashboardColors) {
    val fuel by FuelRangeEstimator.state.collectAsStateWithLifecycle()
    val signals by VehicleSignalHub.state.collectAsStateWithLifecycle()
    val launcher by LauncherRuntime.state.collectAsStateWithLifecycle()
    val formats = rememberDisplayFormats()
    val fuelRate = signals.fuelRateLitersPerHour?.value
    val instantConsumption = calculateConsumptionLitersPer100Km(fuelRate, launcher.speedKmh)
    val consumptionValue = instantConsumption?.let {
        String.format(java.util.Locale.getDefault(), "%.1f", formats.consumption(it.toFloat()))
    } ?: fuelRate?.let {
        String.format(java.util.Locale.getDefault(), "%.2f", it)
    } ?: "--"
    val consumptionUnit = if (instantConsumption != null) formats.unitText(stringResource(R.string.cid_consumption_instant)) else stringResource(R.string.cid_consumption_idle)
    CidPageFrame(stringResource(R.string.cid_range_engine), layout, colors) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            CidSecondaryMetric(fuel.rangeKm?.let { formats.distanceText(it, 0) } ?: "--", stringResource(R.string.cid_range), layout, colors)
            CidSecondaryMetric(
                consumptionValue,
                consumptionUnit,
                layout,
                colors,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            CidSecondaryMetric(
                fuel.fuelLiters?.let { String.format(java.util.Locale.getDefault(), "%.1f L", it) } ?: "--",
                stringResource(R.string.cid_fuel),
                layout,
                colors,
            )
            CidSecondaryMetric(
                signals.coolantCelsius?.let { formats.temperatureText(it.value) } ?: "--",
                stringResource(R.string.cid_coolant),
                layout,
                colors,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            CidSecondaryMetric(formats.temperatureLabel(launcher.outdoorTemperature), stringResource(R.string.cid_outside), layout, colors)
            CidSecondaryMetric(
                signals.batteryVoltage?.let { String.format(java.util.Locale.getDefault(), "%.1f V", it.value) } ?: "--",
                stringResource(R.string.cid_battery),
                layout,
                colors,
            )
        }
    }
}

@Composable
private fun CidPageFrame(
    title: String?,
    layout: LauncherLayout,
    colors: DashboardColors,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Sin card: los datos van directamente sobre el fondo, dentro del hueco del carrusel.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = layout.dp(19), vertical = layout.dp(4)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        title?.let {
            Text(
                it,
                color = colors.muted,
                fontSize = layout.sp(10),
                fontWeight = FontWeight.Bold,
            )
        }
        content()
    }
}

@Composable
internal fun TireCidPage(layout: LauncherLayout, colors: DashboardColors, show3d: Boolean) {
    val tires by TireMonitoringRepository.state.collectAsStateWithLifecycle()
    val offscreenState by Vehicle3dOffscreenEngine.state.collectAsStateWithLifecycle()
    val aerialBitmap = offscreenState.bitmap?.takeIf { show3d }
    // Título arriba como en las demás páginas: el carrusel ya no pasa por debajo de los botones.
    CidPageFrame(stringResource(R.string.tire_pressure_title), layout, colors) {
        Box(Modifier.size(layout.dp(280), layout.dp(150))) {
            // Aerial OpenGL view (nose up) like Tesla's tyre screen; the 2D diagram is the fallback.
            if (aerialBitmap != null) Image(
                // Solo la parte inferior: la franja de arriba es para la carretera al conducir.
                painter = remember(aerialBitmap) {
                    val image = aerialBitmap.asImageBitmap()
                    val rows = image.height * Vehicle3dFrame.PARKED_HEIGHT / Vehicle3dFrame.HEIGHT
                    BitmapPainter(image, IntOffset(0, image.height - rows), IntSize(image.width, rows))
                },
                contentDescription = stringResource(R.string.cid_vehicle_top_view),
                contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.Center).fillMaxSize(),
            ) else Canvas(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(layout.dp(260), layout.dp(145)),
            ) {
                withTransform({ scale(scaleX = 1.42f, scaleY = 1.18f, pivot = Offset(135f, 61f)) }) {
                    drawTireVehicleDiagram(
                        tireColors = TirePosition.entries.associateWith { tireVisualColor(it, tires) },
                        engineColor = Color(0xFF279BE8),
                        fuelColor = Color(0xFF279BE8),
                        fuelFraction = null,
                        serviceBrakePressed = false,
                        handbrakeEngaged = false,
                        outlineColor = colors.primary,
                    )
                }
            }
            TirePressureLabel(TirePosition.FRONT_LEFT, stringResource(R.string.tire_front_left), tires, 0f, 10f, layout)
            TirePressureLabel(TirePosition.FRONT_RIGHT, stringResource(R.string.tire_front_right), tires, 202f, 10f, layout)
            TirePressureLabel(TirePosition.REAR_LEFT, stringResource(R.string.tire_rear_left), tires, 0f, 88f, layout)
            TirePressureLabel(TirePosition.REAR_RIGHT, stringResource(R.string.tire_rear_right), tires, 202f, 88f, layout)
        }
    }
}

@Composable
private fun TirePressureLabel(
    position: TirePosition,
    shortLabel: String,
    state: TireMonitoringState,
    x: Float,
    y: Float,
    layout: LauncherLayout,
) {
    val reading = state.readings[position]
    val deviation = state.ddsDeviationPercent[position]
    val value = when {
        reading?.pressureBar != null ->
            String.format(java.util.Locale.getDefault(), "%.1f bar", reading.pressureBar)
        deviation != null -> stringResource(R.string.tire_dds_estimate, deviation)
        state.calibrationState == DdsCalibrationState.CALIBRATING -> "${state.calibrationProgress}%"
        // "SIN DATOS" no cabía en una línea junto a la rueda; "--" como en las demás páginas.
        else -> "--"
    }
    // Only the value: its position next to the wheel already says which tyre it is.
    Box(
        modifier = Modifier
            .offset(layout.dp(x), layout.dp(y))
            .size(layout.dp(78), layout.dp(52))
            .semantics { contentDescription = "$shortLabel $value" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            value,
            color = tireVisualColor(position, state),
            fontSize = layout.sp(15),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

private fun tireVisualColor(position: TirePosition, state: TireMonitoringState): Color {
    val reading = state.readings[position]
    val deviation = state.ddsDeviationPercent[position]
    return when {
        position in state.ddsAlerts -> Color(0xFFE33B43)
        deviation != null && kotlin.math.abs(deviation) >= 1.5f -> Color(0xFFF0A126)
        reading?.pressureBar != null && reading.pressureBar < 1.7f -> Color(0xFFE33B43)
        reading?.pressureBar != null && reading.pressureBar < 1.95f -> Color(0xFFF0A126)
        reading?.pressureBar != null ||
            (deviation != null && state.calibrationState == DdsCalibrationState.READY) -> Color(0xFF31C967)
        else -> Color(0xFF279BE8)
    }
}

internal fun engineVisualColor(temperatureCelsius: Int?): Color = when {
    temperatureCelsius == null || temperatureCelsius < 70 -> Color(0xFF279BE8)
    temperatureCelsius <= 105 -> Color(0xFF31C967)
    temperatureCelsius <= 109 -> Color(0xFFF0A126)
    else -> Color(0xFFE33B43)
}

internal fun fuelVisualColor(fuelPercent: Int?): Color = when {
    fuelPercent == null -> Color(0xFF279BE8)
    fuelPercent <= 10 -> Color(0xFFE33B43)
    fuelPercent <= 25 -> Color(0xFFF0A126)
    else -> Color(0xFF31C967)
}

@Composable
private fun CidPrimaryMetric(
    value: String,
    label: String,
    layout: LauncherLayout,
    colors: DashboardColors,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = colors.primary, fontSize = layout.sp(27), lineHeight = layout.sp(29), fontWeight = FontWeight.Light)
        Text(label, color = colors.muted, fontSize = layout.sp(8), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CidSecondaryMetric(
    value: String,
    label: String,
    layout: LauncherLayout,
    colors: DashboardColors,
) {
    Column(
        modifier = Modifier.size(layout.dp(126), layout.dp(43)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(value, color = colors.primary, fontSize = layout.sp(18), lineHeight = layout.sp(19), fontWeight = FontWeight.Medium, maxLines = 1)
        Text(label, color = colors.muted, fontSize = layout.sp(7), fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

private fun formatTripElapsed(elapsedMs: Long): String {
    val totalMinutes = elapsedMs / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "%d:%02d h".format(hours, minutes) else "$minutes min"
}
