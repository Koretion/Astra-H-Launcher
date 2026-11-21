package com.ts10.inspector.settings

import com.ts10.inspector.R
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.dashboard.VehicleSceneFocus
import com.ts10.inspector.dashboard.VehicleSceneFocusRequest
import com.ts10.inspector.ui.DesignGrid
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.ui.LocalDashboardColors

private enum class ControlSection(@StringRes val title: Int, val icon: ImageVector) {
    OVERVIEW(R.string.settings_overview, Icons.Outlined.Home),
    TRIPS(R.string.settings_trips, Icons.Outlined.History),
    ENGINE(R.string.settings_engine, Icons.Outlined.Speed),
    BODY(R.string.settings_body, Icons.Outlined.DirectionsCar),
    COMFORT(R.string.settings_comfort, Icons.Outlined.AcUnit),
    ENERGY(R.string.settings_energy, Icons.Outlined.BatteryStd),
    TPMS(R.string.settings_tpms, Icons.Outlined.Warning),
    CONNECTIONS(R.string.settings_connections, Icons.Outlined.Bluetooth),
    DISPLAY(R.string.settings_display, Icons.Outlined.Palette),
    APPLICATIONS(R.string.settings_applications, Icons.Outlined.Apps),
    SYSTEM(R.string.settings_system, Icons.Outlined.Settings),
}

private enum class ControlDetail(@StringRes val title: Int) {
    OBD2(R.string.settings_detail_obd2),
    ESP32(R.string.settings_detail_esp32),
    TRACKING(R.string.settings_detail_tracking),
    APPLICATIONS(R.string.settings_detail_applications),
    ADVANCED(R.string.settings_detail_advanced),
    DIAGNOSTICS(R.string.settings_detail_diagnostics),
    PROFILES(R.string.settings_detail_profiles),
    GMLAN_UPDATE(R.string.settings_detail_gmlan_update),
    LAUNCHER_UPDATE(R.string.settings_detail_launcher_update),
    VEHICLE_PROFILE(R.string.settings_detail_vehicle_profile),
}

private object SettingsNavigationSession {
    var section: ControlSection = ControlSection.OVERVIEW
    var detail: ControlDetail? = null
    var applicationPicker: ApplicationPicker? = null

    data class Snapshot(
        val section: ControlSection,
        val detail: ControlDetail?,
        val applicationPicker: ApplicationPicker?,
    )

    fun restore(context: android.content.Context): Snapshot {
        val preferences = context.getSharedPreferences(PREFERENCES_FILE, android.content.Context.MODE_PRIVATE)
        section = preferences.getString(KEY_SECTION, null)
            ?.let { value -> runCatching { ControlSection.valueOf(value) }.getOrNull() }
            ?: section
        detail = preferences.getString(KEY_DETAIL, null)
            ?.let { value -> runCatching { ControlDetail.valueOf(value) }.getOrNull() }
            ?: detail
        applicationPicker = preferences.getString(KEY_APPLICATION_PICKER, null)
            ?.let { value -> runCatching { ApplicationPicker.valueOf(value) }.getOrNull() }
            ?: applicationPicker
        return Snapshot(section, detail, applicationPicker)
    }

    fun save(
        context: android.content.Context,
        section: ControlSection,
        detail: ControlDetail?,
        applicationPicker: ApplicationPicker?,
    ) {
        this.section = section
        this.detail = detail
        this.applicationPicker = applicationPicker
        context.getSharedPreferences(PREFERENCES_FILE, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SECTION, section.name)
            .putString(KEY_DETAIL, detail?.name)
            .putString(KEY_APPLICATION_PICKER, applicationPicker?.name)
            .apply()
    }

    private const val PREFERENCES_FILE = "settings_navigation"
    private const val KEY_SECTION = "section"
    private const val KEY_DETAIL = "detail"
    private const val KEY_APPLICATION_PICKER = "application_picker"
}

@Composable
internal fun DashboardSettingsOverlay(layout: LauncherLayout, onDismiss: () -> Unit) {
    val colors = LocalDashboardColors.current
    val context = LocalContext.current
    // The TS10 is normally viewed at arm's length. Keep the established grid and touch targets,
    // but make every label in this overlay legible without enlarging the dashboard itself.
    val settingsLayout = layout.copy(textScale = 1.18f)
    val profiles by remember(context) { DriverProfilePreferences.get(context) }.state.collectAsStateWithLifecycle()
    val restoredNavigation = remember(context) { SettingsNavigationSession.restore(context) }
    var section by remember { mutableStateOf(restoredNavigation.section) }
    var detail by remember { mutableStateOf(restoredNavigation.detail) }
    var requestedApplicationPicker by remember {
        mutableStateOf(restoredNavigation.applicationPicker)
    }
    val menuWidth = 132f
    LaunchedEffect(section) {
        VehicleSceneFocusRequest.set(
            when (section) {
                ControlSection.ENGINE -> VehicleSceneFocus.ENGINE
                ControlSection.COMFORT -> VehicleSceneFocus.CABIN
                ControlSection.ENERGY -> VehicleSceneFocus.FUEL
                ControlSection.TPMS -> VehicleSceneFocus.TIRES
                else -> VehicleSceneFocus.NONE
            },
        )
    }
    DisposableEffect(Unit) {
        onDispose { VehicleSceneFocusRequest.set(VehicleSceneFocus.NONE) }
    }

    fun saveNavigation(
        nextSection: ControlSection = section,
        nextDetail: ControlDetail? = detail,
        nextPicker: ApplicationPicker? = requestedApplicationPicker,
    ) {
        SettingsNavigationSession.save(context, nextSection, nextDetail, nextPicker)
    }

    fun dismissRememberingPosition() {
        saveNavigation()
        onDismiss()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().clickable(onClick = ::dismissRememberingPosition)) {
        // The launcher design scales from 800 x 469 by height. On wider 16:9 screens that
        // leaves extra real width, so derive the right edge from the actual constraints.
        val panelWidth = maxWidth.value / layout.scale - DesignGrid.VehiclePanelWidth
        // maxHeight already represents the area below Android's status bar. Use all of it for
        // settings instead of reserving the launcher's own top-indicator strip a second time.
        val panelHeight = maxHeight.value / layout.scale
        Row(
            modifier = Modifier
                .offset(x = layout.dp(DesignGrid.VehiclePanelWidth))
                .size(layout.dp(panelWidth), layout.dp(panelHeight))
                .background(colors.modalBackground)
                .clickable { },
        ) {
            Column(
                modifier = Modifier
                    .size(layout.dp(menuWidth), layout.dp(panelHeight))
                    .background(if (colors.dark) Color(0xFF0B0D0F) else Color(0xFFE9E8E5))
                    .padding(vertical = layout.dp(8)),
            ) {
                Text(
                    stringResource(R.string.settings_controls),
                    color = colors.muted,
                    fontSize = settingsLayout.sp(8),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = layout.dp(13), bottom = layout.dp(5)),
                )
                LazyColumn(modifier = Modifier.size(layout.dp(menuWidth), layout.dp(panelHeight - 25f))) {
                    items(ControlSection.entries.size) { index ->
                        val item = ControlSection.entries[index]
                        ControlMenuItem(item, item == section, settingsLayout) {
                            section = item
                            detail = null
                            requestedApplicationPicker = null
                            saveNavigation(item, null, null)
                        }
                    }
                }
            }
            Column(
                modifier = Modifier
                    .size(layout.dp(panelWidth - menuWidth), layout.dp(panelHeight))
                    .padding(horizontal = layout.dp(16), vertical = layout.dp(10)),
            ) {
                Row(
                    modifier = Modifier.size(layout.dp(panelWidth - menuWidth - 32f), layout.dp(28)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (detail != null) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            stringResource(R.string.common_back),
                            tint = colors.primary,
                            modifier = Modifier
                                .padding(end = layout.dp(9))
                                .size(layout.dp(23))
                                .clickable {
                                    detail = null
                                    saveNavigation(nextDetail = null)
                                },
                        )
                    } else {
                        Box(modifier = Modifier.size(layout.dp(23)))
                    }
                    Box(modifier = Modifier.weight(1f))
                    Row(
                        modifier = Modifier
                            .padding(end = layout.dp(10))
                            .background(colors.primary.copy(alpha = if (colors.dark) .08f else .05f), RoundedCornerShape(layout.dp(15)))
                            .clickable {
                                detail = ControlDetail.PROFILES
                                requestedApplicationPicker = null
                                saveNavigation(nextDetail = ControlDetail.PROFILES, nextPicker = null)
                            }
                            .padding(horizontal = layout.dp(8), vertical = layout.dp(5)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Person,
                            contentDescription = stringResource(R.string.settings_configure_profile, profiles.activeProfile.name),
                            tint = Color(profiles.activeProfile.iconColor.argb),
                            modifier = Modifier.size(layout.dp(18)),
                        )
                        Text(
                            profiles.activeProfile.name,
                            color = colors.primary,
                            fontSize = settingsLayout.sp(9),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = layout.dp(5)).width(layout.dp(72)),
                        )
                    }
                    Icon(
                        Icons.Outlined.Close,
                        stringResource(R.string.common_close),
                        tint = colors.primary,
                        modifier = Modifier.size(layout.dp(25)).clickable(onClick = ::dismissRememberingPosition),
                    )
                }
                Text(
                    stringResource(detail?.title ?: section.title),
                    color = colors.primary,
                    fontSize = settingsLayout.sp(17),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .size(layout.dp(panelWidth - menuWidth - 32f), layout.dp(32))
                        .padding(top = layout.dp(2)),
                )
                val contentWidth = panelWidth - menuWidth - 32f
                val contentHeight = panelHeight - 80f
                when (detail) {
                    ControlDetail.OBD2 -> Obd2SettingsPanel(settingsLayout, contentWidth, contentHeight)
                    ControlDetail.ESP32 -> Esp32SettingsPanel(settingsLayout, contentWidth, contentHeight)
                    ControlDetail.TRACKING -> VehicleTrackingSettingsPanel(settingsLayout, contentWidth, contentHeight)
                    ControlDetail.APPLICATIONS -> ApplicationsDetail(
                        settingsLayout,
                        contentWidth,
                        contentHeight,
                        requestedApplicationPicker,
                        onPickerChanged = { requestedApplicationPicker = it },
                    )
                    ControlDetail.ADVANCED -> AdvancedDetail(settingsLayout, contentWidth, contentHeight)
                    ControlDetail.DIAGNOSTICS -> DiagnosticsDetail(settingsLayout, contentWidth, contentHeight)
                    ControlDetail.PROFILES -> DriverProfilesSettingsPanel(settingsLayout, contentWidth, contentHeight)
                    ControlDetail.GMLAN_UPDATE -> GmlanFirmwareUpdateDetail(settingsLayout, contentWidth, contentHeight)
                    ControlDetail.LAUNCHER_UPDATE -> LauncherUpdateDetail(settingsLayout, contentWidth, contentHeight)
                    ControlDetail.VEHICLE_PROFILE -> VehicleProfileDetail(settingsLayout, contentWidth, contentHeight)
                    null -> SettingsSectionContent(
                        section = section,
                        layout = settingsLayout,
                        width = contentWidth,
                        height = contentHeight,
                        onOpenDetail = {
                            detail = it
                            saveNavigation(nextDetail = it)
                        },
                        onOpenApplicationPicker = { picker ->
                            requestedApplicationPicker = picker
                            detail = ControlDetail.APPLICATIONS
                            saveNavigation(nextDetail = ControlDetail.APPLICATIONS, nextPicker = picker)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlMenuItem(
    section: ControlSection,
    selected: Boolean,
    layout: LauncherLayout,
    onClick: () -> Unit,
) {
    val colors = LocalDashboardColors.current
    Row(
        modifier = Modifier
            .size(layout.dp(132), layout.dp(35))
            .background(if (selected) colors.primary.copy(alpha = if (colors.dark) .12f else .08f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = layout.dp(12)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            section.icon,
            null,
            tint = if (selected) Color(0xFF2E9D50) else colors.muted,
            modifier = Modifier.size(layout.dp(18)),
        )
        Text(
            stringResource(section.title),
            color = if (selected) colors.primary else colors.muted,
            fontSize = layout.sp(10),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(start = layout.dp(9)),
        )
    }
}

@Composable
private fun SettingsSectionContent(
    section: ControlSection,
    layout: LauncherLayout,
    width: Float,
    height: Float,
    onOpenDetail: (ControlDetail) -> Unit,
    onOpenApplicationPicker: (ApplicationPicker) -> Unit,
) {
    when (section) {
        ControlSection.OVERVIEW -> OverviewSection(layout, width, height)
        ControlSection.TRIPS -> TripsSection(layout, width, height) { onOpenDetail(ControlDetail.TRACKING) }
        ControlSection.ENGINE -> EngineSection(layout, width, height)
        ControlSection.BODY -> BodySection(layout, width, height)
        ControlSection.COMFORT -> ComfortSection(layout, width, height)
        ControlSection.ENERGY -> EnergySection(layout, width, height)
        ControlSection.TPMS -> TpmsSection(layout, width, height)
        ControlSection.CONNECTIONS -> ConnectionsSection(
            layout,
            width,
            height,
            onObd2 = { onOpenDetail(ControlDetail.OBD2) },
            onEsp32 = { onOpenDetail(ControlDetail.ESP32) },
        )
        ControlSection.DISPLAY -> DisplaySection(layout, width, height)
        ControlSection.APPLICATIONS -> ApplicationsSection(layout, width, height, onOpenApplicationPicker)
        ControlSection.SYSTEM -> SystemSection(
            layout,
            width,
            height,
            onAdvanced = { onOpenDetail(ControlDetail.ADVANCED) },
            onDiagnostics = { onOpenDetail(ControlDetail.DIAGNOSTICS) },
            onProfiles = { onOpenDetail(ControlDetail.PROFILES) },
            onGmlanUpdate = { onOpenDetail(ControlDetail.GMLAN_UPDATE) },
            onLauncherUpdate = { onOpenDetail(ControlDetail.LAUNCHER_UPDATE) },
            onVehicleProfile = { onOpenDetail(ControlDetail.VEHICLE_PROFILE) },
        )
    }
}
