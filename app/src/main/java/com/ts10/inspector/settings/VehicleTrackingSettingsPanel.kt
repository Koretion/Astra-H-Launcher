package com.ts10.inspector.settings

import com.ts10.inspector.ui.text
import com.ts10.inspector.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.trips.VehicleTrackingPreferences
import com.ts10.inspector.trips.VehicleTrackingSettings
import com.ts10.inspector.trips.VehicleTripTracker
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.ui.LocalDashboardColors

@Composable
internal fun VehicleTrackingSettingsPanel(
    layout: LauncherLayout,
    width: Float,
    height: Float,
) {
    val context = LocalContext.current
    val preferences = remember { VehicleTrackingPreferences.get(context) }
    val saved by preferences.state.collectAsStateWithLifecycle()
    val tracker by VehicleTripTracker.state.collectAsStateWithLifecycle()
    val colors = LocalDashboardColors.current

    var enabled by remember(saved) { mutableStateOf(saved.enabled) }
    var scriptUrl by remember(saved) { mutableStateOf(saved.scriptUrl) }
    var apiKey by remember(saved) { mutableStateOf(saved.apiKey) }
    var deviceId by remember(saved) { mutableStateOf(saved.deviceId) }
    var sampleSeconds by remember(saved) { mutableStateOf(saved.sampleIntervalSeconds.toString()) }
    var minDistance by remember(saved) { mutableStateOf(saved.minDistanceMeters.toCleanString()) }
    var uploadSeconds by remember(saved) { mutableStateOf(saved.uploadIntervalSeconds.toString()) }
    var batchSize by remember(saved) { mutableStateOf(saved.batchSize.toString()) }
    var closeMinutes by remember(saved) { mutableStateOf(saved.autoCloseMinutes.toString()) }

    LazyColumn(modifier = Modifier.size(layout.dp(width), layout.dp(height))) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = layout.dp(7)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.tracking_title),
                        color = colors.primary,
                        fontSize = layout.sp(12),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        tracker.status.text(),
                        color = if (tracker.lastError == null) colors.muted else androidx.compose.ui.graphics.Color(0xFFE0A020),
                        fontSize = layout.sp(9),
                    )
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
        }
        item {
            Text(
                stringResource(R.string.tracking_hint),
                color = colors.muted,
                fontSize = layout.sp(9),
                modifier = Modifier.padding(bottom = layout.dp(6)),
            )
        }
        item {
            TrackingTextField(
                value = scriptUrl,
                onValueChange = { scriptUrl = it },
                label = stringResource(R.string.tracking_url),
                layout = layout,
            )
        }
        item {
            TrackingTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = stringResource(R.string.tracking_api_key),
                layout = layout,
                secret = true,
            )
        }
        item {
            TrackingTextField(
                value = deviceId,
                onValueChange = { deviceId = it },
                label = stringResource(R.string.tracking_device_id),
                layout = layout,
            )
        }
        item {
            Row(modifier = Modifier.fillMaxWidth()) {
                TrackingTextField(
                    value = sampleSeconds,
                    onValueChange = { sampleSeconds = it.filter(Char::isDigit) },
                    label = stringResource(R.string.tracking_sampling),
                    layout = layout,
                    modifier = Modifier.weight(1f).padding(end = layout.dp(4)),
                )
                TrackingTextField(
                    value = minDistance,
                    onValueChange = { minDistance = it.filter { char -> char.isDigit() || char == '.' || char == ',' } },
                    label = stringResource(R.string.tracking_distance),
                    layout = layout,
                    modifier = Modifier.weight(1f).padding(start = layout.dp(4)),
                )
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth()) {
                TrackingTextField(
                    value = uploadSeconds,
                    onValueChange = { uploadSeconds = it.filter(Char::isDigit) },
                    label = stringResource(R.string.tracking_upload),
                    layout = layout,
                    modifier = Modifier.weight(1f).padding(end = layout.dp(4)),
                )
                TrackingTextField(
                    value = batchSize,
                    onValueChange = { batchSize = it.filter(Char::isDigit) },
                    label = stringResource(R.string.tracking_batch),
                    layout = layout,
                    modifier = Modifier.weight(1f).padding(start = layout.dp(4)),
                )
            }
        }
        item {
            TrackingTextField(
                value = closeMinutes,
                onValueChange = { closeMinutes = it.filter(Char::isDigit) },
                label = stringResource(R.string.tracking_idle_close),
                layout = layout,
            )
        }
        item {
            tracker.lastError?.let { error ->
                Text(
                    stringResource(R.string.tracking_last_error, error),
                    color = androidx.compose.ui.graphics.Color(0xFFE0A020),
                    fontSize = layout.sp(8),
                    modifier = Modifier.padding(vertical = layout.dp(4)),
                )
            }
            Text(
                stringResource(R.string.trk_ui_session, tracker.activeSession ?: "--", tracker.sessionPoints, tracker.pendingUploads),
                color = colors.muted,
                fontSize = layout.sp(8),
                modifier = Modifier.padding(vertical = layout.dp(4)),
            )
            Text(
                stringResource(R.string.trk_ui_queue, tracker.uploadWorkerState, tracker.uploadEnqueueReason?.let { " ($it)" }.orEmpty(), tracker.uploadRunAttempt),
                color = colors.muted,
                fontSize = layout.sp(8),
            )
            Text(
                stringResource(R.string.trk_ui_fixes, tracker.acceptedFixes, tracker.rejectedFixes) +
                    tracker.lastFixAccuracyMeters?.let { stringResource(R.string.trk_ui_accuracy, it.toInt()) }.orEmpty() +
                    tracker.lastFixAgeMs?.let { stringResource(R.string.trk_ui_age, it) }.orEmpty() +
                    tracker.lastFixProvider?.let { " | $it" }.orEmpty() +
                    tracker.lastSpeedSource?.let { stringResource(R.string.trk_ui_speed, tracker.lastVehicleSpeedKmh?.toInt() ?: 0, it) }.orEmpty(),
                color = colors.muted,
                fontSize = layout.sp(8),
            )
            tracker.lastDecision?.let { decision ->
                Text(
                    stringResource(R.string.trk_ui_decision, decision),
                    color = colors.muted,
                    fontSize = layout.sp(8),
                    modifier = Modifier.padding(top = layout.dp(2)),
                )
            }
            tracker.lastHttpResult?.let { result ->
                Text(
                    "Sheets: $result",
                    color = colors.muted,
                    fontSize = layout.sp(8),
                    modifier = Modifier.padding(top = layout.dp(2)),
                )
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(top = layout.dp(5))) {
                TrackingButton(
                    text = stringResource(R.string.tracking_save),
                    onClick = {
                        val settings = VehicleTrackingSettings(
                            enabled = enabled,
                            scriptUrl = scriptUrl,
                            apiKey = apiKey,
                            deviceId = deviceId,
                            sampleIntervalSeconds = sampleSeconds.toIntOrNull() ?: 5,
                            minDistanceMeters = minDistance.replace(',', '.').toFloatOrNull() ?: 5f,
                            uploadIntervalSeconds = uploadSeconds.toIntOrNull() ?: 15,
                            batchSize = batchSize.toIntOrNull() ?: 10,
                            autoCloseMinutes = closeMinutes.toIntOrNull() ?: 15,
                        )
                        preferences.save(settings)
                        VehicleTripTracker.applySettings(context)
                    },
                    modifier = Modifier.weight(1f).padding(end = layout.dp(4)),
                    layout = layout,
                )
                TrackingButton(
                    text = stringResource(R.string.tracking_upload_now),
                    onClick = VehicleTripTracker::uploadNow,
                    enabled = saved.enabled && saved.configured,
                    modifier = Modifier.weight(1f).padding(start = layout.dp(4)),
                    layout = layout,
                )
            }
        }
    }
}

@Composable
private fun TrackingButton(
    text: String,
    layout: LauncherLayout,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = LocalDashboardColors.current
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(layout.dp(6)),
        border = BorderStroke(
            layout.dp(1),
            if (enabled) colors.muted.copy(alpha = .28f) else colors.muted.copy(alpha = .12f),
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.primary.copy(alpha = if (colors.dark) .075f else .05f),
            contentColor = Color(0xFF2E9D50),
            disabledContainerColor = colors.primary.copy(alpha = .025f),
            disabledContentColor = colors.muted,
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = layout.dp(0),
            pressedElevation = layout.dp(0),
            focusedElevation = layout.dp(0),
            hoveredElevation = layout.dp(0),
            disabledElevation = layout.dp(0),
        ),
    ) {
        Text(text, fontSize = layout.sp(10), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TrackingTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    layout: LauncherLayout,
    modifier: Modifier = Modifier.fillMaxWidth(),
    secret: Boolean = false,
) {
    val clipboard = LocalClipboardManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = layout.sp(9)) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = layout.sp(10)),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        trailingIcon = {
            IconButton(
                onClick = {
                    clipboard.getText()?.text?.let(onValueChange)
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentPaste,
                    contentDescription = stringResource(R.string.tracking_paste),
                )
            }
        },
        modifier = modifier.padding(vertical = layout.dp(2)),
    )
}

private fun Float.toCleanString(): String =
    if (this % 1f == 0f) toInt().toString() else toString()
