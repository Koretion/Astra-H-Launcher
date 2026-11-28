package com.ts10.inspector.settings

import com.ts10.inspector.ui.rememberDisplayFormats
import com.ts10.inspector.vehicle.can.profile.CanSignals
import com.ts10.inspector.ui.text
import com.ts10.inspector.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.launcher.LauncherRuntime
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.vehicle.BatteryMaintenancePreferences
import com.ts10.inspector.vehicle.SpeedWarningPreferences
import com.ts10.inspector.vehicle.VehicleMileageTracker
import com.ts10.inspector.vehicle.VehicleSignalHub
import com.ts10.inspector.vehicle.can.Esp32CanClient
import com.ts10.inspector.vehicle.fuel.FuelRangeEstimator
import com.ts10.inspector.vehicle.obd.Obd2Client
import com.ts10.inspector.vehicle.obd.calculateConsumptionLitersPer100Km
import com.ts10.inspector.vehicle.obd.effectiveFuelRateLitersPerHour
import java.util.Locale

@Composable
internal fun OverviewSection(layout: LauncherLayout, width: Float, height: Float) {
    val formats = rememberDisplayFormats()
    val context = LocalContext.current
    val state by LauncherRuntime.state.collectAsStateWithLifecycle()
    val diagnostics by LauncherRuntime.diagnostics.collectAsStateWithLifecycle()
    val fuel by FuelRangeEstimator.state.collectAsStateWithLifecycle()
    val battery by BatteryMaintenancePreferences.get(context).state.collectAsStateWithLifecycle()
    val obd by Obd2Client.state.collectAsStateWithLifecycle()
    val can by Esp32CanClient.state.collectAsStateWithLifecycle()
    val signals by VehicleSignalHub.state.collectAsStateWithLifecycle()
    val mileage by VehicleMileageTracker.state.collectAsStateWithLifecycle()
    LazyColumn(modifier = Modifier.size(layout.dp(width), layout.dp(height))) {
        item { SectionHint(stringResource(R.string.overview_hint), layout) }
        item { SettingsCard(layout, width) {
            MetricRow(stringResource(R.string.metric_speed), formats.speedText(state.speedKmh), state.speedSource, layout)
            MetricRow(stringResource(R.string.metric_engine), signals.engineRpm?.let { "${it.value} rpm" } ?: diagnostics.engineRpm.takeUnless { it == "No data" } ?: stringResource(R.string.common_no_data), signals.engineRpm?.source?.label ?: "CAN Box", layout)
            MetricRow(stringResource(R.string.metric_range), fuel.rangeKm?.let { formats.distanceText(it, 0) } ?: stringResource(R.string.common_pending), fuel.fuelSource.text(), layout)
            MetricRow(stringResource(R.string.metric_odometer), mileage.vehicleOdometerKm?.let { formatMileage(it, formats) } ?: stringResource(R.string.common_pending), mileage.vehicleOdometerSource, layout)
            MetricRow(stringResource(R.string.metric_battery_12v), can.swBatteryVoltage?.let { String.format(Locale.getDefault(), "%.2f V", it) } ?: stringResource(R.string.common_pending), canSource(CanSignals.BATTERY_VOLTAGE), layout)
            MetricRow(stringResource(R.string.metric_battery_maintenance), stringResource(battery.statusText), battery.replacementDate?.let { stringResource(R.string.metric_battery_replacement, formatBatteryDate(it, formats)) } ?: stringResource(R.string.metric_no_date), layout)
        } }
        item { SettingsCard(layout, width) {
            MetricRow(stringResource(R.string.metric_current_trip), formats.distanceText(mileage.currentTripKm), stringResource(R.string.metric_odometer_source), layout)
            MetricRow(stringResource(R.string.metric_doors), if (state.frontLeftDoorOpen || state.frontRightDoorOpen || state.rearLeftDoorOpen || state.rearRightDoorOpen || state.trunkOpen || state.bonnetOpen) stringResource(R.string.doors_some_open) else stringResource(R.string.doors_all_closed), "SW-CAN / CAN Box", layout)
            MetricRow(
                if (obd.troubleCodesRead) stringResource(R.string.metric_faults_count, obd.troubleCodes.size) else stringResource(R.string.metric_faults),
                if (!obd.troubleCodesRead) stringResource(R.string.common_checking) else obd.troubleCodes.ifEmpty { listOf(stringResource(R.string.common_none_f)) }.joinToString(),
                stringResource(R.string.metric_faults_source),
                layout,
            )
        } }
    }
}

@Composable
internal fun EngineSection(layout: LauncherLayout, width: Float, height: Float) {
    val formats = rememberDisplayFormats()
    val context = LocalContext.current
    val obd by Obd2Client.state.collectAsStateWithLifecycle()
    val can by Esp32CanClient.state.collectAsStateWithLifecycle()
    val signals by VehicleSignalHub.state.collectAsStateWithLifecycle()
    // El contador guarda el nombre al leerlo; el de los perfiles se traduce en vivo.
    val profiles by DriverProfilePreferences.get(context).state.collectAsStateWithLifecycle()
    val launcher by LauncherRuntime.state.collectAsStateWithLifecycle()
    val diagnostics by LauncherRuntime.diagnostics.collectAsStateWithLifecycle()
    val fuel by FuelRangeEstimator.state.collectAsStateWithLifecycle()
    val mileage by VehicleMileageTracker.state.collectAsStateWithLifecycle()
    val speedWarningPreferences = remember(context) { SpeedWarningPreferences.get(context) }
    val speedWarning by speedWarningPreferences.state.collectAsStateWithLifecycle()
    val effectiveFuelRate = effectiveFuelRateLitersPerHour(obd)
    val effectiveConsumption = calculateConsumptionLitersPer100Km(effectiveFuelRate, launcher.speedKmh)
    var confirmClear by remember { mutableStateOf(false) }
    LazyColumn(modifier = Modifier.size(layout.dp(width), layout.dp(height))) {
        item { SectionHint(stringResource(R.string.engine_hint), layout) }
        item { SettingsCard(layout, width) {
            MetricRow(stringResource(R.string.metric_speed), formats.speedText(launcher.speedKmh), launcher.speedSource, layout)
            MetricRow(stringResource(R.string.metric_range), fuel.rangeKm?.let { formats.distanceText(it, 0) } ?: stringResource(R.string.common_pending), fuel.fuelSource.text(), layout)
            MetricRow(stringResource(R.string.metric_rpm), signals.engineRpm?.let { "${it.value} rpm" } ?: diagnostics.engineRpm.takeUnless { it == "No data" } ?: stringResource(R.string.common_no_data), signals.engineRpm?.source?.label ?: "CAN Box", layout)
            MetricRow(stringResource(R.string.metric_coolant), signals.coolantCelsius?.let { formats.temperatureText(it.value) } ?: stringResource(R.string.common_pending), signals.coolantCelsius?.source?.label ?: "GMLAN-USB HS-CAN / OBD2 PID 05", layout)
            MetricRow(stringResource(R.string.metric_oil), obd.oilCelsius?.let { formats.temperatureText(it) } ?: stringResource(R.string.common_pending), "OBD2 PID 5C", layout)
            MetricRow(
                stringResource(R.string.metric_instant_consumption),
                effectiveConsumption?.let { formats.consumptionText(it) }
                    ?: effectiveFuelRate?.let { String.format(Locale.getDefault(), "%.2f L/h", it) }
                    ?: stringResource(R.string.common_pending),
                if (effectiveConsumption != null) "MAF/caudal + ${launcher.speedSource}" else stringResource(R.string.metric_idle_flow),
                layout,
            )
            MetricRow(stringResource(R.string.metric_air_flow), obd.massAirFlowGps?.let { String.format(Locale.getDefault(), "%.2f g/s", it) } ?: stringResource(R.string.common_pending), "OBD2 PID 10", layout)
            can.swLampFault?.let { fault ->
                MetricRow(stringResource(R.string.metric_lighting_fault), stringResource(fault.text), canSource(CanSignals.TURN_LAMPS), layout)
            }
            MetricRow(
                stringResource(R.string.metric_fuel_flow),
                (obd.fuelRateLitersPerHour ?: obd.estimatedFuelRateLitersPerHour)?.let {
                    String.format(Locale.getDefault(), "%.2f L/h", it)
                } ?: stringResource(R.string.common_pending),
                if (obd.fuelRateLitersPerHour != null) "OBD2 PID 5E" else stringResource(R.string.metric_estimated_maf),
                layout,
            )
        } }
        item { SettingsCard(layout, width) {
            ToggleRow(
                stringResource(R.string.speed_warning),
                stringResource(R.string.speed_warning_hint),
                speedWarning.enabled,
                layout,
                speedWarningPreferences::setEnabled,
            )
            MetricRow(
                stringResource(R.string.speed_warning_from),
                formats.speedText(speedWarning.minimumSpeedKmh),
                stringResource(R.string.speed_warning_from_hint),
                layout,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(layout.dp(8))) {
                Choice("-10", false, layout) {
                    speedWarningPreferences.setMinimumSpeedKmh(speedWarning.minimumSpeedKmh - 10)
                }
                Choice("+10", false, layout) {
                    speedWarningPreferences.setMinimumSpeedKmh(speedWarning.minimumSpeedKmh + 10)
                }
            }
            ToggleRow(
                stringResource(R.string.speed_warning_repeat),
                stringResource(R.string.speed_warning_repeat_off),
                speedWarning.repeatEnabled,
                layout,
                speedWarningPreferences::setRepeatEnabled,
            )
            if (speedWarning.repeatEnabled) {
                MetricRow(
                    stringResource(R.string.speed_warning_every),
                    "${speedWarning.repeatIntervalSeconds} s",
                    stringResource(R.string.speed_warning_every_hint),
                    layout,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(layout.dp(8))) {
                    Choice("-10 s", false, layout) {
                        speedWarningPreferences.setRepeatIntervalSeconds(speedWarning.repeatIntervalSeconds - 10)
                    }
                    Choice("+10 s", false, layout) {
                        speedWarningPreferences.setRepeatIntervalSeconds(speedWarning.repeatIntervalSeconds + 10)
                    }
                }
            }
        } }
        item { SettingsCard(layout, width) {
            MetricRow(
                stringResource(R.string.mileage_vehicle_odometer),
                mileage.vehicleOdometerKm?.let { formatMileage(it, formats) } ?: stringResource(R.string.common_pending),
                mileage.vehicleOdometerSource,
                layout,
            )
            MetricRow(stringResource(R.string.mileage_recorded_by, profiles.profiles.firstOrNull { it.id == mileage.activeProfileId }?.name ?: mileage.activeProfileName), formatMileage(mileage.recordedKm, formats), mileage.distanceSource, layout)
            MetricRow(stringResource(R.string.mileage_partial), formatMileage(mileage.partialKm, formats), mileage.distanceSource, layout)
            ActionRow(stringResource(R.string.mileage_reset_partial), stringResource(R.string.mileage_reset_partial_hint), layout, true, VehicleMileageTracker::resetPartial)
            MetricRow(stringResource(R.string.mileage_since_refuel), formatMileage(mileage.sinceRefuelKm, formats), mileage.distanceSource, layout)
            RefuelRegistration(layout)
        } }
        item { SettingsCard(layout, width) {
            MetricRow(
                if (obd.troubleCodesRead) stringResource(R.string.metric_faults_count, obd.troubleCodes.size) else stringResource(R.string.metric_faults),
                if (!obd.troubleCodesRead) stringResource(R.string.common_checking) else obd.troubleCodes.ifEmpty { listOf(stringResource(R.string.common_none_f)) }.joinToString(),
                stringResource(R.string.obd_faults_source),
                layout,
            )
            ActionRow(if (obd.busy) stringResource(R.string.obd_processing) else stringResource(R.string.obd_read_faults), stringResource(R.string.obd_read_faults_hint), layout, obd.connection.name == "CONNECTED" && !obd.busy, Obd2Client::readTroubleCodes)
            if (obd.troubleCodes.isNotEmpty()) ActionRow(if (confirmClear) stringResource(R.string.obd_confirm_clear) else stringResource(R.string.obd_clear_faults), stringResource(R.string.obd_clear_faults_hint), layout, !obd.busy) { if (confirmClear) { confirmClear = false; Obd2Client.clearTroubleCodes() } else confirmClear = true }
        } }
    }
}

@Composable
internal fun BodySection(layout: LauncherLayout, width: Float, height: Float) {
    val state by LauncherRuntime.state.collectAsStateWithLifecycle()
    val esp32Can by Esp32CanClient.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val preferences = remember(context) { DashboardPreferences.get(context) }
    val settings by preferences.state.collectAsStateWithLifecycle()
    LazyColumn(modifier = Modifier.size(layout.dp(width), layout.dp(height))) {
        item { SectionHint(stringResource(R.string.body_hint), layout) }
        item { SettingsCard(layout, width) {
            val doorSource = if (esp32Can.swDriverDoorOpen != null) canSource(CanSignals.DRIVER_DOOR_OPEN) else "CAN Box"
            MetricRow(stringResource(R.string.body_driver_door), openLabel(state.frontLeftDoorOpen), doorSource, layout)
            MetricRow(stringResource(R.string.body_passenger_door), openLabel(state.frontRightDoorOpen), doorSource, layout)
            MetricRow(stringResource(R.string.body_rear_doors), if (state.rearLeftDoorOpen || state.rearRightDoorOpen) stringResource(R.string.doors_some_open) else stringResource(R.string.doors_all_closed), "CAN Box", layout)
            MetricRow(stringResource(R.string.body_boot), openLabel(state.trunkOpen), if (esp32Can.swTrunkOpen != null) canSource(CanSignals.TRUNK_OPEN) else "CAN Box", layout)
            MetricRow(stringResource(R.string.body_bonnet), openLabel(state.bonnetOpen), "CAN Box", layout)
            MetricRow(
                stringResource(R.string.body_central_locking),
                esp32Can.swCentralLocked?.let { if (it) stringResource(R.string.body_locked) else stringResource(R.string.body_unlocked) } ?: stringResource(R.string.common_no_data),
                esp32Can.swCentralLockAction?.let { stringResource(R.string.body_last_command, canSource(CanSignals.CENTRAL_LOCK_ACTION), stringResource(it.text)) }
                    ?: canSource(CanSignals.CENTRAL_LOCKED, CanSignals.CENTRAL_LOCK_ACTION),
                layout,
            )
            if (showIndividualTurnIndicators(state.hazard)) {
                MetricRow(stringResource(R.string.body_left_indicator), if (state.leftBlinker) stringResource(R.string.common_active_m) else stringResource(R.string.common_inactive_m), canSource(CanSignals.TURN_LAMPS, CanSignals.TURN_STATE), layout)
                MetricRow(stringResource(R.string.body_right_indicator), if (state.rightBlinker) stringResource(R.string.common_active_m) else stringResource(R.string.common_inactive_m), canSource(CanSignals.TURN_LAMPS, CanSignals.TURN_STATE), layout)
            }
            MetricRow(stringResource(R.string.body_hazard), if (state.hazard) stringResource(R.string.common_active_f) else stringResource(R.string.common_inactive_f), canSource(CanSignals.TURN_LAMPS), layout)
            MetricRow(
                stringResource(R.string.body_bulb_fault),
                esp32Can.swLampFault?.let { stringResource(it.text) } ?: stringResource(R.string.body_not_detected),
                stringResource(R.string.body_bulb_fault_hint, canSource(CanSignals.TURN_LAMPS)),
                layout,
            )
            MetricRow(stringResource(R.string.body_handbrake), if (state.handbrake) stringResource(R.string.body_engaged) else stringResource(R.string.common_released), canSource(CanSignals.HANDBRAKE), layout)
            MetricRow(stringResource(R.string.body_service_brake), esp32Can.swServiceBrakePressed?.let { if (it) stringResource(R.string.common_pressed) else stringResource(R.string.common_released) } ?: stringResource(R.string.common_no_data), canSource(CanSignals.SERVICE_BRAKE), layout)
            MetricRow(
                stringResource(R.string.body_clutch),
                esp32Can.swClutchPressed?.let { if (it) stringResource(R.string.common_pressed) else stringResource(R.string.common_released) } ?: stringResource(R.string.common_no_data),
                esp32Can.swPedalSwitchRaw?.let { stringResource(R.string.can_raw_value, canSource(CanSignals.PEDAL_SWITCH_RAW), "0x%02X".format(it)) }
                    ?: canSource(CanSignals.CLUTCH),
                layout,
            )
            MetricRow(stringResource(R.string.body_high_beam_request), if (esp32Can.swHighBeamRequest) stringResource(R.string.common_active_f) else stringResource(R.string.common_inactive_f), canSource(CanSignals.HIGH_BEAM_REQUEST), layout)
            MetricRow(stringResource(R.string.body_high_beam), if (state.highBeam) stringResource(R.string.common_on_f) else stringResource(R.string.common_off_f), canSource(CanSignals.HIGH_BEAM), layout)
            MetricRow(stringResource(R.string.body_lights), if (state.headlights || state.dippedBeam || state.highBeam || state.positionLights) stringResource(R.string.common_on_m) else stringResource(R.string.common_off_m), "CAN Box", layout)
            MetricRow(stringResource(R.string.body_key_detected), esp32Can.swIgnitionKeyIndex?.let { stringResource(R.string.body_key_number, it) } ?: stringResource(R.string.common_no_data), canSource(CanSignals.IGNITION_KEY_INDEX), layout)
            MetricRow(
                stringResource(R.string.body_can_candidates),
                esp32Can.faultCandidates.size.toString(),
                stringResource(R.string.body_can_candidates_hint),
                layout,
            )
            esp32Can.faultCandidates.take(4).forEach { candidate ->
                MetricRow(
                    candidate.frameLabel,
                    if (candidate.confirmed) stringResource(R.string.common_confirmed) else stringResource(R.string.common_pending),
                    candidate.meaning.text(),
                    layout,
                )
            }
        } }
        item { ToggleRow(stringResource(R.string.body_qf_door_popup), stringResource(R.string.body_qf_door_popup_hint), settings.qfDoorPopupEnabled, layout) { preferences.setQfDoorPopupEnabled(it); LauncherRuntime.setQfDoorPopupEnabled(context, it) } }
    }
}

internal fun showIndividualTurnIndicators(hazardActive: Boolean): Boolean = !hazardActive

@Composable
internal fun ComfortSection(layout: LauncherLayout, width: Float, height: Float) {
    val formats = rememberDisplayFormats()
    val state by LauncherRuntime.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val preferences = remember(context) { DashboardPreferences.get(context) }
    val settings by preferences.state.collectAsStateWithLifecycle()
    val esp32Can by Esp32CanClient.state.collectAsStateWithLifecycle()
    LazyColumn(modifier = Modifier.size(layout.dp(width), layout.dp(height))) {
        item { SectionHint(stringResource(R.string.comfort_hint), layout) }
        item { SettingsCard(layout, width) {
            MetricRow(stringResource(R.string.comfort_climate), if (state.climatePower == true) state.climateMode else stringResource(R.string.comfort_climate_off), "CAN Box", layout)
            MetricRow(stringResource(R.string.comfort_cabin_temp), state.cabinTemperature, "CAN Box", layout)
            MetricRow(stringResource(R.string.comfort_outside_temp), esp32Can.swOutdoorTemperatureCelsius?.let { formats.temperatureText(it, decimals = 1) } ?: formats.temperatureLabel(state.outdoorTemperature), if (esp32Can.swOutdoorTemperatureCelsius != null) canSource(CanSignals.OUTDOOR_TEMPERATURE_C) else stringResource(R.string.comfort_outside_source), layout)
            MetricRow(stringResource(R.string.comfort_fan), state.climateFanLevel?.let { stringResource(R.string.comfort_fan_level, it.toString()) } ?: stringResource(R.string.common_pending), "CAN Box", layout)
            MetricRow(stringResource(R.string.comfort_last_window), esp32Can.swWindowAction?.let { stringResource(it.text) } ?: stringResource(R.string.common_no_activity), canSource(CanSignals.WINDOW_ACTION), layout)
            MetricRow(stringResource(R.string.comfort_wipers), esp32Can.swWiperMode?.let { stringResource(it.text) } ?: stringResource(R.string.common_no_data), canSource(CanSignals.WIPER_MODE), layout)
            MetricRow(
                stringResource(R.string.comfort_last_central),
                esp32Can.swCentralControlAction?.let { stringResource(it.text) } ?: stringResource(R.string.common_no_activity),
                canSource(CanSignals.CENTRAL_CONTROL_ACTION),
                layout,
            )
        } }
        item { ToggleRow(stringResource(R.string.comfort_qf_climate_popup), stringResource(R.string.comfort_qf_climate_popup_hint), settings.qfClimatePopupEnabled, layout) { preferences.setQfClimatePopupEnabled(it); LauncherRuntime.setQfClimatePopupEnabled(context, it) } }
    }
}
