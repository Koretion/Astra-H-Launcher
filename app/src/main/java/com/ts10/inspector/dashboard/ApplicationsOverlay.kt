package com.ts10.inspector.dashboard

import androidx.compose.ui.res.stringResource
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.core.graphics.drawable.toBitmap
import com.ts10.inspector.panel.InstalledApplication
import com.ts10.inspector.panel.rememberInstalledApplications
import com.ts10.inspector.settings.DashboardPreferences
import com.ts10.inspector.ui.DashboardAssetIcon
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.ui.LocalDashboardColors
import com.ts10.inspector.ui.modalCornerShape
import com.ts10.inspector.ui.rightPanelModalFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ts10.inspector.R

@Composable
internal fun ApplicationsOverlay(
    layout: LauncherLayout,
    onDismiss: () -> Unit,
    onLaunchApplication: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val settingsLabel = stringResource(R.string.apps_settings_entry)
    val settingsEntry = remember(context, settingsLabel) {
        InstalledApplication(
            label = settingsLabel,
            packageName = context.packageName,
            icon = context.packageManager.getApplicationIcon(context.packageName),
        )
    }
    val colors = LocalDashboardColors.current
    val settings = DashboardPreferences.current(context)
    val installedApplications = rememberInstalledApplications()
    val applications by produceState(
        initialValue = ApplicationSections(emptyList(), emptyList(), emptyList()),
        key1 = settings.favoritePackages,
        key2 = installedApplications,
    ) {
        value = if (installedApplications.isEmpty()) {
            ApplicationSections(emptyList(), emptyList(), emptyList())
        } else {
            withContext(Dispatchers.IO) {
                loadApplications(context, settings.favoritePackages, installedApplications)
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(onClick = onDismiss),
    ) {
        // La ventana empieza bajo la barra de estado: el marco usa su altura real.
        val frame = rightPanelModalFrame(maxWidth.value / layout.scale, maxHeight.value / layout.scale)
        val contentWidth = frame.width - 28f
        val gridHeight = frame.height - 62f
        Column(
            modifier = Modifier
                .offset(layout.dp(frame.x), layout.dp(frame.y))
                .size(layout.dp(frame.width), layout.dp(frame.height))
                .clip(modalCornerShape(layout))
                .background(colors.modalBackground)
                .clickable { }
                .padding(horizontal = layout.dp(14), vertical = layout.dp(18)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = layout.dp(8)),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DashboardAssetIcon(
                        drawable = R.drawable.icon_app_list,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(layout.dp(24)),
                    )
                    Text(
                        text = stringResource(R.string.apps_title),
                        color = colors.primary,
                        fontSize = layout.sp(12),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = layout.dp(8)),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(layout.dp(12)), verticalAlignment = Alignment.CenterVertically) {
                    DashboardAssetIcon(
                        drawable = R.drawable.ic_modal_close,
                        contentDescription = stringResource(R.string.common_close),
                        tint = colors.primary,
                        modifier = Modifier
                            .size(layout.dp(24))
                            .clickable(onClick = onDismiss),
                    )
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.size(layout.dp(contentWidth), layout.dp(gridHeight)),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalArrangement = Arrangement.spacedBy(layout.dp(8)),
            ) {
                // The launcher hides itself from the app list; this tile opens its own settings.
                item { ApplicationItem(settingsEntry, layout, onClick = onOpenSettings) }
                items(applications.all.size) { index ->
                    val application = applications.all[index]
                    ApplicationItem(application, layout) {
                        onLaunchApplication(application.packageName)
                    }
                }
            }
        }
    }
}

@Immutable
private data class ApplicationSections(
    val favorites: List<InstalledApplication>,
    val others: List<InstalledApplication>,
    val all: List<InstalledApplication>,
)

@Composable
private fun ApplicationItem(
    application: InstalledApplication,
    layout: LauncherLayout,
    onClick: () -> Unit,
) {
    val colors = LocalDashboardColors.current
    val painter = remember(application.packageName) {
        BitmapPainter(application.icon.toBitmap(96, 96).asImageBitmap())
    }
    Column(
        modifier = Modifier
            .size(layout.dp(82), layout.dp(84))
            .clickable(onClick = onClick)
            .padding(top = layout.dp(5)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painter,
            contentDescription = application.label,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(layout.dp(48)),
        )
        Text(
            text = application.label,
            color = colors.primary,
            fontSize = layout.sp(10),
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.size(layout.dp(78), layout.dp(22)),
        )
    }
}

private fun loadApplications(
    context: Context,
    favoritePackages: List<String>,
    installedApplications: List<InstalledApplication>,
): ApplicationSections {
    val usage = foregroundUsage(context)
    val installed = installedApplications
        .sortedWith(
            compareByDescending<InstalledApplication> { usage[it.packageName] ?: 0L }
                .thenBy { it.label.lowercase() },
        )
    val favorites = favoritePackages.mapNotNull { favorite ->
        installed.firstOrNull { it.packageName == favorite }
    }.take(DashboardPreferences.MAX_FAVORITES)
    val others = buildList {
        addAll(installed.filterNot { application -> application.packageName in favoritePackages })
    }
    return ApplicationSections(
        favorites = favorites,
        others = others,
        all = favorites + others,
    )
}

private fun foregroundUsage(context: Context): Map<String, Long> {
    val manager = context.getSystemService(UsageStatsManager::class.java)
    val now = System.currentTimeMillis()
    return manager.queryUsageStats(
        UsageStatsManager.INTERVAL_MONTHLY,
        now - 30L * 24 * 60 * 60 * 1000,
        now,
    ).associate { it.packageName to it.totalTimeInForeground }
}
