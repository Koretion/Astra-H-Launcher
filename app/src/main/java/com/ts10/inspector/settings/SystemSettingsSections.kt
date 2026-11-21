package com.ts10.inspector.settings

import com.ts10.inspector.diagnostics.RootDetectionStatus
import com.ts10.inspector.ui.text
import androidx.annotation.StringRes
import com.ts10.inspector.ui.AppLanguage
import com.ts10.inspector.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.graphics.drawable.toBitmap
import com.ts10.inspector.AppVersion
import com.ts10.inspector.dashboard.VehicleSceneMode
import com.ts10.inspector.diagnostics.AndroidRootChecker
import com.ts10.inspector.diagnostics.QfLogExportActivity
import com.ts10.inspector.environment.OsmRoadContextProvider
import com.ts10.inspector.launcher.HybridOverlayService
import com.ts10.inspector.launcher.LauncherRuntime
import com.ts10.inspector.panel.InstalledApplication
import com.ts10.inspector.panel.applicationLabel
import com.ts10.inspector.panel.isDefaultLauncher
import com.ts10.inspector.panel.openAndroidSystemSettings
import com.ts10.inspector.panel.openDefaultLauncherSelector
import com.ts10.inspector.panel.rememberInstalledApplications
import com.ts10.inspector.ui.DashboardThemeMode
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.ui.LocalDashboardColors
import com.ts10.inspector.vehicle.can.Esp32CanClient
import com.ts10.inspector.vehicle.can.firmware.Esp32FirmwarePickerActivity
import com.ts10.inspector.vehicle.can.firmware.Esp32FirmwareUpdater
import com.ts10.inspector.update.GmlanFirmwareUpdateManager
import com.ts10.inspector.update.UpdateMode
import com.ts10.inspector.vehicle.fuel.distanceMeters
import com.ts10.inspector.vehicle.obd.Obd2Client
import com.ts10.inspector.vehicle.qf.QfDataShareBridge
import com.ts10.inspector.vehicle.tires.TireMonitoringRepository
import com.ts10.inspector.vision.UvcDirectVisionProbe

@Composable
internal fun ConnectionsSection(
    layout: LauncherLayout,
    width: Float,
    height: Float,
    onObd2: () -> Unit,
    onEsp32: () -> Unit,
) {
    val obd by Obd2Client.state.collectAsStateWithLifecycle()
    val can by Esp32CanClient.state.collectAsStateWithLifecycle()
    val root by AndroidRootChecker.state.collectAsStateWithLifecycle()
    val tires by TireMonitoringRepository.state.collectAsStateWithLifecycle()
    val gps by LauncherRuntime.gpsAcquisition.collectAsStateWithLifecycle()
    LazyColumn(modifier = Modifier.size(layout.dp(width), layout.dp(height))) {
        item { SettingsCard(layout, width) {
            MetricRow("CAN Box TS10", LauncherRuntime.diagnostics.collectAsStateWithLifecycle().value.binderStatus.text(), "QF Vehicle", layout)
            DetailNavigationRow("GMLAN-USB", can.status.text(), layout, onEsp32)
            MetricRow(
                "Root Android",
                stringResource(
                    when (root.status) {
                        RootDetectionStatus.CHECKING -> R.string.root_checking
                        RootDetectionStatus.DETECTED -> R.string.root_detected
                        RootDetectionStatus.POSSIBLE -> R.string.root_possible
                        RootDetectionStatus.NOT_DETECTED -> R.string.root_not_detected
                    },
                ),
                if (root.status == RootDetectionStatus.NOT_DETECTED) stringResource(R.string.root_not_detected_hint) else root.evidence,
                layout,
            )
            MetricRow(
                stringResource(R.string.conn_gps),
                gps.status.text(),
                if (gps.registeredProviders.isEmpty()) {
                    gps.lastError ?: stringResource(R.string.conn_gps_no_provider)
                } else {
                    stringResource(R.string.conn_gps_fixes, gps.registeredProviders.joinToString(), gps.receivedFixes)
                },
                layout,
            )
            DetailNavigationRow(
                if (obd.troubleCodesRead) stringResource(R.string.conn_obd_faults, obd.troubleCodes.size) else "OBD2",
                if (obd.troubleCodesRead) stringResource(R.string.conn_obd_diag_updated) else "ELM327 · ${obd.status.text()}",
                layout,
                onObd2,
            )
            MetricRow(
                stringResource(R.string.conn_tyres),
                tireMonitoringSourceLabel(tires),
                if (tires.usbCandidates.isNotEmpty()) stringResource(R.string.conn_tpms_usb_detected) else "TPMS USB / DDS",
                layout,
            )
        } }
        item { SectionHint(stringResource(R.string.conn_hint), layout) }
    }
}

@Composable
internal fun DisplaySection(layout: LauncherLayout, width: Float, height: Float) {
    val context = LocalContext.current
    val preferences = remember(context) { DashboardPreferences.get(context) }
    val settings by preferences.state.collectAsStateWithLifecycle()
    val autoLabel = stringResource(R.string.settings_language_auto)
    LazyColumn(modifier = Modifier.size(layout.dp(width), layout.dp(height))) {
        item {
            WrappedOptionChoices(
                stringResource(R.string.settings_language),
                AppLanguage.entries,
                settings.language,
                { if (it == AppLanguage.AUTO) autoLabel else it.nativeName },
                preferences::setLanguage,
                layout,
            )
            SectionHint(stringResource(R.string.settings_language_hint), layout)
        }
        item { ThemeModeRowCompact(settings.themeMode, preferences::setThemeMode, layout) }
        item {
            ToggleRow(
                stringResource(R.string.display_3d_view),
                stringResource(R.string.display_3d_view_hint),
                settings.vehicleSceneMode != VehicleSceneMode.TWO_D,
                layout,
            ) { enabled ->
                preferences.setVehicleSceneMode(
                    if (enabled) VehicleSceneMode.THREE_D else VehicleSceneMode.TWO_D,
                )
            }
        }
        // Each scene has its own colour options; the driving zoom applies to both.
        if (settings.vehicleSceneMode == VehicleSceneMode.THREE_D) {
            item { VehiclePaintRow(settings.vehiclePaint, preferences::setVehiclePaint, layout) }
        } else {
            item { VehicleColorRowCompact(settings.vehicleColorMode, preferences::setVehicleColorMode, layout) }
        }
        item { ZoomRowCompact(settings.drivingZoomMode, preferences::setDrivingZoomMode, layout) }
        item { ToggleRow(stringResource(R.string.display_online_weather), stringResource(R.string.display_online_weather_hint), settings.onlineWeatherEnabled, layout, preferences::setOnlineWeatherEnabled) }
    }
}

@Composable
internal fun ApplicationsSection(
    layout: LauncherLayout,
    width: Float,
    height: Float,
    onPicker: (ApplicationPicker) -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember(context) { DashboardPreferences.get(context) }
    val settings by preferences.state.collectAsStateWithLifecycle()
    val applications = rememberInstalledApplications()
    LazyColumn(modifier = Modifier.size(layout.dp(width), layout.dp(height))) {
        item { SettingsCard(layout, width) {
            DetailNavigationRow(stringResource(R.string.apps_navigation), applicationLabel(applications, settings.navigationPackage, "Google Maps"), layout) { onPicker(ApplicationPicker.NAVIGATION) }
            DetailNavigationRow(stringResource(R.string.apps_voice_assistant), applicationLabel(applications, settings.assistantPackage, stringResource(R.string.apps_default_android)), layout) { onPicker(ApplicationPicker.ASSISTANT) }
            DetailNavigationRow(stringResource(R.string.apps_media), applicationLabel(applications, settings.mediaPackage, stringResource(R.string.apps_active_source)), layout) { onPicker(ApplicationPicker.MEDIA) }
            DetailNavigationRow(stringResource(R.string.apps_camera), cameraLabelForPanel(applications, settings, stringResource(R.string.apps_usb_camera), stringResource(R.string.apps_select)), layout) { onPicker(ApplicationPicker.CAMERA) }
            DetailNavigationRow(stringResource(R.string.apps_media_apps), stringResource(R.string.apps_selected_count, settings.mediaPackages.size), layout) { onPicker(ApplicationPicker.MEDIA_APPS) }
            DetailNavigationRow(stringResource(R.string.apps_favourites), "${settings.favoritePackages.size}/${DashboardPreferences.MAX_FAVORITES}", layout) { onPicker(ApplicationPicker.FAVORITES) }
            DetailNavigationRow(stringResource(R.string.apps_right_panel), stringResource(R.string.apps_selected_count, settings.panelPackages.size), layout) { onPicker(ApplicationPicker.PANEL_APPS) }
        } }
        item {
            ToggleRow(
                stringResource(R.string.apps_fused_location),
                stringResource(R.string.apps_fused_location_hint),
                settings.fusedLocationEnabled,
                layout,
                preferences::setFusedLocationEnabled,
            )
        }
        item { SectionHint(stringResource(R.string.apps_fused_location_off), layout) }
    }
}

@Composable
internal fun SystemSection(
    layout: LauncherLayout,
    width: Float,
    height: Float,
    onAdvanced: () -> Unit,
    onDiagnostics: () -> Unit,
    onProfiles: () -> Unit,
    onGmlanUpdate: () -> Unit,
    onLauncherUpdate: () -> Unit,
    onVehicleProfile: () -> Unit,
) {
    val context = LocalContext.current
    val profiles by DriverProfilePreferences.get(context).state.collectAsStateWithLifecycle()
    LazyColumn(modifier = Modifier.size(layout.dp(width), layout.dp(height))) {
        item { SettingsCard(layout, width) {
            MetricRow("Astra H Launcher", stringResource(R.string.system_version_label, AppVersion.name, AppVersion.code), stringResource(R.string.system_installed_version), layout)
            MetricRow(stringResource(R.string.system_default_launcher), if (isDefaultLauncher(context)) stringResource(R.string.system_active) else stringResource(R.string.system_select_in_android), stringResource(R.string.system_system), layout)
            MetricRow(stringResource(R.string.system_driver_profile), profiles.activeProfile.name, stringResource(R.string.system_driver_profile_hint), layout)
            MetricRow(stringResource(R.string.system_data_architecture), stringResource(R.string.system_decoupled_sources), "CAN · OBD2 · BLE · USB", layout)
        } }
        item { ActionRow(stringResource(R.string.system_change_driver), stringResource(R.string.system_change_driver_hint), layout, true) { HybridOverlayService.showDriverProfiles(context) } }
        item { ActionRow(stringResource(R.string.system_configure_profiles), stringResource(R.string.system_configure_profiles_hint), layout, true, onProfiles) }
        item { ActionRow(stringResource(R.string.system_select_launcher), stringResource(R.string.system_select_launcher_hint), layout, true) { openDefaultLauncherSelector(context) } }
        item { ActionRow(stringResource(R.string.system_android_settings), stringResource(R.string.system_android_settings_hint), layout, true) { openAndroidSystemSettings(context) } }
        item { ActionRow(stringResource(R.string.system_diagnostics), stringResource(R.string.system_diagnostics_hint), layout, true, onDiagnostics) }
        item { ActionRow(stringResource(R.string.system_vehicle_profile), stringResource(R.string.system_vehicle_profile_hint), layout, true, onVehicleProfile) }
        item { ActionRow(stringResource(R.string.system_updates), stringResource(R.string.system_updates_hint), layout, true, onLauncherUpdate) }
        item { ActionRow(stringResource(R.string.system_gmlan_update), stringResource(R.string.system_gmlan_update_hint), layout, true, onGmlanUpdate) }
        item { ActionRow(stringResource(R.string.system_advanced), stringResource(R.string.system_advanced_hint), layout, true, onAdvanced) }
    }
}

@Composable
internal fun GmlanFirmwareUpdateDetail(layout: LauncherLayout, width: Float, height: Float) {
    val context = LocalContext.current
    val update by Esp32FirmwareUpdater.state.collectAsStateWithLifecycle()
    val release by GmlanFirmwareUpdateManager.state.collectAsStateWithLifecycle()
    val modeLabels = UpdateMode.entries.associateWith { stringResource(it.label) }
    var showFirstInstallHelp by remember { mutableStateOf(false) }
    LazyColumn(modifier = Modifier.size(layout.dp(width), layout.dp(height))) {
        item { SettingsCard(layout, width) {
            OptionChoices(
                stringResource(R.string.gmlan_github_mode),
                UpdateMode.entries,
                release.mode,
                { modeLabels.getValue(it) },
                { GmlanFirmwareUpdateManager.setMode(context, it) },
                layout,
            )
            SectionHint(stringResource(gmlanModeDescription(release.mode)), layout)
            MetricRow(
                "GitHub",
                if (release.busy && release.progressPercent > 0) "${release.progressPercent}%" else release.status.text(),
                release.availableVersion?.let { stringResource(R.string.gmlan_published_version, it) } ?: stringResource(R.string.gmlan_checked_on_connect),
                layout,
            )
        } }
        item {
            ActionRow(stringResource(R.string.gmlan_search_github), stringResource(R.string.gmlan_search_github_hint), layout, !release.busy && !update.busy) {
                GmlanFirmwareUpdateManager.checkNow(context)
            }
        }
        release.availableVersion?.takeIf { release.downloadedVersion != it }?.let { version ->
            item {
                ActionRow(stringResource(R.string.gmlan_download_version, version), stringResource(R.string.gmlan_download_hint), layout, !release.busy && !update.busy) {
                    GmlanFirmwareUpdateManager.download(context)
                }
            }
        }
        item {
            SectionHint(
                stringResource(R.string.gmlan_intro),
                layout,
            )
        }
        item { SettingsCard(layout, width) {
            MetricRow(stringResource(R.string.gmlan_connection), if (update.usbConnected) stringResource(R.string.gmlan_usb_connected) else stringResource(R.string.gmlan_no_usb), "GMLAN-USB", layout)
            MetricRow(
                stringResource(R.string.gmlan_ota_update),
                when (update.otaSupported) { true -> stringResource(R.string.gmlan_ota_available); false -> stringResource(R.string.gmlan_ota_pending); null -> stringResource(R.string.gmlan_ota_checking) },
                if (update.otaSupported == false) stringResource(R.string.gmlan_first_load_rom) else stringResource(R.string.gmlan_dual_partition),
                layout,
            )
            MetricRow(stringResource(R.string.gmlan_status), update.status.text(), if (update.busy) "${update.progressPercent}%" else "Firmware", layout)
            MetricRow(
                stringResource(R.string.system_installed_version),
                update.installedVersion ?: if (update.otaSupported == false) stringResource(R.string.gmlan_old_firmware) else stringResource(R.string.gmlan_querying),
                stringResource(R.string.gmlan_reported_by_board),
                layout,
            )
            update.candidateName?.let { MetricRow(stringResource(R.string.gmlan_image), it, stringResource(R.string.gmlan_image_hint), layout) }
            update.candidateVersion?.let { MetricRow(stringResource(R.string.gmlan_selected_version), it, stringResource(R.string.gmlan_read_from_image), layout) }
        } }
        item {
            ActionRow(stringResource(R.string.gmlan_select_firmware), stringResource(R.string.gmlan_select_firmware_hint), layout, !update.busy) {
                Esp32FirmwarePickerActivity.launch(context)
            }
        }
        if (update.confirmationPending) {
            item { SettingsCard(layout, width) {
                SectionHint(
                    if (update.otaSupported == false) {
                        stringResource(R.string.gmlan_confirm_first_install)
                    } else {
                        stringResource(R.string.gmlan_confirm_update)
                    },
                    layout,
                )
                MetricRow(stringResource(R.string.gmlan_current_version), update.installedVersion ?: stringResource(R.string.gmlan_unidentified), stringResource(R.string.gmlan_connected_board), layout)
                MetricRow(stringResource(R.string.gmlan_version_to_load), update.candidateVersion ?: "--", stringResource(R.string.gmlan_selected_file), layout)
            } }
            item { ActionRow(
                if (update.otaSupported == false) stringResource(R.string.gmlan_yes_first_install) else stringResource(R.string.gmlan_yes_update),
                stringResource(R.string.gmlan_start_writing),
                layout,
                !update.busy,
            ) { Esp32FirmwareUpdater.confirmInstallation(context) } }
            item { ActionRow(stringResource(R.string.gmlan_no_cancel), stringResource(R.string.gmlan_no_cancel_hint), layout, !update.busy) { Esp32FirmwareUpdater.cancelInstallation() } }
        } else {
            item {
                ActionRow(
                    stringResource(R.string.gmlan_review_update),
                    stringResource(R.string.gmlan_review_update_hint),
                    layout,
                    update.usbConnected && update.candidateVersion != null && update.otaSupported != null && !update.busy,
                    Esp32FirmwareUpdater::requestInstallationConfirmation,
                )
            }
        }
        if (update.otaSupported == false || showFirstInstallHelp) {
            item {
                SettingsCard(layout, width) {
                    MetricRow(stringResource(R.string.gmlan_first_install), stringResource(R.string.gmlan_needed_once), stringResource(R.string.gmlan_no_ota_receiver), layout)
                    SectionHint(
                        stringResource(R.string.gmlan_first_install_help),
                        layout,
                    )
                }
            }
        } else {
            item {
                ActionRow(stringResource(R.string.gmlan_first_install_caps), stringResource(R.string.gmlan_first_install_caps_hint), layout, !update.busy) {
                    showFirstInstallHelp = true
                }
            }
        }
    }
}

@StringRes
private fun gmlanModeDescription(mode: UpdateMode): Int = when (mode) {
    UpdateMode.OFF -> R.string.gmlan_mode_off
    UpdateMode.NOTIFY -> R.string.gmlan_mode_notify
    UpdateMode.DOWNLOAD -> R.string.gmlan_mode_download
    UpdateMode.INSTALL -> R.string.gmlan_mode_install
}

internal enum class ApplicationPicker(@StringRes val title: Int) {
    NAVIGATION(R.string.picker_navigation),
    ASSISTANT(R.string.picker_assistant),
    MEDIA(R.string.picker_media),
    CAMERA(R.string.picker_camera),
    MEDIA_APPS(R.string.picker_media_apps),
    FAVORITES(R.string.picker_favourites),
    PANEL_APPS(R.string.picker_panel_apps),
}

@Composable
internal fun ApplicationsDetail(
    layout: LauncherLayout,
    width: Float,
    height: Float,
    initialPicker: ApplicationPicker?,
    onPickerChanged: (ApplicationPicker?) -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember(context) { DashboardPreferences.get(context) }
    val settings by preferences.state.collectAsStateWithLifecycle()
    val applications = rememberInstalledApplications()
    var picker by remember(initialPicker) { mutableStateOf(initialPicker) }

    LazyColumn(modifier = Modifier.size(layout.dp(width), layout.dp(height))) {
        if (picker != null) {
            item {
                Text(
                    stringResource(R.string.apps_back_to_apps),
                    color = Color(0xFF2E9D50),
                    fontSize = layout.sp(8),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = layout.dp(7)).clickable {
                        picker = null
                        onPickerChanged(null)
                    },
                )
                SectionHint(picker?.let { stringResource(it.title) }.orEmpty(), layout)
            }
            if (picker == ApplicationPicker.CAMERA) {
                item {
                    ApplicationChoiceRow(
                        title = stringResource(R.string.apps_usb_camera),
                        subtitle = stringResource(R.string.apps_usb_camera_hint),
                        selected = settings.cameraMode == DashboardCameraMode.USB,
                        layout = layout,
                        leadingIcon = Icons.Outlined.CameraAlt,
                    ) {
                        preferences.setUsbCamera()
                        picker = null
                        onPickerChanged(null)
                    }
                }
            }
            items(applications.size) { index ->
                val application = applications[index]
                val selected = when (picker) {
                    ApplicationPicker.NAVIGATION -> settings.navigationPackage == application.packageName
                    ApplicationPicker.ASSISTANT -> settings.assistantPackage == application.packageName
                    ApplicationPicker.MEDIA -> settings.mediaPackage == application.packageName
                    ApplicationPicker.CAMERA -> settings.cameraMode == DashboardCameraMode.APPLICATION && settings.cameraPackage == application.packageName
                    ApplicationPicker.MEDIA_APPS -> application.packageName in settings.mediaPackages
                    ApplicationPicker.FAVORITES -> application.packageName in settings.favoritePackages
                    ApplicationPicker.PANEL_APPS -> application.packageName in settings.panelPackages
                    null -> false
                }
                ApplicationChoiceRow(
                    title = application.label,
                    subtitle = application.packageName,
                    selected = selected,
                    layout = layout,
                    application = application,
                ) {
                    when (picker) {
                        ApplicationPicker.NAVIGATION -> preferences.setNavigation(application.packageName)
                        ApplicationPicker.ASSISTANT -> preferences.setAssistant(application.packageName)
                        ApplicationPicker.MEDIA -> preferences.setMedia(application.packageName)
                        ApplicationPicker.CAMERA -> preferences.setCameraApplication(application.packageName)
                        ApplicationPicker.MEDIA_APPS -> preferences.toggleMediaPackage(application.packageName)
                        ApplicationPicker.FAVORITES -> preferences.toggleFavorite(application.packageName)
                        ApplicationPicker.PANEL_APPS -> preferences.togglePanelPackage(application.packageName)
                        null -> Unit
                    }
                    if (picker in setOf(
                            ApplicationPicker.NAVIGATION,
                            ApplicationPicker.ASSISTANT,
                            ApplicationPicker.MEDIA,
                            ApplicationPicker.CAMERA,
                        )
                    ) {
                        picker = null
                        onPickerChanged(null)
                    }
                }
            }
        } else {
            item { SectionHint(stringResource(R.string.apps_pick_category), layout) }
            item { SettingsCard(layout, width) {
                DetailNavigationRow(stringResource(R.string.apps_navigation), applicationLabel(applications, settings.navigationPackage, "Google Maps"), layout) { picker = ApplicationPicker.NAVIGATION; onPickerChanged(picker) }
                DetailNavigationRow(stringResource(R.string.apps_voice_assistant), applicationLabel(applications, settings.assistantPackage, stringResource(R.string.apps_default_android)), layout) { picker = ApplicationPicker.ASSISTANT; onPickerChanged(picker) }
                DetailNavigationRow(stringResource(R.string.apps_media), applicationLabel(applications, settings.mediaPackage, stringResource(R.string.apps_active_source)), layout) { picker = ApplicationPicker.MEDIA; onPickerChanged(picker) }
                DetailNavigationRow(stringResource(R.string.apps_camera), cameraLabelForPanel(applications, settings, stringResource(R.string.apps_usb_camera), stringResource(R.string.apps_select)), layout) { picker = ApplicationPicker.CAMERA; onPickerChanged(picker) }
            } }
            item { SettingsCard(layout, width) {
                DetailNavigationRow(stringResource(R.string.apps_media_apps), stringResource(R.string.apps_selected_count, settings.mediaPackages.size), layout) { picker = ApplicationPicker.MEDIA_APPS; onPickerChanged(picker) }
                DetailNavigationRow(stringResource(R.string.apps_favourites), "${settings.favoritePackages.size}/${DashboardPreferences.MAX_FAVORITES}", layout) { picker = ApplicationPicker.FAVORITES; onPickerChanged(picker) }
                DetailNavigationRow(stringResource(R.string.apps_right_panel), stringResource(R.string.apps_selected_count, settings.panelPackages.size), layout) { picker = ApplicationPicker.PANEL_APPS; onPickerChanged(picker) }
            } }
        }
    }
}

@Composable
internal fun AdvancedDetail(
    layout: LauncherLayout,
    width: Float,
    height: Float,
) {
    val context = LocalContext.current
    val preferences = remember(context) { DashboardPreferences.get(context) }
    val settings by preferences.state.collectAsStateWithLifecycle()
    val directUvc by UvcDirectVisionProbe.state.collectAsStateWithLifecycle()
    val osm by OsmRoadContextProvider.state.collectAsStateWithLifecycle()
    val qfBridge by QfDataShareBridge.state.collectAsStateWithLifecycle()
    LazyColumn(modifier = Modifier.size(layout.dp(width), layout.dp(height))) {
        item { SectionHint(stringResource(R.string.adv_hint), layout) }
        item { SettingsCard(layout, width) {
            DetailNavigationRow(stringResource(R.string.adv_local_vision), stringResource(settings.localVisionMode.label), layout) {
                preferences.setLocalVisionMode(settings.localVisionMode.next())
            }
            DetailNavigationRow(stringResource(R.string.adv_vision_source), stringResource(settings.localVisionSource.label), layout) {
                preferences.setLocalVisionSource(settings.localVisionSource.next())
            }
            if (settings.localVisionSource == LocalVisionSource.DASHCAM) {
                DetailNavigationRow(stringResource(R.string.adv_capture_format), stringResource(settings.localVisionCaptureMode.label), layout) {
                    preferences.setLocalVisionCaptureMode(settings.localVisionCaptureMode.next())
                }
            } else {
                MetricRow(stringResource(R.string.adv_usb_camera), directUvc.status.text(), directUvc.device, layout)
            }
            ToggleRow(
                stringResource(R.string.adv_objects),
                stringResource(R.string.adv_objects_hint),
                settings.localVisionObjectsEnabled,
                layout,
                preferences::setLocalVisionObjectsEnabled,
            )
        } }
        item { SectionHint(stringResource(R.string.adv_swa_blocked), layout) }
        item { SettingsCard(layout, width) {
            ToggleRow(
                stringResource(R.string.adv_emulate_wheel),
                stringResource(R.string.adv_emulate_wheel_hint),
                settings.qfDataShareSteeringEnabled,
                layout,
                preferences::setQfDataShareSteeringEnabled,
            )
            MetricRow(
                stringResource(R.string.adv_swa_wheel),
                qfBridge.status.text(),
                stringResource(R.string.adv_swa_counters, qfBridge.attemptedPackets, qfBridge.sentPackets, qfBridge.lastAngleLevel?.toString() ?: "--", qfBridge.lastHsAngleDegrees?.let { "$it°" } ?: "--", qfBridge.lastQfAngleDegrees?.let { "$it°" } ?: "--"),
                layout,
            )
        } }
        item { SettingsCard(layout, width) {
            MetricRow(stringResource(R.string.adv_road_context), osm.status.text(), stringResource(R.string.adv_osm_ways, osm.cachedWays), layout)
            MetricRow(stringResource(R.string.adv_osm_source), osm.lastSource ?: stringResource(R.string.common_no_data), stringResource(R.string.adv_osm_auto), layout)
            osm.lastError?.let { MetricRow(stringResource(R.string.adv_osm_last_error), it.text(), stringResource(R.string.adv_osm_cache_kept), layout) }
            osm.road?.let { road ->
                MetricRow(stringResource(R.string.adv_road_found), road.name ?: road.reference ?: road.highway, stringResource(R.string.adv_distance_m, "%.0f".format(road.distanceMeters)), layout)
                MetricRow(stringResource(R.string.adv_osm_limit), road.maxSpeedKmh?.let { "$it km/h" } ?: stringResource(R.string.adv_no_maxspeed), stringResource(R.string.adv_way, road.wayId.toString()), layout)
            }
        } }
    }
}

@Composable
internal fun DiagnosticsDetail(layout: LauncherLayout, width: Float, height: Float) {
    val context = LocalContext.current
    val state by LauncherRuntime.diagnostics.collectAsStateWithLifecycle()
    val esp32 by Esp32CanClient.state.collectAsStateWithLifecycle()
    val recentLog = state.log.takeLast(120).asReversed()
    LazyColumn(modifier = Modifier.size(layout.dp(width), layout.dp(height))) {
        item {
            Row(
                modifier = Modifier.padding(bottom = layout.dp(8)),
                horizontalArrangement = Arrangement.spacedBy(layout.dp(16)),
            ) {
                DiagnosticAction(
                    if (state.captureActive) stringResource(R.string.diag_stop) else stringResource(R.string.diag_start),
                    if (state.captureActive) Color(0xFFB53B32) else Color(0xFF2E9D50),
                    layout,
                    if (state.captureActive) LauncherRuntime::stopDiagnosticsCapture else LauncherRuntime::startDiagnosticsCapture,
                )
                DiagnosticAction(stringResource(R.string.diag_save), Color(0xFF3579B8), layout) { QfLogExportActivity.launch(context) }
                DiagnosticAction(stringResource(R.string.diag_clear), Color(0xFF3579B8), layout, LauncherRuntime::clearDiagnosticsLog)
            }
        }
        item { SettingsCard(layout, width) {
            MetricRow(stringResource(R.string.diag_capture), if (state.captureActive) stringResource(R.string.diag_running) else stringResource(R.string.diag_stopped), stringResource(R.string.diag_monitor), layout)
            MetricRow(stringResource(R.string.diag_binder), state.binderStatus.text(), "VehicleService", layout)
            MetricRow(stringResource(R.string.diag_packets), state.packetCount.toString(), state.lastSource, layout)
            MetricRow("GMLAN-USB", esp32.status.text(), stringResource(R.string.diag_frames_rate, esp32.frames.toInt(), esp32.framesPerSecond), layout)
            MetricRow(stringResource(R.string.diag_last_frame), esp32.lastFrame, "USB", layout)
        } }
        item {
            Text(
                stringResource(R.string.diag_recent_events, state.log.size),
                color = LocalDashboardColors.current.muted,
                fontSize = layout.sp(8),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = layout.dp(5)),
            )
        }
        if (recentLog.isEmpty()) {
            item { EmptyState(stringResource(R.string.diag_no_events), stringResource(R.string.diag_no_events_hint), layout) }
        } else {
            items(recentLog.size) { index ->
                val entry = recentLog[index]
                val colors = LocalDashboardColors.current
                Column(modifier = Modifier.padding(vertical = layout.dp(4))) {
                    Text(entry.timestamp, color = colors.muted, fontSize = layout.sp(7))
                    Text(
                        entry.message,
                        color = colors.primary,
                        fontSize = layout.sp(8),
                        lineHeight = layout.sp(10),
                    )
                }
            }
        }
    }
}

@Composable
private fun ApplicationChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    layout: LauncherLayout,
    application: InstalledApplication? = null,
    leadingIcon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val colors = LocalDashboardColors.current
    val painter = application?.let { installed ->
        remember(installed.packageName) {
            BitmapPainter(installed.icon.toBitmap(64, 64).asImageBitmap())
        }
    }
    Row(
        modifier = Modifier
            .teslaControlSurface(colors, layout, selected = selected)
            .clickable(onClick = onClick)
            .padding(horizontal = layout.dp(11), vertical = layout.dp(8)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (painter != null) {
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(layout.dp(31)),
            )
        } else if (leadingIcon != null) {
            Icon(
                leadingIcon,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(layout.dp(31)),
            )
        }
        Column(
            modifier = Modifier
                .padding(start = layout.dp(if (painter != null || leadingIcon != null) 9 else 0))
                .weight(1f),
        ) {
            Text(title, color = colors.primary, fontSize = layout.sp(10), fontWeight = FontWeight.Bold)
            Text(subtitle, color = colors.muted, fontSize = layout.sp(7), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            if (selected) stringResource(R.string.apps_selected_caps) else "",
            color = Color(0xFF2E9D50),
            fontSize = layout.sp(7),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ThemeModeRowCompact(selected: DashboardThemeMode, onSelected: (DashboardThemeMode) -> Unit, layout: LauncherLayout) {
    val labels = mapOf(
        DashboardThemeMode.AUTOMATIC to stringResource(R.string.theme_automatic),
        DashboardThemeMode.LIGHT to stringResource(R.string.theme_light),
        DashboardThemeMode.DARK to stringResource(R.string.theme_dark),
    )
    OptionChoices(stringResource(R.string.display_theme), DashboardThemeMode.entries, selected, { labels.getValue(it) }, onSelected, layout)
}

@Composable
private fun VehicleColorRowCompact(selected: DashboardVehicleColorMode, onSelected: (DashboardVehicleColorMode) -> Unit, layout: LauncherLayout) {
    val labels = mapOf(
        DashboardVehicleColorMode.WHITE to stringResource(R.string.colour_white),
        DashboardVehicleColorMode.RED to stringResource(R.string.colour_red),
        DashboardVehicleColorMode.BLACK to stringResource(R.string.colour_black),
        DashboardVehicleColorMode.THEME to stringResource(R.string.colour_theme),
    )
    OptionChoices(stringResource(R.string.display_car_colour), DashboardVehicleColorMode.entries, selected, { labels.getValue(it) }, onSelected, layout)
}

@Composable
private fun VehiclePaintRow(selected: Vehicle3dPaint, onSelected: (Vehicle3dPaint) -> Unit, layout: LauncherLayout) {
    val colors = LocalDashboardColors.current
    Column(modifier = Modifier.padding(vertical = layout.dp(8))) {
        Text(stringResource(R.string.display_body_colour_3d), color = colors.primary, fontSize = layout.sp(10), fontWeight = FontWeight.Bold)
        Vehicle3dPaint.entries.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.padding(top = layout.dp(3)),
                horizontalArrangement = Arrangement.spacedBy(layout.dp(3)),
            ) { row.forEach { paint -> Choice(stringResource(paint.label), paint == selected, layout) { onSelected(paint) } } }
        }
    }
}

@Composable
private fun ZoomRowCompact(selected: DashboardDrivingZoomMode, onSelected: (DashboardDrivingZoomMode) -> Unit, layout: LauncherLayout) = OptionChoices(stringResource(R.string.display_driving_zoom), DashboardDrivingZoomMode.entries, selected, { it.label }, onSelected, layout)

private fun cameraLabelForPanel(applications: List<InstalledApplication>, settings: DashboardSettings, usbLabel: String, selectLabel: String): String =
    if (settings.cameraMode == DashboardCameraMode.USB) usbLabel else applicationLabel(applications, settings.cameraPackage, selectLabel)
