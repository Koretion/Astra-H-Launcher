package com.ts10.inspector.settings

import com.ts10.inspector.vehicle.can.profile.CanSignals
import com.ts10.inspector.ui.text
import com.ts10.inspector.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.ui.DashboardColors
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.ui.LocalDashboardColors
import com.ts10.inspector.vehicle.BatteryMaintenanceLevel
import com.ts10.inspector.vehicle.BatteryMaintenancePreferences
import com.ts10.inspector.vehicle.can.Esp32CanClient
import com.ts10.inspector.vehicle.fuel.FuelRangeEstimator
import com.ts10.inspector.vehicle.fuel.FuelTrackingRepository
import com.ts10.inspector.vehicle.fuel.RefuelEntry
import com.ts10.inspector.vehicle.fuel.VehicleFuel
import com.ts10.inspector.vehicle.obd.Obd2Client
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun EnergySection(layout: LauncherLayout, width: Float, height: Float) {
    val context = LocalContext.current
    val fuel by FuelRangeEstimator.state.collectAsStateWithLifecycle()
    val tracking by FuelTrackingRepository.state.collectAsStateWithLifecycle()
    val obd by Obd2Client.state.collectAsStateWithLifecycle()
    val esp32Can by Esp32CanClient.state.collectAsStateWithLifecycle()
    val maintenancePreferences = remember(context) { BatteryMaintenancePreferences.get(context) }
    val battery by maintenancePreferences.state.collectAsStateWithLifecycle()
    var installationDateDraft by remember { mutableStateOf<LocalDate?>(null) }
    LazyColumn(modifier = Modifier.size(layout.dp(width), layout.dp(height))) {
        item { SectionHint(stringResource(R.string.energy_hint), layout) }
        item { SettingsCard(layout, width) {
            Text(stringResource(R.string.energy_fuel_type), color = LocalDashboardColors.current.primary, fontSize = layout.sp(9), fontWeight = FontWeight.Bold)
            VehicleFuel.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(layout.dp(6)), modifier = Modifier.padding(top = layout.dp(5))) {
                    row.forEach { option ->
                        Choice(stringResource(option.text), tracking.fuel == option, layout) { FuelTrackingRepository.setFuel(option) }
                    }
                }
            }
        } }
        item { SettingsCard(layout, width) {
            MetricRow(stringResource(R.string.energy_fuel), fuel.fuelLiters?.let { String.format(Locale.getDefault(), "%.1f L", it) } ?: obd.fuelPercent?.let { "$it %" } ?: stringResource(R.string.common_pending), fuel.fuelSource.text(), layout)
            MetricRow(stringResource(R.string.energy_estimated_level), fuel.fuelPercent?.let { "$it %" } ?: stringResource(R.string.common_pending), stringResource(R.string.energy_level_source), layout)
            MetricRow(stringResource(R.string.energy_calibration), stringResource(R.string.energy_calibration_points, fuel.calibrationPointCount), stringResource(R.string.energy_calibration_hint), layout)
            MetricRow(stringResource(R.string.energy_estimated_range), fuel.rangeKm?.let { "$it km" } ?: stringResource(R.string.common_pending), stringResource(R.string.energy_range_source), layout)
            MetricRow(stringResource(R.string.energy_average_consumption), String.format(Locale.getDefault(), "%.1f L/100 km", fuel.averageLitersPer100Km), stringResource(R.string.energy_learned_km, fuel.learnedDistanceKm), layout)
            MetricRow(stringResource(R.string.energy_sender_state), if (fuel.senderFaultSuspected) stringResource(R.string.energy_sender_bad) else stringResource(R.string.energy_sender_ok), if (fuel.senderFaultSuspected) stringResource(R.string.energy_sender_bad_hint) else stringResource(R.string.energy_sender_ok_hint), layout)
            MetricRow(
                stringResource(R.string.energy_reserve),
                fuel.hsLowFuel?.let { if (it) stringResource(R.string.common_active_f) else stringResource(R.string.common_inactive_f) } ?: stringResource(R.string.common_pending),
                stringResource(R.string.energy_reserve_hint, canSource(CanSignals.FUEL_SENDER_RAW)),
                layout,
            )
            fuel.lastRefuelLiters?.let { liters ->
                MetricRow(
                    stringResource(R.string.energy_last_refuel),
                    String.format(Locale.getDefault(), "%.1f L", liters),
                    if (fuel.lastRefuelWasFull) stringResource(R.string.energy_full_fill) else stringResource(R.string.energy_partial_fill),
                    layout,
                )
            }
            MetricRow(stringResource(R.string.metric_battery_12v), esp32Can.swBatteryVoltage?.let { String.format(Locale.getDefault(), "%.2f V", it) } ?: stringResource(R.string.common_pending), canSource(CanSignals.BATTERY_VOLTAGE), layout)
            MetricRow(
                stringResource(R.string.energy_charging_system),
                esp32Can.swBatteryVoltage?.let { if (it >= 13.2f) stringResource(R.string.energy_charging) else stringResource(R.string.energy_not_charging) } ?: stringResource(R.string.common_pending),
                stringResource(R.string.energy_charging_hint, canSource(CanSignals.BATTERY_VOLTAGE)),
                layout,
            )
        } }
        item { SettingsCard(layout, width) { RefuelRegistration(layout) } }
        if (tracking.history.isNotEmpty()) {
            item { SettingsCard(layout, width) {
                Text(stringResource(R.string.energy_history_price), color = LocalDashboardColors.current.primary, fontSize = layout.sp(9), fontWeight = FontWeight.Bold)
                RefuelPriceChart(tracking.history, layout, width)
                tracking.history.take(5).forEach { entry ->
                    val date = Instant.ofEpochMilli(entry.timestampMillis).atZone(ZoneId.systemDefault()).toLocalDate()
                    MetricRow(
                        date.format(DateTimeFormatter.ofPattern("dd/MM/yy")),
                        entry.pricePerLiter?.let { String.format(Locale.getDefault(), "%.3f EUR/L", it) } ?: stringResource(R.string.energy_no_price),
                        String.format(Locale.getDefault(), "%.1f L%s", entry.liters, entry.stationName?.let { " · $it" }.orEmpty()),
                        layout,
                    )
                }
            } }
        }
        item { SettingsCard(layout, width) {
            Text(
                stringResource(R.string.energy_battery_reminder),
                color = batteryStatusColor(battery.level, LocalDashboardColors.current),
                fontSize = layout.sp(9),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = layout.dp(4)),
            )
            DetailNavigationRow(
                stringResource(R.string.energy_install_date),
                battery.installationDate?.let(::formatBatteryDate) ?: stringResource(R.string.energy_not_set),
                layout,
            ) {
                installationDateDraft = battery.installationDate ?: LocalDate.now()
            }
            installationDateDraft?.let { draft ->
                InlineBatteryDateEditor(
                    date = draft,
                    layout = layout,
                    onDateChange = { installationDateDraft = it },
                    onCancel = { installationDateDraft = null },
                    onSave = {
                        maintenancePreferences.setInstallationDate(draft)
                        installationDateDraft = null
                    },
                )
            }
            BatteryDurationRow(
                stringResource(R.string.energy_max_life),
                stringResource(R.string.energy_years, battery.lifetimeYears),
                layout,
                onDecrease = { maintenancePreferences.setLifetimeYears(battery.lifetimeYears - 1) },
                onIncrease = { maintenancePreferences.setLifetimeYears(battery.lifetimeYears + 1) },
            )
            BatteryDurationRow(
                stringResource(R.string.energy_warn_ahead),
                stringResource(R.string.energy_months, battery.warningMonths),
                layout,
                onDecrease = { maintenancePreferences.setWarningMonths(battery.warningMonths - 1) },
                onIncrease = { maintenancePreferences.setWarningMonths(battery.warningMonths + 1) },
            )
            MetricRow(
                stringResource(R.string.energy_estimated_replacement),
                battery.replacementDate?.let(::formatBatteryDate) ?: stringResource(R.string.common_pending),
                stringResource(battery.statusText),
                layout,
            )
        } }
        item { SectionHint(stringResource(R.string.energy_pending_items), layout) }
    }
}

@Composable
internal fun RefuelRegistration(layout: LauncherLayout) {
    val colors = LocalDashboardColors.current
    val tracking by FuelTrackingRepository.state.collectAsStateWithLifecycle()
    val pending = tracking.pendingRefuel
    var editing by remember { mutableStateOf(false) }
    var litersText by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }
    var stationText by remember { mutableStateOf("") }
    var filledToFull by remember { mutableStateOf(true) }
    val liters = litersText.replace(',', '.').toFloatOrNull()
    val price = priceText.replace(',', '.').toFloatOrNull()
    LaunchedEffect(pending?.detectedAtMillis, pending?.station) {
        if (pending != null) {
            editing = true
            litersText = String.format(Locale.US, "%.1f", pending.estimatedLiters)
            pending.station?.let { station ->
                stationText = station.name
                priceText = station.pricePerLiter?.let { String.format(Locale.US, "%.3f", it) }.orEmpty()
            }
        }
    }
    if (!editing) {
        ActionRow(
            stringResource(R.string.energy_log_refuel),
            stringResource(R.string.energy_log_refuel_hint),
            layout,
            true,
        ) { editing = true }
        return
    }

    Text(
        stringResource(R.string.energy_refuel_caps),
        color = colors.primary,
        fontSize = layout.sp(9),
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = layout.dp(4)),
    )
    if (pending != null) {
        Text(
            stringResource(R.string.energy_auto_detected, String.format(Locale.getDefault(), "%.1f L", pending.estimatedLiters)) +
                when {
                    pending.lookingUpStation -> stringResource(R.string.fuel_refuel_searching)
                    else -> pending.lookupMessage.orEmpty()
                },
            color = Color(0xFF2E9D50),
            fontSize = layout.sp(8),
            lineHeight = layout.sp(10),
            modifier = Modifier.padding(bottom = layout.dp(5)),
        )
    }
    OutlinedTextField(
        value = litersText,
        onValueChange = { value ->
            if (value.length <= 5 && value.all { it.isDigit() || it == ',' || it == '.' }) {
                litersText = value
            }
        },
        label = { Text(stringResource(R.string.fuel_litres_added)) },
        suffix = { Text("L") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = stationText,
        onValueChange = { if (it.length <= 60) stationText = it },
        label = { Text(stringResource(R.string.fuel_station)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = priceText,
        onValueChange = { value ->
            if (value.length <= 6 && value.all { it.isDigit() || it == ',' || it == '.' }) priceText = value
        },
        label = { Text(stringResource(R.string.energy_price_per_litre)) },
        suffix = { Text("EUR/L") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
    ToggleRow(
        stringResource(R.string.energy_full_fill),
        stringResource(R.string.energy_full_fill_hint),
        filledToFull,
        layout,
    ) { filledToFull = it }
    Row(
        horizontalArrangement = Arrangement.spacedBy(layout.dp(8)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.common_cancel_caps),
            color = colors.muted,
            fontSize = layout.sp(9),
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .weight(1f)
                .clickable {
                    editing = false
                    litersText = ""
                    priceText = ""
                    stationText = ""
                    if (pending != null) FuelTrackingRepository.dismissPending()
                }
                .padding(vertical = layout.dp(9)),
        )
        Text(
            stringResource(R.string.common_save_caps),
            color = if (liters != null && liters > 0f && (price == null || price > 0f)) Color(0xFF2E9D50) else colors.muted,
            fontSize = layout.sp(9),
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = liters != null && liters > 0f && (price == null || price > 0f)) {
                    if (FuelTrackingRepository.recordRefuel(liters!!, filledToFull, price, stationText)) {
                        editing = false
                        litersText = ""
                        priceText = ""
                        stationText = ""
                    }
                }
                .padding(vertical = layout.dp(9)),
        )
    }
}

@Composable
private fun RefuelPriceChart(history: List<RefuelEntry>, layout: LauncherLayout, width: Float) {
    val chronological = history.asReversed().takeLast(20)
    val pricePoints = chronological.mapNotNull(RefuelEntry::pricePerLiter)
    if (pricePoints.size < 2 && chronological.size < 2) {
        Text(stringResource(R.string.energy_chart_hint), color = LocalDashboardColors.current.muted, fontSize = layout.sp(8))
        return
    }
    if (pricePoints.size >= 2) {
        RefuelLineChart(stringResource(R.string.energy_chart_price), pricePoints, "EUR/L", layout, width)
    }
    if (chronological.size >= 2) {
        RefuelLineChart(stringResource(R.string.energy_chart_litres), chronological.map(RefuelEntry::liters), "L", layout, width)
    }
}

@Composable
private fun RefuelLineChart(title: String, points: List<Float>, suffix: String, layout: LauncherLayout, width: Float) {
    val minimum = points.minOrNull() ?: return
    val maximum = points.maxOrNull() ?: return
    val span = (maximum - minimum).coerceAtLeast(0.01f)
    Text(title, color = LocalDashboardColors.current.muted, fontSize = layout.sp(7), fontWeight = FontWeight.Bold)
    Canvas(modifier = Modifier.size(layout.dp(width - 24f), layout.dp(72))) {
        val path = Path()
        points.forEachIndexed { index, value ->
            val x = index * size.width / (points.size - 1)
            val y = size.height - ((value - minimum) / span) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Color(0xFF2E9D50), style = Stroke(width = layout.dp(3).toPx(), cap = StrokeCap.Round))
    }
    Text(
        String.format(Locale.getDefault(), "%.3f - %.3f %s", minimum, maximum, suffix),
        color = LocalDashboardColors.current.muted,
        fontSize = layout.sp(7),
    )
}

@Composable
internal fun BatteryDurationRow(
    title: String,
    value: String,
    layout: LauncherLayout,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    val colors = LocalDashboardColors.current
    Row(
        modifier = Modifier.padding(vertical = layout.dp(5)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = colors.primary,
            fontSize = layout.sp(10),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        TeslaStepButton("−", layout, onDecrease)
        Text(value, color = colors.primary, fontSize = layout.sp(9), fontWeight = FontWeight.Bold)
        TeslaStepButton("+", layout, onIncrease)
    }
}

@Composable
private fun InlineBatteryDateEditor(
    date: LocalDate,
    layout: LauncherLayout,
    onDateChange: (LocalDate) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val colors = LocalDashboardColors.current
    val today = LocalDate.now()
    Column(
        modifier = Modifier
            .padding(vertical = layout.dp(6))
            .background(colors.primary.copy(alpha = .06f), RoundedCornerShape(layout.dp(6)))
            .padding(horizontal = layout.dp(10), vertical = layout.dp(7)),
    ) {
        Text(
            stringResource(R.string.energy_edit_install_date, formatBatteryDate(date)),
            color = Color(0xFF2E9D50),
            fontSize = layout.sp(8),
            fontWeight = FontWeight.Bold,
        )
        BatteryDurationRow(stringResource(R.string.energy_day), date.dayOfMonth.toString(), layout, {
            onDateChange(date.minusDays(1))
        }, {
            onDateChange(date.plusDays(1).coerceAtMost(today))
        })
        BatteryDurationRow(stringResource(R.string.energy_month), date.monthValue.toString(), layout, {
            onDateChange(date.minusMonths(1))
        }, {
            onDateChange(date.plusMonths(1).coerceAtMost(today))
        })
        BatteryDurationRow(stringResource(R.string.energy_year), date.year.toString(), layout, {
            if (date.year > 1990) onDateChange(date.minusYears(1))
        }, {
            onDateChange(date.plusYears(1).coerceAtMost(today))
        })
        Row(
            modifier = Modifier.padding(top = layout.dp(3)),
            horizontalArrangement = Arrangement.spacedBy(layout.dp(18)),
        ) {
            DiagnosticAction(stringResource(R.string.common_cancel_caps), colors.muted, layout, onCancel)
            DiagnosticAction(stringResource(R.string.common_save_caps), Color(0xFF2E9D50), layout, onSave)
        }
    }
}

internal fun formatBatteryDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.getDefault()))

internal fun formatMileage(kilometres: Double): String =
    String.format(Locale.getDefault(), "%.1f km", kilometres)

private fun batteryStatusColor(level: BatteryMaintenanceLevel, colors: DashboardColors): Color = when (level) {
    BatteryMaintenanceLevel.OK -> Color(0xFF2E9D50)
    BatteryMaintenanceLevel.WARNING -> Color(0xFFE39A28)
    BatteryMaintenanceLevel.OVERDUE -> Color(0xFFD94747)
    BatteryMaintenanceLevel.NOT_CONFIGURED -> colors.muted
}
