package com.ts10.inspector.dashboard

import com.ts10.inspector.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Text
import com.ts10.inspector.panel.InstalledApplication
import com.ts10.inspector.panel.launchApplicationInContentArea
import com.ts10.inspector.panel.rememberInstalledApplications
import com.ts10.inspector.settings.DashboardPreferences
import com.ts10.inspector.ui.LocalDashboardColors

@Composable
internal fun ContentPanel(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = LocalDashboardColors.current
    val settings by DashboardPreferences.get(context).state.collectAsStateWithLifecycle()
    val installedApplications = rememberInstalledApplications()
    val configuredPackages = remember(settings.navigationPackage, settings.panelPackages) {
        (listOf(settings.navigationPackage) + settings.panelPackages).distinct()
    }
    val panelApplications = configuredPackages.mapNotNull { packageName ->
        installedApplications.firstOrNull { it.packageName == packageName }
    }

    Box(
        modifier = modifier.background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.size(340.dp, 245.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.panel_right_title),
                color = colors.primary,
                fontWeight = FontWeight.Light,
                fontSize = 24.sp,
            )
            Text(
                text = stringResource(R.string.panel_right_choose),
                color = colors.muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 5.dp, bottom = 20.dp),
            )
            if (panelApplications.isEmpty()) {
                Text(
                    text = stringResource(R.string.panel_right_configure),
                    color = colors.muted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.size(260.dp, 48.dp),
                )
            } else {
                panelApplications.take(6).chunked(3).forEach { rowApplications ->
                    Row(
                        modifier = Modifier.size(330.dp, 92.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        rowApplications.forEach { application ->
                            PanelApplicationItem(application) {
                                launchApplicationInContentArea(
                                    context,
                                    application.packageName,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelApplicationItem(application: InstalledApplication, onClick: () -> Unit) {
    val colors = LocalDashboardColors.current
    val painter = remember(application.packageName) {
        BitmapPainter(application.icon.toBitmap(96, 96).asImageBitmap())
    }
    Column(
        modifier = Modifier
            .size(88.dp, 84.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painter,
            contentDescription = application.label,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = application.label,
            color = colors.primary,
            fontSize = 11.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 5.dp).size(86.dp, 18.dp),
        )
    }
}

internal const val DEFAULT_NAVIGATION_PACKAGE = "com.google.android.apps.maps"
