package com.ts10.inspector.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ts10.inspector.BuildConfig
import com.ts10.inspector.R
import com.ts10.inspector.panel.openExclusiveSystemScreen
import com.ts10.inspector.ui.AppLocale
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.ui.LocalDashboardColors
import com.ts10.inspector.ui.text
import com.ts10.inspector.vehicle.can.profile.VehicleCanProfiles
import com.ts10.inspector.vehicle.can.profile.VehicleProfileEntry
import com.ts10.inspector.vehicle.can.profile.VehicleProfileFileActivity
import com.ts10.inspector.vehicle.can.profile.VehicleProfileManager

/** Trama de la que sale un dato según el perfil activo, para las etiquetas de origen de Ajustes. */
internal fun canSource(vararg signals: String): String = VehicleCanProfiles.active.sourceLabel(*signals)

@Composable
internal fun VehicleProfileDetail(layout: LauncherLayout, width: Float, height: Float) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { VehicleProfileManager.start(context) }
    val state by VehicleProfileManager.state.collectAsStateWithLifecycle()
    val active = state.profiles.firstOrNull { it.id == state.activeId }
    var pendingDelete by remember { mutableStateOf<VehicleProfileEntry?>(null) }
    Box(modifier = Modifier.size(layout.dp(width), layout.dp(height))) {
        LazyColumn(modifier = Modifier.size(layout.dp(width), layout.dp(height))) {
            item { SectionHint(stringResource(R.string.vprofile_intro), layout) }
            item { SettingsCard(layout, width) {
                MetricRow(
                    stringResource(R.string.vprofile_active),
                    active?.name ?: "--",
                    if (active?.builtIn != false) stringResource(R.string.vprofile_builtin) else stringResource(R.string.vprofile_imported),
                    layout,
                )
                active?.let {
                    MetricRow(
                        stringResource(R.string.vprofile_content),
                        stringResource(R.string.vprofile_signals, it.signalCount, it.frameCount),
                        it.id,
                        layout,
                    )
                }
            } }
            // Texto completo: un error de validación no cabe en el valor de una fila.
            state.status?.let { status ->
                item { SectionHint("${stringResource(R.string.vprofile_last_import)}: ${status.text()}", layout) }
            }
            if (state.profiles.size > 1) {
                item { SettingsCard(layout, width) {
                    WrappedOptionChoices(
                        stringResource(R.string.vprofile_choose),
                        state.profiles.map { it.id },
                        state.activeId,
                        { id -> state.profiles.first { it.id == id }.name },
                        { VehicleProfileManager.select(context, it) },
                        layout,
                    )
                } }
            }
            item {
                ActionRow(stringResource(R.string.vprofile_import), stringResource(R.string.vprofile_import_hint), layout, true) {
                    VehicleProfileFileActivity.launchImport(context)
                }
            }
            item {
                ActionRow(stringResource(R.string.vprofile_export), stringResource(R.string.vprofile_export_hint), layout, true) {
                    VehicleProfileFileActivity.launchExport(context, state.activeId)
                }
            }
            item {
            ActionRow(stringResource(R.string.vprofile_open_docs), "github.com/${BuildConfig.UPDATE_REPOSITORY}", layout, true) {
                openProfileFormatDocs(context)
            }
        }
        if (active != null && !active.builtIn) {
                item {
                    ActionRow(stringResource(R.string.vprofile_delete, active.name), stringResource(R.string.vprofile_delete_hint), layout, true) {
                        pendingDelete = active
                    }
                }
            }
        }
        pendingDelete?.let { profile ->
            DeleteProfileConfirmation(
                profile = profile,
                layout = layout,
                onCancel = { pendingDelete = null },
                onConfirm = {
                    pendingDelete = null
                    VehicleProfileManager.delete(context, profile.id)
                },
            )
        }
    }
}

/** Documentación del formato en el repositorio público, en español si el launcher lo está. */
private fun openProfileFormatDocs(context: Context) {
    val spanish = AppLocale.resources(context).configuration.locales[0].language == "es"
    val file = if (spanish) "CAN_SIGNALS.es.md" else "CAN_SIGNALS.md"
    val url = "https://github.com/${BuildConfig.UPDATE_REPOSITORY}/blob/main/docs/$file"
    openExclusiveSystemScreen(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

@Composable
private fun DeleteProfileConfirmation(
    profile: VehicleProfileEntry,
    layout: LauncherLayout,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalDashboardColors.current
    val shape = RoundedCornerShape(layout.dp(12))
    // Capa que tapa el panel: un toque fuera de la ventana cancela, como en un diálogo.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = .35f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = layout.dp(320))
                .background(colors.modalBackground, shape)
                .border(layout.dp(1), colors.divider, shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(horizontal = layout.dp(18), vertical = layout.dp(14)),
        ) {
            Text(
                stringResource(R.string.vprofile_delete_title, profile.name),
                color = colors.primary,
                fontSize = layout.sp(11),
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.vprofile_delete_message),
                color = colors.muted,
                fontSize = layout.sp(9),
                lineHeight = layout.sp(12),
                modifier = Modifier.padding(top = layout.dp(6), bottom = layout.dp(12)),
            )
            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(layout.dp(8)),
            ) {
                DiagnosticAction(stringResource(R.string.common_cancel_caps), colors.primary, layout, onCancel)
                DiagnosticAction(stringResource(R.string.vprofile_delete_confirm), Color(0xFFD94747), layout, onConfirm)
            }
        }
    }
}
