package com.ts10.inspector.settings

import com.ts10.inspector.ui.text
import com.ts10.inspector.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import com.ts10.inspector.ui.LocalDashboardColors
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.AppVersion
import com.ts10.inspector.update.LauncherUpdateManager
import com.ts10.inspector.update.UpdateMode
import com.ts10.inspector.ui.LauncherLayout
import java.text.DateFormat
import java.util.Date

@Composable
internal fun LauncherUpdateDetail(layout: LauncherLayout, width: Float, height: Float) {
    val context = LocalContext.current
    val update by LauncherUpdateManager.state.collectAsStateWithLifecycle()
    val modeLabels = UpdateMode.entries.associateWith { stringResource(it.label) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // El estado y el progreso están arriba: al pulsar se vuelve allí para que se vean.
    val showProgress: () -> Unit = { scope.launch { listState.animateScrollToItem(0) } }
    val downloadingTitle = stringResource(R.string.lupdate_downloading_percent, update.progressPercent)
    Box(modifier = Modifier.size(layout.dp(width), layout.dp(height))) {
        LazyColumn(state = listState, modifier = Modifier.size(layout.dp(width), layout.dp(height))) {
            item {
                SectionHint(
                    stringResource(R.string.lupdate_intro),
                    layout,
                )
            }
            item { SettingsCard(layout, width) {
                OptionChoices(
                    stringResource(R.string.lupdate_when_new),
                    UpdateMode.entries,
                    update.mode,
                    { modeLabels.getValue(it) },
                    { LauncherUpdateManager.setMode(context, it) },
                    layout,
                )
                SectionHint(stringResource(modeDescription(update.mode)), layout)
            } }
            item { SettingsCard(layout, width) {
                MetricRow(stringResource(R.string.system_installed_version), AppVersion.name, stringResource(R.string.lupdate_code, AppVersion.code), layout)
                MetricRow(
                    stringResource(R.string.gmlan_status),
                    if (update.busy && update.progressPercent > 0) "${update.progressPercent}%" else update.status.text(),
                    update.availableVersion?.let { stringResource(R.string.gmlan_published_version, it) } ?: "GitHub Releases",
                    layout,
                )
                MetricRow(
                    stringResource(R.string.lupdate_last_check),
                    if (update.lastCheckMillis > 0) {
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(update.lastCheckMillis))
                    } else {
                        "--"
                    },
                    stringResource(R.string.lupdate_last_check_hint),
                    layout,
                )
            } }
            item {
                ActionRow(stringResource(R.string.lupdate_check_now), stringResource(R.string.lupdate_check_now_hint), layout, !update.busy) {
                    LauncherUpdateManager.checkNow(context)
                }
            }
            if (update.availableVersion != null) {
                if (update.downloadedVersion == null) {
                    item {
                        ActionRow(
                            if (update.downloading) downloadingTitle else stringResource(R.string.gmlan_download_version, update.availableVersion.orEmpty()),
                            stringResource(R.string.lupdate_download_hint),
                            layout,
                            !update.busy,
                        ) {
                            showProgress()
                            LauncherUpdateManager.download(context)
                        }
                    }
                }
                item {
                    ActionRow(
                        when {
                            update.installing -> stringResource(R.string.upd_installing_wait)
                            update.downloading -> downloadingTitle
                            else -> stringResource(R.string.lupdate_install_version, update.availableVersion.orEmpty())
                        },
                        if (update.downloadedVersion != null) stringResource(R.string.lupdate_install_ready_hint) else stringResource(R.string.lupdate_install_hint),
                        layout,
                        !update.busy,
                    ) {
                        showProgress()
                        LauncherUpdateManager.install(context)
                    }
                }
                if (update.notes.isNotBlank()) {
                    item { SettingsCard(layout, width) {
                        MetricRow(stringResource(R.string.lupdate_whats_new), update.availableVersion.orEmpty(), stringResource(R.string.lupdate_release_notes), layout)
                        SectionHint(update.notes, layout)
                    } }
                }
            }
        }
        if (update.installing) InstallingNotice(layout, Modifier.align(Alignment.Center))
    }
}

@Composable
private fun InstallingNotice(layout: LauncherLayout, modifier: Modifier) {
    val colors = LocalDashboardColors.current
    val shape = RoundedCornerShape(layout.dp(12))
    Row(
        modifier = modifier
            .background(colors.modalBackground, shape)
            .border(layout.dp(1), colors.divider, shape)
            .padding(horizontal = layout.dp(18), vertical = layout.dp(14)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(layout.dp(12)),
    ) {
        CircularProgressIndicator(color = Color(0xFF2E9D50), strokeWidth = layout.dp(2), modifier = Modifier.size(layout.dp(22)))
        Column {
            Text(stringResource(R.string.upd_installing_wait), color = colors.primary, fontSize = layout.sp(10), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.lupdate_installing_hint), color = colors.muted, fontSize = layout.sp(8))
        }
    }
}

@androidx.annotation.StringRes
private fun modeDescription(mode: UpdateMode): Int = when (mode) {
    UpdateMode.OFF -> R.string.lupdate_mode_off
    UpdateMode.NOTIFY -> R.string.lupdate_mode_notify
    UpdateMode.DOWNLOAD -> R.string.lupdate_mode_download
    UpdateMode.INSTALL -> R.string.lupdate_mode_install
}
