package com.ts10.inspector.settings

import com.ts10.inspector.ui.text
import com.ts10.inspector.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.ui.LocalDashboardColors
import com.ts10.inspector.vehicle.tires.DdsCalibrationState
import com.ts10.inspector.vehicle.tires.TireMonitoringMode
import com.ts10.inspector.vehicle.tires.TireMonitoringRepository
import com.ts10.inspector.vehicle.tires.TireMonitoringSource
import com.ts10.inspector.vehicle.tires.TireMonitoringState
import com.ts10.inspector.vehicle.tires.TirePosition
import com.ts10.inspector.vehicle.tires.TpmsPressurePreferences
import com.ts10.inspector.vehicle.tires.WheelSpeeds
import java.util.Locale

@Composable
internal fun TpmsSection(layout: LauncherLayout, width: Float, height: Float) {
    val context = LocalContext.current
    val preferences = remember(context) { TpmsPressurePreferences.get(context) }
    val pressure by preferences.state.collectAsStateWithLifecycle()
    val monitoring by TireMonitoringRepository.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { TireMonitoringRepository.start(context) }
    LazyColumn(modifier = Modifier.size(layout.dp(width), layout.dp(height))) {
        item { SettingsCard(layout, width) {
            MetricRow(stringResource(R.string.tpms_active_system), tireMonitoringSourceLabel(monitoring), monitoring.status.text(), layout)
            TirePosition.entries.forEach { position ->
                val sensor = monitoring.readings[position]
                val wheelSpeed = monitoring.wheelSpeeds?.value(position)
                val deviation = monitoring.ddsDeviationPercent[position]
                val value = when {
                    sensor?.pressureBar != null -> String.format(
                        Locale.getDefault(),
                        "%.1f bar  /  %s",
                        sensor.pressureBar,
                        sensor.temperatureCelsius?.let { "%.0f °C".format(Locale.getDefault(), it) } ?: "-- °C",
                    )
                    wheelSpeed != null -> String.format(Locale.getDefault(), "%.1f km/h", wheelSpeed)
                    else -> "--"
                }
                val source = when {
                    sensor != null -> stringResource(R.string.tpms_usb_sensor)
                    deviation != null -> String.format(
                        Locale.getDefault(),
                        "DDS %+.1f %%%s",
                        deviation,
                        if (position in monitoring.ddsAlerts) stringResource(R.string.tpms_possible_drop) else "",
                    )
                    else -> stringResource(R.string.tpms_no_wheel_data)
                }
                MetricRow(stringResource(position.text), value, source, layout)
            }
        } }
        item { SettingsCard(layout, width) {
            Text(stringResource(R.string.tpms_source), color = LocalDashboardColors.current.muted, fontSize = layout.sp(8), fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.padding(top = layout.dp(5))) {
                Choice("AUTO", monitoring.mode == TireMonitoringMode.AUTO, layout) { TireMonitoringRepository.setMode(TireMonitoringMode.AUTO) }
                Choice("USB TPMS", monitoring.mode == TireMonitoringMode.USB, layout) { TireMonitoringRepository.setMode(TireMonitoringMode.USB) }
                Choice("DDS", monitoring.mode == TireMonitoringMode.DDS, layout) { TireMonitoringRepository.setMode(TireMonitoringMode.DDS) }
            }
            MetricRow(
                stringResource(R.string.tpms_usb_receiver),
                when (monitoring.usbCandidates.size) {
                    0 -> stringResource(R.string.tpms_not_connected)
                    1 -> monitoring.usbCandidates.first().identifier
                    else -> stringResource(R.string.tpms_candidates, monitoring.usbCandidates.size)
                },
                if (monitoring.usbProtocolReady) stringResource(R.string.tpms_protocol_active) else stringResource(R.string.tpms_protocol_pending),
                layout,
            )
            monitoring.usbCandidates.forEach { candidate ->
                ActionRow(
                    candidate.name,
                    "USB ${candidate.identifier}${if (candidate.deviceId == monitoring.selectedUsbDeviceId) stringResource(R.string.tpms_selected_suffix) else ""}",
                    layout,
                    true,
                ) { TireMonitoringRepository.selectUsbCandidate(candidate.deviceId) }
            }
            ActionRow(
                if (monitoring.calibrationState == DdsCalibrationState.CALIBRATING) stringResource(R.string.tpms_calibrating, monitoring.calibrationProgress) else stringResource(R.string.tpms_calibrate),
                stringResource(R.string.tpms_calibrate_hint),
                layout,
                monitoring.calibrationState != DdsCalibrationState.CALIBRATING,
                TireMonitoringRepository::beginDdsCalibration,
            )
            if (monitoring.calibrationState == DdsCalibrationState.READY) {
                ActionRow(stringResource(R.string.tpms_reset_calibration), stringResource(R.string.tpms_reset_calibration_hint), layout, true, TireMonitoringRepository::resetDdsCalibration)
            }
        } }
        item { SectionHint(stringResource(R.string.tpms_cold_pressures), layout) }
        item { SettingsCard(layout, width) {
            Text(
                if (pressure.manufacturerValuesConfirmed) stringResource(R.string.tpms_reference_confirmed) else stringResource(R.string.tpms_confirm_label),
                color = if (pressure.manufacturerValuesConfirmed) Color(0xFF2E9D50) else Color(0xFFE39A28),
                fontSize = layout.sp(9),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = layout.dp(4)),
            )
            BatteryDurationRow(
                stringResource(R.string.tpms_front_target),
                String.format(Locale.getDefault(), "%.1f bar", pressure.frontTargetBar),
                layout,
                onDecrease = { preferences.setFrontTargetBar(pressure.frontTargetBar - 0.1f) },
                onIncrease = { preferences.setFrontTargetBar(pressure.frontTargetBar + 0.1f) },
            )
            BatteryDurationRow(
                stringResource(R.string.tpms_rear_target),
                String.format(Locale.getDefault(), "%.1f bar", pressure.rearTargetBar),
                layout,
                onDecrease = { preferences.setRearTargetBar(pressure.rearTargetBar - 0.1f) },
                onIncrease = { preferences.setRearTargetBar(pressure.rearTargetBar + 0.1f) },
            )
            BatteryDurationRow(
                stringResource(R.string.tpms_low_margin),
                String.format(Locale.getDefault(), "%.1f bar", pressure.lowToleranceBar),
                layout,
                onDecrease = { preferences.setLowToleranceBar(pressure.lowToleranceBar - 0.1f) },
                onIncrease = { preferences.setLowToleranceBar(pressure.lowToleranceBar + 0.1f) },
            )
            BatteryDurationRow(
                stringResource(R.string.tpms_high_margin),
                String.format(Locale.getDefault(), "%.1f bar", pressure.highToleranceBar),
                layout,
                onDecrease = { preferences.setHighToleranceBar(pressure.highToleranceBar - 0.1f) },
                onIncrease = { preferences.setHighToleranceBar(pressure.highToleranceBar + 0.1f) },
            )
            MetricRow(
                stringResource(R.string.tpms_front_range),
                String.format(Locale.getDefault(), "%.1f - %.1f bar", pressure.frontMinimumBar, pressure.frontMaximumBar),
                stringResource(R.string.tpms_range_hint),
                layout,
            )
            MetricRow(
                stringResource(R.string.tpms_rear_range),
                String.format(Locale.getDefault(), "%.1f - %.1f bar", pressure.rearMinimumBar, pressure.rearMaximumBar),
                stringResource(R.string.tpms_range_hint),
                layout,
            )
            if (!pressure.manufacturerValuesConfirmed) {
                ActionRow(
                    stringResource(R.string.tpms_confirm_values),
                    stringResource(R.string.tpms_confirm_values_hint),
                    layout,
                    true,
                    preferences::confirmManufacturerValues,
                )
            }
        } }
        item { SettingsCard(layout, width) {
            MetricRow(
                stringResource(R.string.tpms_usb_alarms),
                if (pressure.manufacturerValuesConfirmed) stringResource(R.string.tpms_usb_alarms_on) else stringResource(R.string.tpms_usb_alarms_off),
                stringResource(R.string.tpms_usb_alarms_hint),
                layout,
            )
            MetricRow(
                stringResource(R.string.tpms_dds_alarm),
                if (monitoring.calibrationState == DdsCalibrationState.READY) stringResource(R.string.common_active_f) else stringResource(R.string.tpms_pending_calibration),
                stringResource(R.string.tpms_dds_alarm_hint),
                layout,
            )
            MetricRow(stringResource(R.string.tpms_physical_connection), stringResource(R.string.tpms_powered_hub), stringResource(R.string.tpms_ts10_ports), layout)
            SectionHint(stringResource(R.string.tpms_auto_hint), layout)
        } }
    }
}

private fun WheelSpeeds.value(position: TirePosition): Float = when (position) {
    TirePosition.FRONT_LEFT -> frontLeftKmh
    TirePosition.FRONT_RIGHT -> frontRightKmh
    TirePosition.REAR_LEFT -> rearLeftKmh
    TirePosition.REAR_RIGHT -> rearRightKmh
}

@Composable
internal fun tireMonitoringSourceLabel(state: TireMonitoringState): String = when (state.source) {
    TireMonitoringSource.NONE -> when {
        state.snapshotRestored && state.lastSnapshotSource == TireMonitoringSource.USB_TPMS -> stringResource(R.string.tpms_usb_saved)
        state.snapshotRestored -> stringResource(R.string.tpms_dds_saved)
        state.calibrationState == DdsCalibrationState.WAITING_FOR_SPEEDS ||
            state.calibrationState == DdsCalibrationState.CALIBRATING ||
            state.calibrationState == DdsCalibrationState.READY -> "DDS"
        else -> stringResource(R.string.common_pending)
    }
    TireMonitoringSource.USB_TPMS -> "TPMS USB"
    TireMonitoringSource.DDS_OBD2 -> stringResource(R.string.tpms_dds_obd2)
    TireMonitoringSource.DDS_HS_CAN -> stringResource(R.string.tpms_dds_hs_can)
}
