package com.ts10.inspector.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.R
import com.ts10.inspector.launcher.LauncherRuntime
import com.ts10.inspector.launcher.SpeedUiState
import com.ts10.inspector.ui.DashboardColors
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.vehicle.F1RpmBand
import com.ts10.inspector.vehicle.IDLE_RPM
import com.ts10.inspector.vehicle.TACHOMETER_MAX_RPM
import com.ts10.inspector.vehicle.VehicleSignalHub
import com.ts10.inspector.vehicle.estimateManualGear
import com.ts10.inspector.vehicle.f1RpmBand
import com.ts10.inspector.vehicle.f1RpmFill
import com.ts10.inspector.vehicle.shouldRecommendEconomicUpshift
import com.ts10.inspector.vehicle3d.VEHICLE_3D_DRIVING_KMH

@Composable
internal fun RuntimeSpeed(
    layout: LauncherLayout,
    foreground: Color,
    showSpeedLimit: Boolean,
    /** The parked 3D camera turns the car side-on; the road sign then has no road to stand on. */
    hideSpeedLimitWhenParked: Boolean = false,
) {
    val state by LauncherRuntime.speedState.collectAsStateWithLifecycle()
    val speedLimit = state.speedLimitKmh
    val speedColor = if (isAboveDetectedSpeedLimit(state.speedKmh, speedLimit)) {
        Color(0xFFD71920)
    } else {
        foreground
    }
    Speed(state, layout, speedColor)
    val parked3d = hideSpeedLimitWhenParked && state.speedKmh < VEHICLE_3D_DRIVING_KMH
    if (showSpeedLimit && speedLimit != null && !parked3d) {
        SpeedLimitSign(speedLimit, layout, showPost = true)
    }
}

internal fun isAboveDetectedSpeedLimit(speedKmh: Int, speedLimitKmh: Int?): Boolean =
    speedLimitKmh != null && speedKmh > speedLimitKmh

@Composable
internal fun EconomicShiftIndicator(layout: LauncherLayout, colors: DashboardColors) {
    val signals by VehicleSignalHub.state.collectAsStateWithLifecycle()
    val engineRpm = signals.engineRpm?.value
    val gear = estimateManualGear(
        engineRpm = engineRpm,
        speedKmh = signals.busSpeedKmh?.value,
        clutchPressed = signals.clutchPressed,
        reverse = signals.reverse,
    )
    val fill = f1RpmFill(engineRpm)
    val recommendShift = shouldRecommendEconomicUpshift(engineRpm, gear)
    val segmentCount = 9

    Box(
        modifier = Modifier
            .offset(layout.dp(83), layout.dp(42))
            .size(layout.dp(15), layout.dp(65)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Canvas(Modifier.size(layout.dp(10), layout.dp(52)).offset(y = layout.dp(7))) {
            val gap = layout.dp(1.2f).toPx()
            val segmentHeight = (size.height - gap * (segmentCount - 1)) / segmentCount
            repeat(segmentCount) { bottomIndex ->
                val top = size.height - (bottomIndex + 1) * segmentHeight - bottomIndex * gap
                val active = fill * segmentCount >= bottomIndex + 0.35f
                val segmentRpm = IDLE_RPM +
                    (bottomIndex + 1f) / segmentCount * (TACHOMETER_MAX_RPM - IDLE_RPM)
                val activeColor = when (f1RpmBand(segmentRpm)) {
                    F1RpmBand.BLUE -> Color(0xFF3182CE)
                    F1RpmBand.GREEN -> Color(0xFF2E9D50)
                    F1RpmBand.AMBER -> Color(0xFFF4A900)
                    F1RpmBand.RED -> Color(0xFFD71920)
                }
                drawRoundRect(
                    color = if (active) activeColor else colors.muted.copy(alpha = 0.17f),
                    topLeft = Offset(0f, top),
                    size = Size(size.width, segmentHeight),
                    cornerRadius = CornerRadius(size.width * .24f),
                )
            }
        }
        Text(
            text = if (recommendShift) "↑" else "ECO",
            color = if (recommendShift) Color(0xFFF4A900) else colors.muted,
            fontSize = layout.sp(if (recommendShift) 10 else 4.5f),
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = when (gear) { -1 -> "R"; null -> "–"; else -> gear.toString() },
            color = colors.primary,
            fontSize = layout.sp(7),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun Speed(state: SpeedUiState, layout: LauncherLayout, foreground: Color) {
    Text(
        text = state.speedKmh.toString(),
        color = foreground,
        fontSize = layout.sp(43),
        lineHeight = layout.sp(43),
        fontWeight = FontWeight.Light,
        textAlign = TextAlign.Center,
        modifier = Modifier.offset(layout.dp(8), layout.dp(45)).size(layout.dp(72), layout.dp(43)),
    )
    Text(
        text = "km/h",
        color = foreground,
        fontSize = layout.sp(14),
        textAlign = TextAlign.Center,
        modifier = Modifier.offset(layout.dp(8), layout.dp(87)).size(layout.dp(72), layout.dp(21)),
    )
}

@Composable
private fun SpeedLimitSign(limit: Int, layout: LauncherLayout, showPost: Boolean) {
    Box(
        modifier = Modifier.offset(layout.dp(18), layout.dp(169)).size(layout.dp(43))
            .background(Color.White, CircleShape).border(layout.dp(4), Color(0xFFD71920), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            limit.toString(),
            color = Color(0xFF171717),
            fontSize = layout.sp(speedLimitFontSizeDesignSp(limit)),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
    if (showPost) {
        Canvas(Modifier.offset(layout.dp(37), layout.dp(211)).size(layout.dp(5), layout.dp(48))) {
            drawLine(Color(0xFF8A8A88), Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), size.width * .55f)
        }
    }
}

internal fun speedLimitFontSizeDesignSp(limit: Int): Int = when {
    limit >= 100 -> 16
    limit >= 10 -> 18
    else -> 16
}
