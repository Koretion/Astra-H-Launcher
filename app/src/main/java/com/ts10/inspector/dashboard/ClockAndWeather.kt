package com.ts10.inspector.dashboard

import com.ts10.inspector.ui.rememberDisplayFormats
import com.ts10.inspector.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.Thunderstorm
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.launcher.LauncherRuntime
import com.ts10.inspector.launcher.WeatherUiState
import com.ts10.inspector.ui.DashboardClock
import com.ts10.inspector.ui.DashboardColors
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.ui.rememberSystemClock
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun RuntimeClockAndWeather(
    layout: LauncherLayout,
    colors: DashboardColors,
    onWeather: () -> Unit,
) {
    val state by LauncherRuntime.weatherState.collectAsStateWithLifecycle()
    val clock = rememberSystemClock()
    val formats = rememberDisplayFormats()
    ClockAndWeather(state.copy(outdoorTemperature = formats.temperatureLabel(state.outdoorTemperature)), clock, layout, colors, onWeather)
}

@Composable
private fun ClockAndWeather(
    state: WeatherUiState,
    clock: DashboardClock,
    layout: LauncherLayout,
    colors: DashboardColors,
    onWeather: () -> Unit,
) {
    Text(
        text = clock.dateLabel,
        color = colors.primary,
        fontSize = layout.sp(15),
        textAlign = TextAlign.Center,
        modifier = Modifier.offset(layout.dp(101), layout.dp(51)).size(layout.dp(115), layout.dp(21)),
    )
    Text(
        text = clock.timeLabel,
        color = colors.primary,
        fontSize = layout.sp(30),
        lineHeight = layout.sp(31),
        textAlign = TextAlign.Center,
        modifier = Modifier.offset(layout.dp(98), layout.dp(70)).size(layout.dp(120), layout.dp(35)),
    )
    WeatherIcon(
        weatherCode = state.weatherCode,
        modifier = Modifier.offset(layout.dp(238), layout.dp(48)).size(layout.dp(30)),
    )
    Text(
        text = sanitizeTemperature(state.outdoorTemperature),
        color = if (state.outdoorTemperature.startsWith("--")) colors.muted else colors.primary,
        fontSize = layout.sp(19),
        lineHeight = layout.sp(21),
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.offset(layout.dp(218), layout.dp(77)).size(layout.dp(70), layout.dp(22)),
    )
    Box(
        modifier = Modifier
            .offset(layout.dp(231), layout.dp(43))
            .size(layout.dp(83), layout.dp(55))
            .clickable(onClick = onWeather),
    )
    HorizonAccent(layout = layout, darkTheme = colors.dark)
}

@Composable
private fun HorizonAccent(layout: LauncherLayout, darkTheme: Boolean) {
    Canvas(
        modifier = Modifier
            .offset(layout.dp(HorizonLeft), layout.dp(HorizonTop))
            .size(layout.dp(HorizonWidth), layout.dp(HorizonHeight)),
    ) {
        val horizonColor = if (darkTheme) {
            Color.White.copy(alpha = 0.18f)
        } else {
            Color.Black.copy(alpha = 0.10f)
        }
        drawRect(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.14f to horizonColor.copy(alpha = horizonColor.alpha * 0.12f),
                    0.5f to horizonColor,
                    0.86f to horizonColor.copy(alpha = horizonColor.alpha * 0.12f),
                    1f to Color.Transparent,
                ),
            ),
            topLeft = Offset(0f, size.height * 0.42f),
            size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.18f),
        )
        drawRect(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.22f to horizonColor.copy(alpha = horizonColor.alpha * 0.08f),
                    0.5f to horizonColor.copy(alpha = horizonColor.alpha * 0.32f),
                    0.78f to horizonColor.copy(alpha = horizonColor.alpha * 0.08f),
                    1f to Color.Transparent,
                ),
            ),
            topLeft = Offset(0f, 0f),
            size = androidx.compose.ui.geometry.Size(size.width, size.height),
        )
    }
}

@Composable
internal fun WeatherIcon(weatherCode: Int?, modifier: Modifier = Modifier) {
    val icon = when (weatherCode) {
        in 2..48 -> Icons.Outlined.Cloud
        in 51..86 -> Icons.Outlined.Grain
        in 95..99 -> Icons.Outlined.Thunderstorm
        else -> null
    }
    if (icon != null) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(R.string.weather_current),
            tint = if (weatherCode in 71..86) Color(0xFF6C91B5) else Color(0xFF607D8B),
            modifier = modifier,
        )
        return
    }
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outer = size.minDimension * 0.47f
        val inner = size.minDimension * 0.35f
        repeat(12) { index ->
            val angle = Math.toRadians((index * 30).toDouble())
            drawLine(
                color = Weather,
                start = Offset(center.x + cos(angle).toFloat() * inner, center.y + sin(angle).toFloat() * inner),
                end = Offset(center.x + cos(angle).toFloat() * outer, center.y + sin(angle).toFloat() * outer),
                strokeWidth = size.minDimension * 0.09f,
                cap = StrokeCap.Round,
            )
        }
        drawCircle(Weather, radius = size.minDimension * 0.28f, center = center)
    }
}
