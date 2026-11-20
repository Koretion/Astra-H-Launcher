package com.ts10.inspector.phone

import com.ts10.inspector.ui.text
import com.ts10.inspector.R
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.ui.LocalDashboardColors
import com.ts10.inspector.ui.modalCornerShape
import com.ts10.inspector.ui.rightPanelModalFrame

@Composable
internal fun PhoneOverlay(
    layout: LauncherLayout,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalDashboardColors.current
    val phoneState by AndroidBluetoothPhoneClient.state.collectAsStateWithLifecycle()
    var number by remember { mutableStateOf("") }
    var contactMode by remember { mutableStateOf(false) }
    val frame = rightPanelModalFrame(width = 460f, height = 360f, top = 58f)

    LaunchedEffect(Unit) {
        AndroidBluetoothPhoneClient.connect(context)
        AndroidBluetoothPhoneClient.refresh()
    }
    LaunchedEffect(phoneState.callState, phoneState.remoteNumber) {
        if (phoneState.callState != BluetoothCallState.IDLE && phoneState.remoteNumber.isNotBlank()) {
            number = phoneState.remoteNumber
        }
    }

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
                .padding(horizontal = layout.dp(18), vertical = layout.dp(10)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.size(layout.dp(374), layout.dp(34)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Bluetooth,
                    contentDescription = null,
                    tint = if (phoneState.connected) Color(0xFF278844) else colors.muted,
                    modifier = Modifier.size(layout.dp(22)),
                )
                Column(modifier = Modifier.weight(1f).padding(start = layout.dp(8))) {
                    Text(
                        text = stringResource(R.string.phone_title),
                        color = colors.primary,
                        fontSize = layout.sp(11),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Text(
                        text = connectionLabel(phoneState),
                        color = if (phoneState.connected) Color(0xFF278844) else Color(0xFF8A3A32),
                        fontSize = layout.sp(10),
                        maxLines = 1,
                    )
                }
                Icon(
                    imageVector = if (contactMode) Icons.Outlined.Dialpad else Icons.Outlined.Contacts,
                    contentDescription = if (contactMode) stringResource(R.string.phone_keypad) else stringResource(R.string.phone_contacts),
                    tint = colors.primary,
                    modifier = Modifier.size(layout.dp(28)).clickable { contactMode = !contactMode },
                )
            }
            Text(
                text = callLabel(phoneState),
                color = colors.muted,
                fontSize = layout.sp(9),
                maxLines = 1,
                modifier = Modifier.size(layout.dp(374), layout.dp(15)),
                textAlign = TextAlign.Center,
            )
            if (contactMode) {
                ContactList(
                    contacts = phoneState.contacts,
                    layout = layout,
                    onSelect = { contact ->
                        number = contact.number
                        contactMode = false
                    },
                )
                Row(
                    modifier = Modifier
                        .size(layout.dp(200), layout.dp(42))
                        .clickable { AndroidBluetoothPhoneClient.refreshContacts() },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(layout.dp(22)))
                    Text(stringResource(R.string.phone_refresh_contacts), fontSize = layout.sp(11), modifier = Modifier.padding(start = layout.dp(8)))
                }
            } else {
                Row(
                    modifier = Modifier.size(layout.dp(330), layout.dp(43)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = phoneState.remoteName.ifBlank { number.ifEmpty { stringResource(R.string.phone_number_hint) } },
                        color = colors.primary,
                        fontSize = layout.sp(19),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Backspace,
                        contentDescription = stringResource(R.string.phone_delete),
                        tint = if (phoneState.callState == BluetoothCallState.IDLE) colors.primary else colors.muted,
                        modifier = Modifier
                            .size(layout.dp(34))
                            .clickable(enabled = phoneState.callState == BluetoothCallState.IDLE) {
                                if (number.isNotEmpty()) number = number.dropLast(1)
                            },
                    )
                }
                listOf("123", "456", "789", "*0#").forEach { keys ->
                    Row(
                        modifier = Modifier.size(layout.dp(285), layout.dp(43)),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        keys.forEach { key ->
                            Box(
                                modifier = Modifier
                                    .size(layout.dp(38))
                                    .background(
                                        if (colors.dark) Color(0xFF1D2227) else Color(0xFFE1E2E0),
                                        CircleShape,
                                    )
                                    .clickable(enabled = phoneState.callState == BluetoothCallState.IDLE) {
                                        number += key
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = key.toString(),
                                    fontSize = layout.sp(18),
                                    color = if (phoneState.callState == BluetoothCallState.IDLE) colors.primary else colors.muted,
                                )
                            }
                        }
                    }
                }
                CallControls(phoneState, number, layout)
            }
        }
    }
}

@Composable
private fun ContactList(
    contacts: List<BluetoothContact>,
    layout: LauncherLayout,
    onSelect: (BluetoothContact) -> Unit,
) {
    val colors = LocalDashboardColors.current
    if (contacts.isEmpty()) {
        Box(modifier = Modifier.size(layout.dp(374), layout.dp(248)), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.phone_contacts_unavailable),
                color = colors.muted,
                fontSize = layout.sp(12),
                textAlign = TextAlign.Center,
                modifier = Modifier.size(layout.dp(300), layout.dp(70)),
            )
        }
        return
    }
    LazyColumn(modifier = Modifier.size(layout.dp(374), layout.dp(248))) {
        items(contacts, key = { "${it.id}:${it.number}" }) { contact ->
            Row(
                modifier = Modifier
                    .size(layout.dp(374), layout.dp(52))
                    .clickable { onSelect(contact) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(start = layout.dp(8))) {
                    Text(
                        contact.name.ifBlank { contact.number },
                        color = colors.primary,
                        fontSize = layout.sp(13),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Text(contact.number, color = colors.muted, fontSize = layout.sp(10), maxLines = 1)
                }
                Icon(
                    imageVector = if (contact.favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (contact.favorite) stringResource(R.string.phone_remove_favorite) else stringResource(R.string.phone_add_favorite),
                    tint = if (contact.favorite) Color(0xFFE0A300) else colors.muted,
                    modifier = Modifier
                        .size(layout.dp(30))
                        .clickable { AndroidBluetoothPhoneClient.toggleFavorite(contact.number) },
                )
            }
        }
    }
}

@Composable
private fun CallControls(state: BluetoothPhoneState, number: String, layout: LauncherLayout) {
    val colors = LocalDashboardColors.current
    Row(
        modifier = Modifier.size(layout.dp(285), layout.dp(55)),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state.callState) {
            BluetoothCallState.RINGING -> {
                RoundCallButton(Color(0xFF3DAA55), Icons.Outlined.Call, stringResource(R.string.phone_answer), layout) {
                    AndroidBluetoothPhoneClient.answer()
                }
                RoundCallButton(Color(0xFFC7473D), Icons.Outlined.CallEnd, stringResource(R.string.phone_reject), layout) {
                    AndroidBluetoothPhoneClient.reject()
                }
            }

            BluetoothCallState.DIALING, BluetoothCallState.CONNECTED -> {
                RoundCallButton(Color(0xFFC7473D), Icons.Outlined.CallEnd, stringResource(R.string.phone_hang_up), layout) {
                    AndroidBluetoothPhoneClient.hangUp()
                }
            }

            BluetoothCallState.IDLE, BluetoothCallState.FAILED -> {
                RoundCallButton(
                    color = if (state.connected && number.isNotBlank()) Color(0xFF3DAA55) else colors.muted,
                    icon = Icons.Outlined.Call,
                    description = stringResource(R.string.phone_call_bluetooth),
                    layout = layout,
                ) {
                    if (state.connected && number.isNotBlank()) AndroidBluetoothPhoneClient.dial(number)
                }
            }
        }
    }
}

@Composable
private fun RoundCallButton(
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    layout: LauncherLayout,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(layout.dp(48))
            .background(color, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(layout.dp(26)))
    }
}

@Composable
private fun connectionLabel(state: BluetoothPhoneState): String = when {
    !state.serviceAvailable -> stringResource(R.string.phone_headset_unavailable)
    !state.connected -> stringResource(R.string.phone_not_connected)
    !state.deviceName.isNullOrBlank() -> stringResource(R.string.phone_connected_to, state.deviceName)
    else -> stringResource(R.string.phone_connected)
}

@Composable
private fun callLabel(state: BluetoothPhoneState): String {
    val remote = state.remoteName.ifBlank { state.remoteNumber }
    return if (remote.isBlank()) state.status.text() else "${state.status.text()}: $remote"
}
