package com.ts10.inspector.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.R
import com.ts10.inspector.launcher.LauncherActions
import com.ts10.inspector.launcher.LauncherRuntime
import com.ts10.inspector.launcher.MediaUiState
import com.ts10.inspector.panel.InstalledApplication
import com.ts10.inspector.settings.DashboardPreferences
import com.ts10.inspector.ui.DashboardColors
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.ui.LocalDashboardColors

@Composable
internal fun RuntimeMediaSection(
    actions: LauncherActions,
    installedApplications: List<InstalledApplication>,
    layout: LauncherLayout,
    colors: DashboardColors,
) {
    val state by LauncherRuntime.mediaState.collectAsStateWithLifecycle()
    MediaSection(actions, state, installedApplications, layout, colors)
}

@Composable
private fun MediaSection(
    actions: LauncherActions,
    state: MediaUiState,
    installedApplications: List<InstalledApplication>,
    layout: LauncherLayout,
    colors: DashboardColors,
) {
    val context = LocalContext.current
    val activeMediaPackage = state.mediaPackage ?: DashboardPreferences.current(context).mediaPackage
    val activeMediaApplication = remember(activeMediaPackage, installedApplications) {
        resolveMediaApplication(context, activeMediaPackage, installedApplications)
    }
    val mediaText = state.mediaTitle?.takeIf(String::isNotBlank)
        ?: state.mediaArtist?.takeIf(String::isNotBlank)
    val foreground = if (colors.dark) Color.White else colors.actionForeground
    val titleColor = if (colors.dark) Color(0xFFD4D8E0) else Color(0xFF5C6679)
    val buttonColor = if (colors.dark) Color(0xFF252A31) else colors.actionBackground

    if (mediaText != null) {
        Text(
            text = mediaText,
            color = titleColor,
            fontSize = layout.sp(15),
            lineHeight = layout.sp(16),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .offset(layout.dp(94), layout.dp(MediaTop + 9f))
                .size(layout.dp(207), layout.dp(23)),
        )
    }

    MediaReferenceButton(
        x = 33f,
        y = 33f,
        size = 223f,
        radius = 22f,
        onClick = actions.onOpenMedia,
        buttonColor = buttonColor,
        layout = layout,
    ) {
        MediaApplicationIcon(activeMediaApplication, layout)
    }
    MediaReferenceButton(316f, 123f, 104f, 18f, actions.onPrevious, buttonColor, layout) {
        ReferenceIcon(R.drawable.icon_back, 54f, 54f, foreground, layout)
    }
    MediaReferenceButton(486f, 123f, 104f, 18f, actions.onPlayPause, buttonColor, layout) {
        if (state.mediaPlaying) {
            ReferenceIcon(R.drawable.icon_pause, 54f, 54f, foreground, layout)
        } else {
            ReferenceIcon(R.drawable.icon_play, 54f, 54f, foreground, layout)
        }
    }
    MediaReferenceButton(656f, 123f, 104f, 18f, actions.onNext, buttonColor, layout) {
        ReferenceIcon(R.drawable.icon_next, 54f, 54f, foreground, layout)
    }
    MediaReferenceButton(826f, 123f, 104f, 18f, actions.onSelectMediaSource, buttonColor, layout) {
        ReferenceIcon(R.drawable.icon_eject, 54f, 54f, foreground, layout)
    }
}

@Composable
private fun MediaApplicationIcon(application: InstalledApplication?, layout: LauncherLayout) {
    if (application == null) {
        MusicNoteIcon(layout)
        return
    }
    val painter = remember(application.packageName) {
        BitmapPainter(application.icon.toBitmap(192, 192).asImageBitmap())
    }
    Image(
        painter = painter,
        contentDescription = application.label,
        modifier = Modifier.size(layout.dp(174f * ReferenceScale)),
    )
}

private fun resolveMediaApplication(
    context: android.content.Context,
    packageName: String?,
    installedApplications: List<InstalledApplication>,
): InstalledApplication? = resolveApplication(context, packageName, installedApplications)

private fun resolveApplication(
    context: android.content.Context,
    packageName: String?,
    installedApplications: List<InstalledApplication>,
): InstalledApplication? {
    if (packageName == null) return null
    installedApplications.firstOrNull { it.packageName == packageName }?.let { return it }
    return runCatching {
        val packageManager = context.packageManager
        val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
        InstalledApplication(
            label = applicationInfo.loadLabel(packageManager).toString(),
            packageName = packageName,
            icon = applicationInfo.loadIcon(packageManager),
        )
    }.getOrNull()
}

@Composable
private fun MusicNoteIcon(layout: LauncherLayout) {
    val colors = LocalDashboardColors.current
    val tint = if (colors.dark) Color.White else colors.actionForeground
    Canvas(modifier = Modifier.size(layout.dp(126f * ReferenceScale))) {
        val stem = size.width * 0.09f
        val leftX = size.width * 0.34f
        val rightX = size.width * 0.76f
        drawLine(tint, Offset(leftX, size.height * 0.19f), Offset(leftX, size.height * 0.72f), stem, StrokeCap.Round)
        drawLine(tint, Offset(rightX, size.height * 0.10f), Offset(rightX, size.height * 0.63f), stem, StrokeCap.Round)
        val beam = Path().apply {
            moveTo(leftX - stem / 2f, size.height * 0.16f)
            lineTo(rightX + stem / 2f, size.height * 0.07f)
            lineTo(rightX + stem / 2f, size.height * 0.24f)
            lineTo(leftX - stem / 2f, size.height * 0.33f)
            close()
        }
        drawPath(beam, tint)
        drawOval(
            tint,
            topLeft = Offset(size.width * 0.08f, size.height * 0.65f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.34f, size.height * 0.22f),
        )
        drawOval(
            tint,
            topLeft = Offset(size.width * 0.50f, size.height * 0.56f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.34f, size.height * 0.22f),
        )
    }
}

@Composable
private fun MediaReferenceButton(
    x: Float,
    y: Float,
    size: Float,
    radius: Float,
    onClick: () -> Unit,
    buttonColor: Color,
    layout: LauncherLayout,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .offset(layout.dp(x * ReferenceScale), layout.dp(MediaTop + y * ReferenceScale))
            .size(layout.dp(size * ReferenceScale))
            .background(buttonColor, RoundedCornerShape(layout.dp(radius * ReferenceScale)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun ReferenceIcon(
    drawable: Int,
    width: Float,
    height: Float,
    tint: Color,
    layout: LauncherLayout,
) {
    Image(
        painter = painterResource(drawable),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = Modifier.size(layout.dp(width * ReferenceScale), layout.dp(height * ReferenceScale)),
    )
}

