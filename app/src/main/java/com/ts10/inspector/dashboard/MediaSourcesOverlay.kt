package com.ts10.inspector.dashboard

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.core.graphics.drawable.toBitmap
import com.ts10.inspector.R
import com.ts10.inspector.panel.InstalledApplication
import com.ts10.inspector.panel.rememberInstalledApplications
import com.ts10.inspector.settings.DashboardPreferences
import com.ts10.inspector.ui.DashboardAssetIcon
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.ui.LocalDashboardColors
import com.ts10.inspector.ui.modalCornerShape
import com.ts10.inspector.ui.rightPanelModalFrame

@Composable
internal fun MediaSourcesOverlay(
    layout: LauncherLayout,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalDashboardColors.current
    val settings = DashboardPreferences.current(context)
    val applications = rememberInstalledApplications()
    val mediaApplications = remember(settings.mediaPackages, applications) {
        applications.filter { it.packageName in settings.mediaPackages }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(onClick = onDismiss),
    ) {
        // La ventana empieza bajo la barra de estado: el marco usa su altura real.
        val frame = rightPanelModalFrame(maxWidth.value / layout.scale, maxHeight.value / layout.scale)
        val contentWidth = frame.width - 32f
        Column(
            modifier = Modifier
                .offset(layout.dp(frame.x), layout.dp(frame.y))
                .size(layout.dp(frame.width), layout.dp(frame.height))
                .clip(modalCornerShape(layout))
                .background(colors.modalBackground)
                .clickable { }
                .padding(layout.dp(16)),
        ) {
            Row(
                modifier = Modifier.size(layout.dp(contentWidth), layout.dp(28)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DashboardAssetIcon(
                    drawable = R.drawable.icon_options,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier
                        .size(layout.dp(23))
                        .padding(end = layout.dp(6)),
                )
                Text(
                    text = stringResource(R.string.media_source_title),
                    color = colors.primary,
                    fontSize = layout.sp(15),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                DashboardAssetIcon(
                    drawable = R.drawable.ic_modal_close,
                    contentDescription = stringResource(R.string.media_source_close),
                    tint = colors.primary,
                    modifier = Modifier.size(layout.dp(22)).clickable(onClick = onDismiss),
                )
            }
            if (mediaApplications.isEmpty()) {
                Text(
                    text = stringResource(R.string.media_source_empty),
                    color = colors.muted,
                    fontSize = layout.sp(11),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(top = layout.dp(26))
                            .size(layout.dp(contentWidth), layout.dp(60)),
                )
            } else {
                Column(
                    modifier = Modifier
                        .padding(top = layout.dp(10))
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(layout.dp(8)),
                ) {
                    mediaApplications.forEach { application ->
                        MediaSourceRow(
                            application = application,
                            selected = settings.mediaPackage == application.packageName,
                            layout = layout,
                        ) {
                            onSelect(application.packageName)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaSourceRow(
    application: InstalledApplication,
    selected: Boolean,
    layout: LauncherLayout,
    onClick: () -> Unit,
) {
    val colors = LocalDashboardColors.current
    val painter = remember(application.packageName) {
        BitmapPainter(application.icon.toBitmap(72, 72).asImageBitmap())
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(layout.dp(40))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Image(
            painter = painter,
            contentDescription = application.label,
            modifier = Modifier.size(layout.dp(28)),
        )
        Text(
            text = application.label,
            color = colors.primary,
            fontSize = layout.sp(11),
            maxLines = 1,
            modifier = Modifier.padding(start = layout.dp(10)).weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = stringResource(R.string.media_source_selected),
                tint = Color(0xFF2E9D50),
                modifier = Modifier.size(layout.dp(18)),
            )
        }
    }
}
