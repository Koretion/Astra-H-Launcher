package com.ts10.inspector.settings

import com.ts10.inspector.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.phone.AndroidBluetoothPhoneClient
import com.ts10.inspector.phone.normalizeBluetoothAddress
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.ui.LocalDashboardColors

@Composable
internal fun DriverProfilesSettingsPanel(layout: LauncherLayout, width: Float, height: Float) {
    val context = LocalContext.current
    val colors = LocalDashboardColors.current
    val preferences = remember(context) { DriverProfilePreferences.get(context) }
    val state by preferences.state.collectAsStateWithLifecycle()
    val phone by AndroidBluetoothPhoneClient.state.collectAsStateWithLifecycle()
    var editedProfileId by remember { mutableStateOf(state.activeProfileId) }
    val profile = state.profiles.firstOrNull { it.id == editedProfileId } ?: state.activeProfile
    var nameDraft by remember(profile.id, profile.name) { mutableStateOf(profile.name) }
    var phoneAddressDraft by remember(profile.id, profile.preferredPhoneAddress) {
        mutableStateOf(profile.preferredPhoneAddress.orEmpty())
    }

    LazyColumn(modifier = Modifier.size(layout.dp(width), layout.dp(height))) {
        item {
            Text(
                stringResource(R.string.profiles_local),
                color = colors.muted,
                fontSize = layout.sp(8),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = layout.dp(5)),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(layout.dp(6)),
            ) {
                state.profiles.forEach { candidate ->
                    val selected = candidate.id == profile.id
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .background(colors.primary.copy(alpha = if (colors.dark) .07f else .045f), RoundedCornerShape(layout.dp(6)))
                            .border(
                                layout.dp(1),
                                if (selected) Color(candidate.iconColor.argb) else colors.muted.copy(alpha = .25f),
                                RoundedCornerShape(layout.dp(6)),
                            )
                            .clickable { editedProfileId = candidate.id }
                            .padding(horizontal = layout.dp(8), vertical = layout.dp(8)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Person,
                            contentDescription = null,
                            tint = Color(candidate.iconColor.argb),
                            modifier = Modifier.size(layout.dp(18)),
                        )
                        Text(
                            candidate.name,
                            color = colors.primary,
                            fontSize = layout.sp(9),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = layout.dp(5)).weight(1f),
                        )
                        if (candidate.id == state.activeProfileId) {
                            Icon(Icons.Outlined.Check, stringResource(R.string.profile_active), tint = Color(0xFF2E9D50), modifier = Modifier.size(layout.dp(15)))
                        }
                    }
                }
            }
        }

        item {
            ProfileSettingsCard(layout, width) {
                ProfileSectionTitle(stringResource(R.string.profiles_identity), layout)
                Text(stringResource(R.string.profiles_name), color = colors.muted, fontSize = layout.sp(8))
                Row(
                    modifier = Modifier.padding(top = layout.dp(4)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it.take(24) },
                        singleLine = true,
                        textStyle = TextStyle(color = colors.primary, fontSize = layout.sp(10), fontWeight = FontWeight.Bold),
                        modifier = Modifier
                            .weight(1f)
                            .background(colors.primary.copy(alpha = if (colors.dark) .07f else .04f), RoundedCornerShape(layout.dp(5)))
                            .border(layout.dp(1), colors.muted.copy(alpha = .3f), RoundedCornerShape(layout.dp(5)))
                            .padding(horizontal = layout.dp(10), vertical = layout.dp(9)),
                    )
                    ProfileButton(stringResource(R.string.common_save_caps), layout, enabled = nameDraft.trim() != profile.name) {
                        preferences.setName(profile.id, nameDraft)
                    }
                }
                Text(stringResource(R.string.profiles_icon_colour), color = colors.muted, fontSize = layout.sp(8), modifier = Modifier.padding(top = layout.dp(9)))
                Row(
                    modifier = Modifier.padding(top = layout.dp(5)),
                    horizontalArrangement = Arrangement.spacedBy(layout.dp(9)),
                ) {
                    DriverProfileColor.entries.forEach { option ->
                        Box(
                            modifier = Modifier
                                .size(layout.dp(if (option == profile.iconColor) 24 else 21))
                                .background(Color(option.argb), RoundedCornerShape(50))
                                .border(
                                    layout.dp(if (option == profile.iconColor) 2 else 1),
                                    if (option == profile.iconColor) colors.primary else colors.muted.copy(alpha = .35f),
                                    RoundedCornerShape(50),
                                )
                                .clickable { preferences.setIconColor(profile.id, option) },
                        )
                    }
                }
            }
        }

        item {
            ProfileSettingsCard(layout, width) {
                ProfileSectionTitle(stringResource(R.string.profiles_preferred_phone), layout)
                ProfileValueRow(
                    stringResource(R.string.profiles_linked),
                    profile.preferredPhoneName ?: stringResource(R.string.profiles_unassigned),
                    profile.preferredPhoneAddress ?: stringResource(R.string.profiles_address_pending),
                    layout,
                )
                ProfileValueRow(
                    stringResource(R.string.profiles_connected_now),
                    if (phone.connected) phone.deviceName ?: stringResource(R.string.profiles_bluetooth_phone) else stringResource(R.string.profiles_none),
                    phone.deviceAddress ?: if (phone.connected) stringResource(R.string.profiles_no_address) else stringResource(R.string.profiles_connect_phone),
                    layout,
                )
                Row(modifier = Modifier.padding(top = layout.dp(7))) {
                    ProfileButton(stringResource(R.string.profiles_link_connected), layout, enabled = phone.connected && phone.deviceAddress != null) {
                        preferences.setPreferredPhone(profile.id, phone.deviceAddress, phone.deviceName)
                    }
                    ProfileButton(stringResource(R.string.profiles_unlink), layout, enabled = profile.preferredPhoneAddress != null) {
                        preferences.setPreferredPhone(profile.id, null, null)
                    }
                }
                Text(
                    stringResource(R.string.profiles_link_by_mac),
                    color = colors.muted,
                    fontSize = layout.sp(8),
                    modifier = Modifier.padding(top = layout.dp(9)),
                )
                Row(
                    modifier = Modifier.padding(top = layout.dp(4)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = phoneAddressDraft,
                        onValueChange = { phoneAddressDraft = it.take(17) },
                        singleLine = true,
                        textStyle = TextStyle(color = colors.primary, fontSize = layout.sp(10), fontWeight = FontWeight.Bold),
                        modifier = Modifier
                            .weight(1f)
                            .background(colors.primary.copy(alpha = if (colors.dark) .07f else .04f), RoundedCornerShape(layout.dp(5)))
                            .border(layout.dp(1), colors.muted.copy(alpha = .3f), RoundedCornerShape(layout.dp(5)))
                            .padding(horizontal = layout.dp(9), vertical = layout.dp(8)),
                    )
                    val normalizedAddress = normalizeBluetoothAddress(phoneAddressDraft)
                    ProfileButton(stringResource(R.string.profiles_link_mac), layout, enabled = normalizedAddress != null) {
                        preferences.setPreferredPhone(profile.id, normalizedAddress, phone.deviceName)
                    }
                }
                ProfileButton(stringResource(R.string.profiles_open_bluetooth), layout, enabled = true) {
                    AndroidBluetoothPhoneClient.openBluetoothSettings()
                }
                Text(
                    stringResource(R.string.profiles_mac_hint),
                    color = colors.muted,
                    fontSize = layout.sp(8),
                    lineHeight = layout.sp(11),
                    modifier = Modifier.padding(top = layout.dp(6)),
                )
            }
        }

    }
}

@Composable
private fun ProfileSettingsCard(layout: LauncherLayout, width: Float, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalDashboardColors.current
    Column(
        modifier = Modifier
            .padding(top = layout.dp(8))
            .width(layout.dp(width))
            .wrapContentHeight()
            .background(colors.primary.copy(alpha = if (colors.dark) .07f else .045f), RoundedCornerShape(layout.dp(7)))
            .padding(layout.dp(11)),
        content = content,
    )
}

@Composable
private fun ProfileSectionTitle(text: String, layout: LauncherLayout) {
    val colors = LocalDashboardColors.current
    Text(text, color = colors.muted, fontSize = layout.sp(8), fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = layout.dp(5)))
}

@Composable
private fun ProfileValueRow(title: String, value: String, subtitle: String, layout: LauncherLayout) {
    val colors = LocalDashboardColors.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = layout.dp(4)), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = colors.primary, fontSize = layout.sp(10), fontWeight = FontWeight.Bold)
            Text(subtitle, color = colors.muted, fontSize = layout.sp(7), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(value, color = colors.primary, fontSize = layout.sp(9), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ProfileButton(text: String, layout: LauncherLayout, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalDashboardColors.current
    Text(
        text,
        color = if (enabled) Color(0xFF2E9D50) else colors.muted.copy(alpha = .45f),
        fontSize = layout.sp(8),
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(end = layout.dp(6), top = layout.dp(4))
            .background(colors.primary.copy(alpha = if (colors.dark) .08f else .05f), RoundedCornerShape(layout.dp(5)))
            .border(layout.dp(1), if (enabled) colors.muted.copy(alpha = .3f) else colors.muted.copy(alpha = .12f), RoundedCornerShape(layout.dp(5)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = layout.dp(10), vertical = layout.dp(8)),
    )
}
