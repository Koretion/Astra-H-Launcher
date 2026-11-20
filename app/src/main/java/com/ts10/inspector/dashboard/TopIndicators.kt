package com.ts10.inspector.dashboard

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.R
import com.ts10.inspector.launcher.LauncherRuntime
import com.ts10.inspector.ui.DashboardAssetIcon
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.ui.LocalDashboardColors
import com.ts10.inspector.vehicle.VehicleSignalHub
import com.ts10.inspector.vehicle.fuel.FuelRangeEstimator

@Composable
internal fun VehicleTopIndicators(
    layout: LauncherLayout,
    modifier: Modifier = Modifier,
) {
    val signals by VehicleSignalHub.state.collectAsStateWithLifecycle()
    val vehicle by LauncherRuntime.state.collectAsStateWithLifecycle()
    val fuel by FuelRangeEstimator.state.collectAsStateWithLifecycle()
    val temperature = signals.coolantCelsius?.value
    val colors = LocalDashboardColors.current
    var leftPulseUntil by remember { mutableStateOf(0L) }
    var rightPulseUntil by remember { mutableStateOf(0L) }
    LaunchedEffect(vehicle.leftBlinker) {
        if (vehicle.leftBlinker) {
            leftPulseUntil = android.os.SystemClock.elapsedRealtime() + IndicatorPulseHoldMs
        }
    }
    LaunchedEffect(vehicle.rightBlinker) {
        if (vehicle.rightBlinker) {
            rightPulseUntil = android.os.SystemClock.elapsedRealtime() + IndicatorPulseHoldMs
        }
    }
    val now = android.os.SystemClock.elapsedRealtime()
    val leftActive = vehicle.leftBlinker || now < leftPulseUntil
    val rightActive = vehicle.rightBlinker || now < rightPulseUntil
    val criticalFuel = fuel.lowRange && fuel.criticalRange && fuel.rangeKm != null
    // La transicion infinita recompone la barra en cada frame: solo existe mientras algo parpadea.
    // Mientras existe, esas recomposiciones tambien hacen caducar los pulsos de leftPulseUntil.
    val indicatorPhase = if (leftActive || rightActive || criticalFuel) {
        val indicatorTransition = rememberInfiniteTransition(label = "top bar indicators")
        indicatorTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(450, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "top bar indicator blink",
        ).value
    } else {
        1f
    }
    val leftIlluminated = leftActive && indicatorPhase >= 0.5f
    val rightIlluminated = rightActive && indicatorPhase >= 0.5f

    Box(modifier = modifier) {
        TopBarTurnArrow(
            left = true,
            active = leftIlluminated,
            foreground = colors.primary,
            layout = layout,
            modifier = Modifier.offset(layout.dp(8), layout.dp(15)),
        )
        if (vehicle.handbrake) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset(layout.dp(43), layout.dp(13))
                    .size(layout.dp(22))
                    .border(layout.dp(1.6f), Color(0xFFE02626), CircleShape),
            ) {
                Text(
                    text = "P",
                    color = Color(0xFFE02626),
                    fontSize = layout.sp(13),
                    lineHeight = layout.sp(14),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }
        TopBarHeadlightIcon(
            exteriorLights = vehicle.headlights || vehicle.positionLights || vehicle.dippedBeam,
            highBeam = vehicle.highBeam,
            layout = layout,
            foreground = colors.primary,
            modifier = Modifier.offset(layout.dp(68), layout.dp(14)),
        )
        if (temperature != null) {
            val indicatorColor = coolantIndicatorColor(temperature, colors.dark)
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .offset(layout.dp(96), layout.dp(12))
                    .size(layout.dp(82), layout.dp(25)),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DashboardAssetIcon(
                    drawable = R.drawable.icon_coolant,
                    contentDescription = stringResource(R.string.top_coolant_temperature),
                    tint = indicatorColor,
                    modifier = Modifier.size(layout.dp(22)),
                )
                Text(
                    text = "$temperature \u00b0C",
                    color = indicatorColor,
                    fontSize = layout.sp(15),
                    lineHeight = layout.sp(17),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.offset(x = layout.dp(2)),
                )
            }
        }
        val rangeKm = fuel.rangeKm
        if (fuel.lowRange && rangeKm != null) {
            val criticalAlpha = if (fuel.criticalRange) indicatorPhase.coerceAtLeast(0.12f) else 1f
            DashboardAssetIcon(
                drawable = R.drawable.icon_fuel,
                contentDescription = stringResource(R.string.top_low_range, rangeKm),
                tint = Color(0xFFD51F2A).copy(alpha = criticalAlpha),
                modifier = Modifier
                    .offset(layout.dp(169), layout.dp(14))
                    .size(layout.dp(22)),
            )
        }
        TopBarTurnArrow(
            left = false,
            active = rightIlluminated,
            foreground = colors.primary,
            layout = layout,
            modifier = Modifier.offset(layout.dp(186), layout.dp(15)),
        )
    }
}

@Composable
private fun TopBarHeadlightIcon(
    exteriorLights: Boolean,
    highBeam: Boolean,
    layout: LauncherLayout,
    foreground: Color,
    modifier: Modifier,
) {
    Canvas(modifier = modifier.size(width = layout.dp(28), height = layout.dp(20))) {
        val lampColor = when {
            highBeam -> Color(0xFF248CFF)
            exteriorLights -> Color(0xFF31D86A)
            else -> foreground.copy(alpha = 0.22f)
        }
        val strokeWidth = size.height * 0.085f
        val leftHousing = Path().apply {
            moveTo(size.width * 0.43f, size.height * 0.16f)
            cubicTo(
                size.width * 0.25f,
                size.height * 0.21f,
                size.width * 0.25f,
                size.height * 0.79f,
                size.width * 0.43f,
                size.height * 0.84f,
            )
            close()
        }
        val rightHousing = Path().apply {
            moveTo(size.width * 0.57f, size.height * 0.16f)
            cubicTo(
                size.width * 0.75f,
                size.height * 0.21f,
                size.width * 0.75f,
                size.height * 0.79f,
                size.width * 0.57f,
                size.height * 0.84f,
            )
            close()
        }
        drawPath(leftHousing, lampColor, style = Stroke(strokeWidth, cap = StrokeCap.Round))
        drawPath(rightHousing, lampColor, style = Stroke(strokeWidth, cap = StrokeCap.Round))
        val rayStarts = floatArrayOf(0.28f, 0.50f, 0.72f)
        val rayEnds = floatArrayOf(0.18f, 0.50f, 0.82f)
        repeat(3) { index ->
            drawLine(
                lampColor,
                Offset(size.width * 0.20f, size.height * rayStarts[index]),
                Offset(size.width * 0.03f, size.height * rayEnds[index]),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                lampColor,
                Offset(size.width * 0.80f, size.height * rayStarts[index]),
                Offset(size.width * 0.97f, size.height * rayEnds[index]),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun TopBarTurnArrow(
    left: Boolean,
    active: Boolean,
    foreground: Color,
    layout: LauncherLayout,
    modifier: Modifier,
) {
    Canvas(modifier = modifier.size(layout.dp(28), layout.dp(18))) {
        val color = if (active) Color(0xFF31D86A) else foreground.copy(alpha = 0.22f)
        val direction = if (left) -1f else 1f
        val path = Path().apply {
            moveTo(size.width * (0.50f + direction * 0.43f), size.height * 0.50f)
            lineTo(size.width * (0.50f - direction * 0.03f), size.height * 0.05f)
            lineTo(size.width * (0.50f - direction * 0.03f), size.height * 0.31f)
            lineTo(size.width * (0.50f - direction * 0.43f), size.height * 0.31f)
            lineTo(size.width * (0.50f - direction * 0.43f), size.height * 0.69f)
            lineTo(size.width * (0.50f - direction * 0.03f), size.height * 0.69f)
            lineTo(size.width * (0.50f - direction * 0.03f), size.height * 0.95f)
            close()
        }
        drawPath(path, color)
    }
}

private fun coolantIndicatorColor(temperatureCelsius: Int, darkTheme: Boolean): Color = when {
    temperatureCelsius < 90 -> if (darkTheme) Color(0xFF62B0FF) else Color(0xFF0067B9)
    temperatureCelsius <= 105 -> if (darkTheme) Color.White else Color.Black
    temperatureCelsius <= 109 -> if (darkTheme) Color(0xFFFFB74D) else Color(0xFFE07000)
    else -> if (darkTheme) Color(0xFFFF666B) else Color(0xFFC62828)
}
