package com.ts10.inspector.settings

import androidx.compose.ui.platform.LocalConfiguration
import com.ts10.inspector.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.trips.VehicleTripMapPoint
import com.ts10.inspector.trips.VehicleTripSummary
import com.ts10.inspector.trips.VehicleTripTracker
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.ui.LocalDashboardColors
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun TripsSection(layout: LauncherLayout, width: Float, height: Float, onConfigure: () -> Unit) {
    val context = LocalContext.current
    val profilePreferences = remember(context) { DriverProfilePreferences.get(context) }
    val profiles by profilePreferences.state.collectAsStateWithLifecycle()
    val historyLimit = profiles.activeProfile.tripHistoryLimit
    var selected by remember { mutableStateOf<VehicleTripSummary?>(null) }
    val tracking by VehicleTripTracker.state.collectAsStateWithLifecycle()
    val trips = remember(historyLimit, tracking.databasePoints, tracking.activeSession) {
        runCatching { VehicleTripTracker.recentTrips(context, historyLimit) }.getOrDefault(emptyList())
    }
    LazyColumn(modifier = Modifier.size(layout.dp(width), layout.dp(height))) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHint(stringResource(R.string.trips_local_history), layout, Modifier.weight(1f))
                Choice("25", historyLimit == 25, layout) { profilePreferences.setTripHistoryLimit(profiles.activeProfileId, 25) }
                Choice("50", historyLimit == 50, layout) { profilePreferences.setTripHistoryLimit(profiles.activeProfileId, 50) }
            }
        }
        item { ActionRow(stringResource(R.string.trips_configure), stringResource(R.string.trips_configure_hint), layout, true, onConfigure) }
        selected?.let { trip ->
            item { TripDetail(trip, layout, width) { selected = null } }
        } ?: run {
            if (trips.isEmpty()) {
                item { EmptyState(stringResource(R.string.trips_empty), stringResource(R.string.trips_empty_hint), layout) }
            } else {
                items(trips.size) { index -> TripRow(trips[index], layout, width) { selected = trips[index] } }
            }
        }
    }
}

/**
 * Nombre actual del perfil del viaje: el guardado queda en el idioma de cuando se grabó y no
 * refleja renombres posteriores. Si el perfil ya no existe se usa el guardado.
 */
@Composable
private fun tripDriverName(trip: VehicleTripSummary): String {
    val profiles by DriverProfilePreferences.get(LocalContext.current).state.collectAsStateWithLifecycle()
    return profiles.profiles.firstOrNull { it.id == trip.driverProfileId }?.name
        ?: trip.driverProfileName.ifBlank { stringResource(R.string.profiles_unassigned) }
}

@Composable
private fun TripRow(trip: VehicleTripSummary, layout: LauncherLayout, width: Float, onClick: () -> Unit) {
    val colors = LocalDashboardColors.current
    Row(
        modifier = Modifier.size(layout.dp(width), layout.dp(54)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(formatTripDate(trip.startUtc, LocalConfiguration.current.locales[0]), color = colors.primary, fontSize = layout.sp(11), fontWeight = FontWeight.Bold)
            Text("${tripDriverName(trip)} · ${formatTripDuration(trip)}", color = colors.muted, fontSize = layout.sp(8))
        }
        TripValue(String.format(Locale.getDefault(), "%.1f km", trip.distanceKm), stringResource(R.string.trips_distance), layout)
        TripValue(String.format(Locale.getDefault(), "%.0f km/h", trip.averageSpeedKmh), stringResource(R.string.trips_average), layout)
        TripValue(trip.consumptionLitersPer100Km?.let { String.format(Locale.getDefault(), "%.1f L/100", it) } ?: "--", stringResource(R.string.trips_consumption), layout)
        Icon(Icons.Outlined.ChevronRight, null, tint = colors.muted, modifier = Modifier.size(layout.dp(18)))
    }
}

@Composable
private fun TripValue(value: String, label: String, layout: LauncherLayout) {
    val colors = LocalDashboardColors.current
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(horizontal = layout.dp(5))) {
        Text(value, color = colors.primary, fontSize = layout.sp(9), fontWeight = FontWeight.Bold)
        Text(label, color = colors.muted, fontSize = layout.sp(6))
    }
}

@Composable
private fun TripDetail(trip: VehicleTripSummary, layout: LauncherLayout, width: Float, onBack: () -> Unit) {
    val colors = LocalDashboardColors.current
    SettingsCard(layout, width) {
        Text(stringResource(R.string.trips_back), color = Color(0xFF2E9D50), fontSize = layout.sp(8), fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onBack))
        Text(formatTripDate(trip.startUtc, LocalConfiguration.current.locales[0]), color = colors.primary, fontSize = layout.sp(14), fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = layout.dp(5)))
        Text(stringResource(R.string.trips_driver, tripDriverName(trip)), color = colors.muted, fontSize = layout.sp(8), modifier = Modifier.padding(bottom = layout.dp(5)))
        RoutePreview(trip.route, layout, width - 24f)
        Row(modifier = Modifier.padding(top = layout.dp(7))) {
            TripValue(String.format(Locale.getDefault(), "%.1f km", trip.distanceKm), stringResource(R.string.trips_distance), layout)
            TripValue(String.format(Locale.getDefault(), "%.0f km/h", trip.averageSpeedKmh), stringResource(R.string.trips_average_speed), layout)
            TripValue(String.format(Locale.getDefault(), "%.0f km/h", trip.maxSpeedKmh), stringResource(R.string.trips_max), layout)
            TripValue(trip.consumptionLitersPer100Km?.let { String.format(Locale.getDefault(), "%.1f L/100", it) } ?: stringResource(R.string.trips_consumption_pending), stringResource(R.string.trips_consumption), layout)
        }
    }
}

@Composable
private fun RoutePreview(points: List<VehicleTripMapPoint>, layout: LauncherLayout, width: Float) {
    val colors = LocalDashboardColors.current
    Box(
        modifier = Modifier.size(layout.dp(width), layout.dp(190)).background(
            if (colors.dark) Color(0xFF171B1F) else Color(0xFFE1E4E2), RoundedCornerShape(layout.dp(8)),
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (points.size < 2) {
            Text(stringResource(R.string.trips_route_unavailable), color = colors.muted, fontSize = layout.sp(10))
        } else {
            Canvas(modifier = Modifier.fillMaxSize().padding(layout.dp(14))) {
                val minLat = points.minOf { it.latitude }
                val maxLat = points.maxOf { it.latitude }
                val minLon = points.minOf { it.longitude }
                val maxLon = points.maxOf { it.longitude }
                val latSpan = (maxLat - minLat).coerceAtLeast(0.00001)
                val lonSpan = (maxLon - minLon).coerceAtLeast(0.00001)
                val path = Path()
                points.forEachIndexed { index, point ->
                    val x = ((point.longitude - minLon) / lonSpan * size.width).toFloat()
                    val y = (size.height - (point.latitude - minLat) / latSpan * size.height).toFloat()
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, Color(0xFF2E9D50), style = Stroke(width = layout.dp(4).toPx(), cap = StrokeCap.Round))
                fun projected(point: VehicleTripMapPoint) = Offset(
                    ((point.longitude - minLon) / lonSpan * size.width).toFloat(),
                    (size.height - (point.latitude - minLat) / latSpan * size.height).toFloat(),
                )
                drawCircle(Color(0xFF2E9D50), layout.dp(5).toPx(), projected(points.first()))
                drawCircle(Color(0xFFD94747), layout.dp(5).toPx(), projected(points.last()))
            }
        }
    }
}

private fun formatTripDate(utc: String, locale: Locale): String = runCatching {
    Instant.parse(utc).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", locale))
}.getOrDefault(utc)

private fun formatTripDuration(trip: VehicleTripSummary): String = runCatching {
    val minutes = Duration.between(Instant.parse(trip.startUtc), Instant.parse(trip.endUtc)).toMinutes().coerceAtLeast(0)
    "${minutes / 60} h ${minutes % 60} min"
}.getOrDefault("--")
