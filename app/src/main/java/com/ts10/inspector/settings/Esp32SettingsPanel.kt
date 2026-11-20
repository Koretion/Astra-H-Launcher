package com.ts10.inspector.settings

import com.ts10.inspector.ui.text
import com.ts10.inspector.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BluetoothDisabled
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.ui.LocalDashboardColors
import com.ts10.inspector.vehicle.can.Esp32BleCandidate
import com.ts10.inspector.vehicle.can.Esp32BleTransport
import com.ts10.inspector.vehicle.can.Esp32CanClient

/** Finds the ESP32 CAN boards advertising over BLE and remembers the one to use. */
@Composable
internal fun Esp32SettingsPanel(
    layout: LauncherLayout,
    width: Float,
    height: Float,
) {
    val colors = LocalDashboardColors.current
    val can by Esp32CanClient.state.collectAsStateWithLifecycle()
    val discovery by Esp32BleTransport.discovery.collectAsStateWithLifecycle()
    val usb = can.streamMode.startsWith("USB")

    LazyColumn(modifier = Modifier.size(layout.dp(width), layout.dp(height))) {
        item {
            Column(modifier = Modifier.padding(bottom = layout.dp(8))) {
                Text(
                    stringResource(R.string.esp32_title),
                    color = colors.muted,
                    fontSize = layout.sp(9),
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    can.status.text(),
                    color = if (can.connected) Color(0xFF2E9D50) else colors.muted,
                    fontSize = layout.sp(10),
                    modifier = Modifier.padding(top = layout.dp(3)),
                )
                Text(
                    listOfNotNull(
                        can.streamMode.takeIf { it != "--" },
                        can.deviceName,
                        can.deviceAddress?.takeIf { it != "USB" },
                    ).joinToString(" · ").ifEmpty { stringResource(R.string.esp32_not_connected) },
                    color = colors.muted,
                    fontSize = layout.sp(8),
                )
            }
        }
        if (usb) {
            item {
                Text(
                    stringResource(R.string.esp32_usb_hint),
                    color = colors.muted,
                    fontSize = layout.sp(9),
                    modifier = Modifier.padding(vertical = layout.dp(4)),
                )
            }
        }
        item {
            BluetoothActionRow(
                icon = Icons.AutoMirrored.Outlined.BluetoothSearching,
                title = if (discovery.searching) stringResource(R.string.esp32_searching_title) else stringResource(R.string.esp32_search_title),
                subtitle = stringResource(R.string.esp32_search_hint),
                layout = layout,
                enabled = !discovery.searching,
            ) { Esp32BleTransport.startDiscovery() }
        }
        if (discovery.savedAddress != null) {
            item {
                BluetoothActionRow(
                    icon = Icons.Outlined.BluetoothDisabled,
                    title = stringResource(R.string.esp32_forget, discovery.savedName ?: "ESP32"),
                    subtitle = stringResource(R.string.esp32_forget_hint),
                    layout = layout,
                ) { Esp32BleTransport.forgetDevice() }
            }
        }
        item {
            Text(
                stringResource(R.string.esp32_found),
                color = colors.muted,
                fontSize = layout.sp(8),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = layout.dp(9), bottom = layout.dp(4)),
            )
        }
        val devices = discovery.devices.ifEmpty {
            listOfNotNull(
                discovery.savedAddress?.let { Esp32BleCandidate(discovery.savedName ?: "GMLAN-USB", it, rssi = 0) },
            )
        }
        if (devices.isEmpty()) {
            item {
                Text(
                    if (discovery.searching) stringResource(R.string.esp32_searching) else stringResource(R.string.esp32_press_search),
                    color = colors.muted,
                    fontSize = layout.sp(10),
                    modifier = Modifier.padding(vertical = layout.dp(10)),
                )
            }
        }
        items(devices.size) { index ->
            val device = devices[index]
            val selected = device.address == discovery.savedAddress
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
                    .clickable { Esp32BleTransport.selectDevice(device) }
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
                    Text(
                        if (device.rssi != 0) "${device.address} · ${device.rssi} dBm" else device.address,
                        color = colors.muted,
                        fontSize = layout.sp(8),
                    )
                }
                if (selected) {
                    Text(stringResource(R.string.esp32_selected), color = Color(0xFF2E9D50), fontSize = layout.sp(8))
                }
            }
        }
    }
}
