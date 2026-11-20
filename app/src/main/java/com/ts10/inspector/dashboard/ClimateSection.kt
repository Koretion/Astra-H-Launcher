package com.ts10.inspector.dashboard

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.R
import com.ts10.inspector.launcher.ClimateUiState
import com.ts10.inspector.launcher.LauncherRuntime
import com.ts10.inspector.ui.DashboardColors
import com.ts10.inspector.ui.LauncherLayout
import kotlin.math.roundToInt

@Composable
internal fun RuntimeClimate(layout: LauncherLayout, colors: DashboardColors) {
    val state by LauncherRuntime.climateState.collectAsStateWithLifecycle()
    Climate(state, layout, colors)
}

@Composable
private fun Climate(state: ClimateUiState, layout: LauncherLayout, colors: DashboardColors) {
    val systemOn = state.climatePower == true
    val compressorOn = systemOn && state.climateCooling == true
    // Raise/Opel exports seven blower steps (1..7). The high nibble of the same byte is
    // air direction and is decoded separately.
    val fanLevel = if (systemOn) (state.climateFanLevel ?: 0).coerceIn(0, 7) else 0
    val fanOn = fanLevel > 0
    val temperatureKnown = state.cabinTemperature.isNotBlank() && !state.cabinTemperature.startsWith("--")
    val active = if (colors.dark) Color(0xFFF0F3F7) else Color(0xFF7A8088)
    val temperatureActive = if (colors.dark) Color(0xFFF4F4F4) else active
    val inactive = if (colors.dark) Color(0xFF2F343B) else Color(0xFFD2D4D8)

    Text(
        text = sanitizeTemperature(state.cabinTemperature),
        color = if (systemOn && temperatureKnown) temperatureActive else inactive,
        fontSize = layout.sp(20),
        lineHeight = layout.sp(21),
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.offset(layout.dp(16), layout.dp(ClimateTop + 5f)).size(layout.dp(42), layout.dp(25)),
    )
    ClimateAirflowIcon(
        airDirection = state.climateAirDirection,
        fanOn = fanOn,
        automatic = state.climateAuto == true,
        activeColor = active,
        inactiveColor = inactive,
        layout = layout,
    )
    // The Raise/Opel payload exposes the A/C request but no trustworthy heater-output bit.
    // A high set temperature alone does not prove that warm air is actually being delivered.
    ClimateLayer(R.drawable.climate_snowflake, 456f, 13f, 69f, 68f, compressorOn, active, inactive, layout)
    ClimateLayer(R.drawable.climate_fan, 636f, 16f, 62f, 61f, fanOn, active, inactive, layout)
    ClimateFanBars(fanLevel, active, inactive, layout)
}

@Composable
private fun ClimateAirflowIcon(
    airDirection: String,
    fanOn: Boolean,
    automatic: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    layout: LauncherLayout,
) {
    // Códigos internos en inglés; se aceptan los antiguos en español guardados en caché.
    val normalizedDirection = airDirection.lowercase()
    val upActive = "up" in normalizedDirection || "arriba" in normalizedDirection
    val roofActive = "roof" in normalizedDirection || "techo" in normalizedDirection
    val upperActive = automatic || upActive || roofActive
    val middleActive = automatic || "middle" in normalizedDirection || "medio" in normalizedDirection
    val feetActive = automatic || "feet" in normalizedDirection || "pies" in normalizedDirection
    val directionDescription = buildList {
        if (upActive) add(stringResource(R.string.climate_air_up))
        if (middleActive && !automatic) add(stringResource(R.string.climate_air_middle))
        if (feetActive && !automatic) add(stringResource(R.string.climate_air_feet))
        if (roofActive) add(stringResource(R.string.climate_air_roof))
        if (automatic) add(stringResource(R.string.climate_auto))
    }.joinToString(" + ")
    val modifier = Modifier
        .offset(layout.dp(277f * ReferenceScale), layout.dp(ClimateTop + 10f * ReferenceScale))
        .size(layout.dp(59f * ReferenceScale), layout.dp(69f * ReferenceScale))

    // Keep the original artwork as the inactive base, then illuminate each outlet
    // independently. Previously the whole bitmap used one tint, so changing the manual
    // distribution could never produce a visible change.
    Image(
        painter = painterResource(R.drawable.climate_airflow),
        contentDescription = directionDescription.takeIf(String::isNotBlank)?.let { stringResource(R.string.climate_air_direction, it) },
        colorFilter = ColorFilter.tint(inactiveColor),
        modifier = modifier,
    )
    if (fanOn) {
        Canvas(modifier = modifier) {
            if (upperActive) {
                drawAirflowArrow(
                    start = Offset(size.width * .08f, size.height * .14f),
                    end = Offset(size.width * .43f, size.height * .14f),
                    color = activeColor,
                )
            }
            if (middleActive) {
                drawAirflowArrow(
                    start = Offset(size.width * .04f, size.height * .39f),
                    end = Offset(size.width * .38f, size.height * .39f),
                    color = activeColor,
                )
            }
            if (feetActive) {
                drawAirflowArrow(
                    start = Offset(size.width * .03f, size.height * .62f),
                    end = Offset(size.width * .30f, size.height * .79f),
                    color = activeColor,
                )
            }
        }
    }
    if (automatic) {
        // The occupant is part of the inactive airflow artwork. Overlay its dedicated asset so
        // AUTO illuminates the complete seat-and-airflow symbol, not only its outlet arrows.
        Image(
            painter = painterResource(R.drawable.climate_occupant),
            contentDescription = null,
            colorFilter = ColorFilter.tint(activeColor),
            modifier = Modifier
                .offset(
                    layout.dp(303f * ReferenceScale),
                    layout.dp(ClimateTop + 10f * ReferenceScale),
                )
                .size(layout.dp(33f * ReferenceScale), layout.dp(69f * ReferenceScale)),
        )
    }
    if (automatic) {
        Text(
            text = stringResource(R.string.climate_auto),
            color = if (fanOn) activeColor else inactiveColor,
            fontSize = layout.sp(10),
            lineHeight = layout.sp(11),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .offset(layout.dp(264f * ReferenceScale), layout.dp(ClimateTop + 72f * ReferenceScale))
                .size(layout.dp(86f * ReferenceScale), layout.dp(34f * ReferenceScale)),
        )
    }
}

private fun DrawScope.drawAirflowArrow(start: Offset, end: Offset, color: Color) {
    val stroke = size.minDimension * .065f
    drawLine(color, start, end, strokeWidth = stroke, cap = StrokeCap.Round)
    val direction = end - start
    val length = direction.getDistance().coerceAtLeast(1f)
    val unit = direction / length
    val perpendicular = Offset(-unit.y, unit.x)
    val headLength = size.minDimension * .13f
    val headWidth = size.minDimension * .09f
    drawLine(
        color,
        end,
        end - unit * headLength + perpendicular * headWidth,
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color,
        end,
        end - unit * headLength - perpendicular * headWidth,
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}

@Composable
private fun ClimateFanBars(
    fanLevel: Int,
    activeColor: Color,
    inactiveColor: Color,
    layout: LauncherLayout,
) {
    val barCount = 7
    val barWidth = 12f
    val barGap = 7f
    val minimumHeight = 13f
    val heightStep = (63f - minimumHeight) / (barCount - 1)
    Canvas(
        modifier = Modifier
            .offset(layout.dp(753f * ReferenceScale), layout.dp(ClimateTop + 15f * ReferenceScale))
            .size(layout.dp(126f * ReferenceScale), layout.dp(63f * ReferenceScale)),
    ) {
        repeat(barCount) { index ->
            val barHeight = minimumHeight + heightStep * index
            val left = (barWidth + barGap) * index
            drawRoundRect(
                color = if (index < fanLevel) activeColor else inactiveColor,
                topLeft = Offset(
                    size.width * (left / 126f),
                    size.height * ((63f - barHeight) / 63f),
                ),
                size = androidx.compose.ui.geometry.Size(
                    size.width * (barWidth / 126f),
                    size.height * (barHeight / 63f),
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.016f, size.width * 0.016f),
            )
        }
    }
}

@Composable
private fun ClimateLayer(
    drawable: Int,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    enabled: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    layout: LauncherLayout,
) {
    Image(
        painter = painterResource(drawable),
        contentDescription = null,
        colorFilter = ColorFilter.tint(if (enabled) activeColor else inactiveColor),
        modifier = Modifier
            .offset(layout.dp(x * ReferenceScale), layout.dp(ClimateTop + y * ReferenceScale))
            .size(layout.dp(width * ReferenceScale), layout.dp(height * ReferenceScale)),
    )
}

internal fun sanitizeTemperature(value: String): String {
    val normalized = value
        .replace("\u00C2\u00B0", "\u00B0")
        .replace("\u00BA", "\u00B0")
    val parsed = parseTemperature(normalized) ?: return normalized
    val rounded = parsed.roundToInt().toString()
    return normalized.replace(Regex("-?\\d+(?:[.,]\\d+)?"), rounded)
}

private fun parseTemperature(value: String): Float? = Regex("-?\\d+(?:[.,]\\d+)?")
    .find(value)
    ?.value
    ?.replace(',', '.')
    ?.toFloatOrNull()
