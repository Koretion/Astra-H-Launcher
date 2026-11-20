package com.ts10.inspector.settings

import com.ts10.inspector.ui.UiText
import com.ts10.inspector.ui.text
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BluetoothDisabled
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.R
import com.ts10.inspector.ui.DashboardAssetIcon
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.ui.LocalDashboardColors
import com.ts10.inspector.vehicle.formatManualOdometerKm
import com.ts10.inspector.vehicle.obd.Obd2Client
import com.ts10.inspector.vehicle.obd.ObdConnectionState
import com.ts10.inspector.vehicle.parseManualOdometerKm

@Composable
internal fun Obd2SettingsPanel(
    layout: LauncherLayout,
    width: Float,
    height: Float,
) {
    val context = LocalContext.current
    val colors = LocalDashboardColors.current
    val state by Obd2Client.state.collectAsStateWithLifecycle()
    val dashboardPreferences = remember(context) { DashboardPreferences.get(context) }
    val dashboardSettings by dashboardPreferences.state.collectAsStateWithLifecycle()
    var odometerInput by remember(dashboardSettings.manualOdometerKm) {
        mutableStateOf(formatManualOdometerKm(dashboardSettings.manualOdometerKm))
    }
    var odometerMessage by remember { mutableStateOf<String?>(null) }
    var odometerError by remember { mutableStateOf(false) }

    LaunchedEffect(state.connection) {
        if (state.connection == ObdConnectionState.CONNECTED) Obd2Client.ensureTroubleCodesRead()
    }

    LazyColumn(modifier = Modifier.size(layout.dp(width), layout.dp(height))) {
        item {
            Column(modifier = Modifier.padding(bottom = layout.dp(8))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DashboardAssetIcon(
                        drawable = R.drawable.icon_obd,
                        contentDescription = null,
                        tint = colors.muted,
                        modifier = Modifier.size(layout.dp(20)),
                    )
                    Text(
                        stringResource(R.string.obd_adapter),
                        color = colors.muted,
                        fontSize = layout.sp(9),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = layout.dp(7)),
                    )
                }
                Text(
                    state.status.text(),
                    color = connectionColor(state.connection, colors.muted),
                    fontSize = layout.sp(10),
                    modifier = Modifier.padding(top = layout.dp(3)),
                )
            }
        }
        if (state.connection == ObdConnectionState.PERMISSION_REQUIRED) {
            item {
                Text(
                    stringResource(R.string.obd_permission_hint),
                    color = Color(0xFFE0A020),
                    fontSize = layout.sp(10),
                    modifier = Modifier.padding(vertical = layout.dp(8)),
                )
            }
        }
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = layout.dp(5))) {
                OutlinedTextField(
                    value = odometerInput,
                    onValueChange = { value ->
                        odometerInput = value
                            .filter { it.isDigit() || it == ',' || it == '.' }
                            .take(MAX_ODOMETER_INPUT_LENGTH)
                        odometerMessage = null
                        odometerError = false
                    },
                    label = { Text(stringResource(R.string.obd_odometer_label), fontSize = layout.sp(9)) },
                    placeholder = { Text(stringResource(R.string.obd_odometer_example), fontSize = layout.sp(9)) },
                    supportingText = {
                        Text(
                            odometerMessage ?: stringResource(R.string.obd_odometer_hint),
                            fontSize = layout.sp(8),
                        )
                    },
                    isError = odometerError,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = layout.sp(10)),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val parsed = parseManualOdometerKm(odometerInput)
                        if (odometerInput.isBlank()) {
                            dashboardPreferences.setManualOdometerKm(null)
                            odometerError = false
                            odometerMessage = context.getString(R.string.obd_odometer_cleared)
                        } else if (parsed == null) {
                            odometerError = true
                            odometerMessage = context.getString(R.string.obd_odometer_invalid)
                        } else {
                            dashboardPreferences.setManualOdometerKm(parsed)
                            odometerInput = formatManualOdometerKm(parsed)
                            odometerError = false
                            odometerMessage = context.getString(R.string.obd_odometer_saved, formatManualOdometerKm(parsed))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary.copy(alpha = if (colors.dark) .10f else .07f),
                        contentColor = Color(0xFF2E9D50),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (odometerInput.isBlank()) stringResource(R.string.obd_clear_reference) else stringResource(R.string.obd_save_reference),
                        fontSize = layout.sp(9),
                    )
                }
            }
        }
        item {
            BluetoothActionRow(
                icon = Icons.Outlined.Refresh,
                title = stringResource(R.string.obd_refresh_devices),
                subtitle = stringResource(R.string.obd_refresh_devices_hint),
                layout = layout,
            ) { Obd2Client.refreshPairedDevices() }
        }
        item {
            BluetoothActionRow(
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                title = stringResource(R.string.obd_pair_android),
                subtitle = stringResource(R.string.obd_pair_android_hint),
                layout = layout,
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        if (state.selectedAddress != null) {
            item {
                BluetoothActionRow(
                    icon = if (state.connection == ObdConnectionState.CONNECTED) {
                        Icons.Outlined.BluetoothDisabled
                    } else {
                        Icons.Outlined.Bluetooth
                    },
                    title = if (state.connection == ObdConnectionState.CONNECTED ||
                        state.connection == ObdConnectionState.CONNECTING
                    ) {
                        stringResource(R.string.obd_disconnect)
                    } else {
                        stringResource(R.string.obd_connect, state.selectedName ?: "ELM327")
                    },
                    subtitle = if (state.connection == ObdConnectionState.CONNECTED) {
                        stringResource(R.string.obd_live_active)
                    } else {
                        stringResource(R.string.obd_use_selected)
                    },
                    layout = layout,
                ) {
                    if (state.connection == ObdConnectionState.CONNECTED ||
                        state.connection == ObdConnectionState.CONNECTING
                    ) {
                        Obd2Client.disconnect()
                    } else {
                        Obd2Client.connect()
                    }
                }
            }
        }
        item {
            BluetoothActionRow(
                icon = Icons.Outlined.Info,
                title = if (state.busy) stringResource(R.string.obd_test_running) else stringResource(R.string.obd_capability_test),
                subtitle = stringResource(R.string.obd_capability_test_hint),
                layout = layout,
                enabled = state.connection == ObdConnectionState.CONNECTED && !state.busy,
            ) { Obd2Client.runCapabilityTest() }
        }
        item {
            BluetoothActionRow(
                icon = Icons.Outlined.Info,
                title = if (state.busy) stringResource(R.string.obd_test_running) else stringResource(R.string.obd_dds_test),
                subtitle = stringResource(R.string.obd_dds_test_hint),
                layout = layout,
                enabled = state.connection == ObdConnectionState.CONNECTED && !state.busy,
            ) { Obd2Client.runWheelSpeedTest() }
        }
        state.wheelSpeedTestStatus?.let { result ->
            item {
                Column(modifier = Modifier.padding(top = layout.dp(7), bottom = layout.dp(4))) {
                    Text(
                        stringResource(R.string.obd_dds_result),
                        color = colors.muted,
                        fontSize = layout.sp(8),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        result.text(),
                        color = if ((result as? UiText.Res)?.id == R.string.obd_dds_global_only) Color(0xFFE0A020) else colors.primary,
                        fontSize = layout.sp(9),
                        modifier = Modifier.padding(top = layout.dp(3)),
                    )
                }
            }
        }
        if (state.capabilityResults.isNotEmpty() || state.capabilityTestCompleted) {
            item {
                Text(
                    stringResource(R.string.obd_capability_results),
                    color = colors.muted,
                    fontSize = layout.sp(8),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = layout.dp(9), bottom = layout.dp(4)),
                )
            }
            items(state.capabilityResults.size) { index ->
                val result = state.capabilityResults[index]
                Column(
                    modifier = Modifier
                        .size(layout.dp(width), layout.dp(45))
                        .padding(vertical = layout.dp(3)),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            result.command,
                            color = if (result.available) Color(0xFF2E9D50) else Color(0xFFE0A020),
                            fontSize = layout.sp(9),
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(result.title),
                            color = colors.primary,
                            fontSize = layout.sp(10),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = layout.dp(8)),
                        )
                    }
                    Text(
                        result.summary.text(),
                        color = colors.muted,
                        fontSize = layout.sp(8),
                        maxLines = 2,
                    )
                }
            }
        }
        item {
            Text(
                stringResource(R.string.obd_paired_devices),
                color = colors.muted,
                fontSize = layout.sp(8),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = layout.dp(9), bottom = layout.dp(4)),
            )
        }
        if (state.pairedDevices.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.obd_no_devices),
                    color = colors.muted,
                    fontSize = layout.sp(10),
                    modifier = Modifier.padding(vertical = layout.dp(10)),
                )
            }
        }
        items(state.pairedDevices.size) { index ->
            val device = state.pairedDevices[index]
            val selected = device.address == state.selectedAddress
            Row(
                modifier = Modifier
                    .padding(vertical = layout.dp(3))
                    .fillMaxWidth()
                    .height(layout.dp(48))
                    .background(
                        colors.primary.copy(alpha = if (colors.dark) .09f else .065f),
                        RoundedCornerShape(layout.dp(5)),
                    )
                    .border(
                        layout.dp(1),
                        if (selected) Color(0xFF2E9D50).copy(alpha = .65f) else colors.divider.copy(alpha = if (colors.dark) .28f else .16f),
                        RoundedCornerShape(layout.dp(5)),
                    )
                    .clickable { Obd2Client.selectDevice(device) }
                    .padding(horizontal = layout.dp(12)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Bluetooth,
                    null,
                    tint = if (selected) Color(0xFF2E9D50) else colors.primary,
                    modifier = Modifier.size(layout.dp(24)),
                )
                Column(modifier = Modifier.padding(start = layout.dp(12)).weight(1f)) {
                    Text(
                        device.name,
                        color = colors.primary,
                        fontSize = layout.sp(11),
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text(device.address, color = colors.muted, fontSize = layout.sp(8))
                }
                if (selected) {
                    Text(stringResource(R.string.esp32_selected), color = Color(0xFF2E9D50), fontSize = layout.sp(8))
                }
            }
        }
    }
}

@Composable
internal fun BluetoothActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    layout: LauncherLayout,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = LocalDashboardColors.current
    Row(
        modifier = Modifier
            .padding(vertical = layout.dp(3))
            .fillMaxWidth()
            .height(layout.dp(47))
            .background(
                colors.primary.copy(alpha = if (colors.dark) .09f else .065f),
                RoundedCornerShape(layout.dp(5)),
            )
            .border(
                layout.dp(1),
                colors.divider.copy(alpha = if (colors.dark) .28f else .16f),
                RoundedCornerShape(layout.dp(5)),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = layout.dp(12)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (enabled) colors.primary else colors.muted, modifier = Modifier.size(layout.dp(25)))
        Column(modifier = Modifier.padding(start = layout.dp(13)).weight(1f)) {
            Text(title, color = if (enabled) colors.primary else colors.muted, fontSize = layout.sp(12), fontWeight = FontWeight.Bold)
            Text(subtitle, color = colors.muted, fontSize = layout.sp(9), maxLines = 1)
        }
    }
}

private fun connectionColor(connection: ObdConnectionState, muted: Color): Color = when (connection) {
    ObdConnectionState.CONNECTED -> Color(0xFF2E9D50)
    ObdConnectionState.ERROR, ObdConnectionState.PERMISSION_REQUIRED -> Color(0xFFE0A020)
    else -> muted
}

private const val MAX_ODOMETER_INPUT_LENGTH = 11
