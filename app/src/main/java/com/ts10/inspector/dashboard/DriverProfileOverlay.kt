package com.ts10.inspector.dashboard

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.R
import com.ts10.inspector.settings.DashboardPreferences
import com.ts10.inspector.settings.DriverProfilePreferences
import com.ts10.inspector.ui.DashboardAssetIcon
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.ui.LocalDashboardColors
import com.ts10.inspector.ui.modalCornerShape
import com.ts10.inspector.ui.rightPanelModalFrame

@Composable
internal fun DriverProfileOverlay(
    layout: LauncherLayout,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalDashboardColors.current
    val profiles = remember(context) { DriverProfilePreferences.get(context) }
    val state by profiles.state.collectAsStateWithLifecycle()
    val dashboard = remember(context) { DashboardPreferences.get(context) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(onClick = onDismiss),
    ) {
        // La ventana empieza bajo la barra de estado: el marco usa su altura real.
        val frame = rightPanelModalFrame(maxWidth.value / layout.scale, maxHeight.value / layout.scale)
        Column(
            modifier = Modifier
                .offset(layout.dp(frame.x), layout.dp(frame.y))
                .size(layout.dp(frame.width), layout.dp(frame.height))
                .background(colors.modalBackground, modalCornerShape(layout))
                .clickable { }
                .padding(layout.dp(16)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Person,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(layout.dp(24)),
                )
                Column(modifier = Modifier.padding(start = layout.dp(8)).weight(1f)) {
                    Text(stringResource(R.string.profile_who_drives), color = colors.primary, fontSize = layout.sp(15), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.profile_select_for_start), color = colors.muted, fontSize = layout.sp(8))
                }
                DashboardAssetIcon(
                    drawable = R.drawable.ic_modal_close,
                    contentDescription = stringResource(R.string.profile_close_selector),
                    tint = colors.primary,
                    modifier = Modifier.size(layout.dp(22)).clickable(onClick = onDismiss),
                )
            }
            Column(modifier = Modifier.padding(top = layout.dp(12))) {
                state.profiles.forEach { profile ->
                    val selected = profile.id == state.activeProfileId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = layout.dp(3))
                            .background(colors.primary.copy(alpha = if (colors.dark) .075f else .05f), RoundedCornerShape(layout.dp(7)))
                            .border(
                                layout.dp(1),
                                if (selected) Color(0xFF2E9D50) else colors.muted.copy(alpha = .24f),
                                RoundedCornerShape(layout.dp(7)),
                            )
                            .clickable {
                                profiles.selectProfile(profile.id, dashboard)
                                onDismiss()
                            }
                            .padding(horizontal = layout.dp(12), vertical = layout.dp(10)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Person,
                            contentDescription = null,
                            tint = Color(profile.iconColor.argb),
                            modifier = Modifier.size(layout.dp(25)),
                        )
                        Column(modifier = Modifier.padding(start = layout.dp(10)).weight(1f)) {
                            Text(profile.name, color = colors.primary, fontSize = layout.sp(11), fontWeight = FontWeight.Bold)
                            Text(
                                profile.preferredPhoneName?.let { stringResource(R.string.profile_preferred_phone, it) }
                                    ?: stringResource(R.string.profile_no_preferred_phone),
                                color = colors.muted,
                                fontSize = layout.sp(8),
                            )
                        }
                        if (selected) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = stringResource(R.string.profile_active),
                                tint = Color(0xFF2E9D50),
                                modifier = Modifier.size(layout.dp(20)),
                            )
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.profile_applies_hint),
                color = colors.muted,
                fontSize = layout.sp(8),
                lineHeight = layout.sp(11),
                modifier = Modifier.padding(top = layout.dp(10)),
            )
        }
    }
}
