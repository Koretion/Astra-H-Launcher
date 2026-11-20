package com.ts10.inspector.dashboard

import androidx.annotation.StringRes
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.ts10.inspector.environment.CurrentWeather
import com.ts10.inspector.environment.WeatherForecastDay
import com.ts10.inspector.launcher.WeatherUiState
import com.ts10.inspector.ui.DashboardAssetIcon
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.ui.LocalDashboardColors
import com.ts10.inspector.ui.modalCornerShape
import com.ts10.inspector.ui.rightPanelModalFrame
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.ts10.inspector.R

@Composable
internal fun WeatherOverlay(
    state: WeatherUiState,
    layout: LauncherLayout,
    onDismiss: () -> Unit,
) {
    val colors = LocalDashboardColors.current
    val weather = state.weatherDetails
    val frame = rightPanelModalFrame(width = 460f, height = 350f, top = 70f)
    val contentWidth = frame.width - 32f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .offset(layout.dp(frame.x), layout.dp(frame.y))
                .size(layout.dp(frame.width), layout.dp(frame.height))
                .clip(modalCornerShape(layout))
                .background(colors.modalBackground)
                .clickable { }
                .padding(layout.dp(16)),
        ) {
            Row(
                modifier = Modifier.size(layout.dp(contentWidth), layout.dp(28)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = weatherOverlayTitle(weather?.locationName, stringResource(R.string.weather_title)) { city -> stringResource(R.string.weather_title_city, city) },
                    color = colors.primary,
                    fontSize = layout.sp(17),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                DashboardAssetIcon(
                    drawable = R.drawable.ic_modal_close,
                    contentDescription = stringResource(R.string.weather_close),
                    tint = colors.primary,
                    modifier = Modifier.size(layout.dp(23)).clickable(onClick = onDismiss),
                )
            }

            if (weather == null) {
                Box(
                    modifier = Modifier.size(layout.dp(contentWidth), layout.dp(270)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.weather_waiting),
                        color = colors.muted,
                        fontSize = layout.sp(13),
                        textAlign = TextAlign.Center,
                    )
                }
                return@Column
            }

            Row(
                modifier = Modifier
                    .padding(top = layout.dp(4))
                    .size(layout.dp(contentWidth), layout.dp(76)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WeatherIcon(
                    weatherCode = weather.code,
                    modifier = Modifier.size(layout.dp(48)),
                )
                Column(modifier = Modifier.padding(start = layout.dp(12)).weight(1f)) {
                    Text(
                        text = state.outdoorTemperature,
                        color = colors.primary,
                        fontSize = layout.sp(30),
                        lineHeight = layout.sp(31),
                        fontWeight = FontWeight.Light,
                    )
                    Text(
                        text = stringResource(weatherDescription(weather.code)),
                        color = colors.primary,
                        fontSize = layout.sp(13),
                    )
                }
                Text(
                    text = weather.updatedAtLabel()?.let { stringResource(R.string.weather_updated_at, it) }.orEmpty(),
                    color = colors.muted,
                    fontSize = layout.sp(9),
                    textAlign = TextAlign.End,
                )
            }

            Row(
                modifier = Modifier.size(layout.dp(contentWidth), layout.dp(48)),
                horizontalArrangement = Arrangement.spacedBy(layout.dp(8)),
            ) {
                WeatherMetric(
                    icon = Icons.Outlined.WaterDrop,
                    label = stringResource(R.string.weather_humidity),
                    value = weather.humidityPercent?.let { "$it %" } ?: "--",
                    layout = layout,
                    modifier = Modifier.weight(1f),
                )
                WeatherMetric(
                    icon = Icons.Outlined.Air,
                    label = stringResource(R.string.weather_wind),
                    value = weather.windSpeedKmh?.let { String.format(Locale.getDefault(), "%.0f km/h", it) } ?: "--",
                    layout = layout,
                    modifier = Modifier.weight(1f),
                )
                WeatherMetric(
                    icon = null,
                    label = stringResource(R.string.weather_feels_like),
                    value = weather.apparentTemperatureCelsius?.let {
                        String.format(Locale.getDefault(), "%.0f°", it)
                    } ?: "--",
                    layout = layout,
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                text = stringResource(R.string.weather_forecast),
                color = colors.primary,
                fontSize = layout.sp(12),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = layout.dp(12)),
            )
            Row(
                modifier = Modifier
                    .padding(top = layout.dp(6))
                    .size(layout.dp(contentWidth), layout.dp(116)),
                horizontalArrangement = Arrangement.spacedBy(layout.dp(6)),
            ) {
                weather.forecast.take(5).forEachIndexed { index, day ->
                    ForecastDay(
                        day = day,
                        today = index == 0,
                        layout = layout,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun WeatherMetric(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    label: String,
    value: String,
    layout: LauncherLayout,
    modifier: Modifier,
) {
    val colors = LocalDashboardColors.current
    Row(
        modifier = modifier
            .background(
                colors.primary.copy(alpha = if (colors.dark) 0.08f else 0.06f),
                RoundedCornerShape(layout.dp(8)),
            )
            .padding(horizontal = layout.dp(8)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.muted,
                modifier = Modifier.size(layout.dp(17)),
            )
        }
        Column(modifier = Modifier.padding(start = layout.dp(if (icon == null) 0 else 6))) {
            Text(text = label, color = colors.muted, fontSize = layout.sp(8))
            Text(text = value, color = colors.primary, fontSize = layout.sp(11), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ForecastDay(
    day: WeatherForecastDay,
    today: Boolean,
    layout: LauncherLayout,
    modifier: Modifier,
) {
    val colors = LocalDashboardColors.current
    Column(
        modifier = modifier
            .background(
                colors.primary.copy(alpha = if (today) 0.11f else 0.05f),
                RoundedCornerShape(layout.dp(8)),
            )
            .padding(vertical = layout.dp(7)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (today) stringResource(R.string.weather_today) else day.dayLabel(LocalConfiguration.current.locales[0]),
            color = colors.primary,
            fontSize = layout.sp(9),
            fontWeight = FontWeight.Bold,
        )
        WeatherIcon(
            weatherCode = day.code,
            modifier = Modifier.padding(top = layout.dp(4)).size(layout.dp(25)),
        )
        Text(
            text = "${day.maximumCelsius.rounded()}°  ${day.minimumCelsius.rounded()}°",
            color = colors.primary,
            fontSize = layout.sp(9),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = layout.dp(4)),
        )
        Text(
            text = stringResource(R.string.weather_rain_probability, day.precipitationProbability),
            color = colors.muted,
            fontSize = layout.sp(8),
            modifier = Modifier.padding(top = layout.dp(3)),
        )
    }
}

@StringRes
internal fun weatherDescription(code: Int): Int = when (code) {
    0 -> R.string.weather_clear
    1 -> R.string.weather_mainly_clear
    2 -> R.string.weather_partly_cloudy
    3 -> R.string.weather_overcast
    45, 48 -> R.string.weather_fog
    in 51..57 -> R.string.weather_drizzle
    in 61..67 -> R.string.weather_rain
    in 71..77 -> R.string.weather_snow
    in 80..82 -> R.string.weather_showers
    in 85..86 -> R.string.weather_snow_showers
    in 95..99 -> R.string.weather_thunderstorm
    else -> R.string.weather_unknown
}

private fun WeatherForecastDay.dayLabel(locale: Locale): String = runCatching {
    LocalDate.parse(date)
        .format(DateTimeFormatter.ofPattern("EEE", locale))
        .removeSuffix(".")
        .uppercase(locale)
}.getOrDefault(date.takeLast(5))

private fun CurrentWeather.updatedAtLabel(): String? = runCatching {
    Instant.ofEpochMilli(updatedAtMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
}.getOrNull()

private fun Double.rounded(): String = String.format(Locale.getDefault(), "%.0f", this)

internal inline fun weatherOverlayTitle(locationName: String?, generic: String, withCity: (String) -> String): String =
    locationName?.trim()?.takeIf(String::isNotEmpty)?.let(withCity) ?: generic
