package com.ts10.inspector.dashboard

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.launcher.HybridOverlayService
import com.ts10.inspector.launcher.LauncherActions
import com.ts10.inspector.launcher.LauncherRuntime
import com.ts10.inspector.panel.launchConfiguredApplication
import com.ts10.inspector.phone.AndroidBluetoothPhoneClient
import com.ts10.inspector.phone.BluetoothCallState
import com.ts10.inspector.phone.PhoneOverlay
import com.ts10.inspector.settings.DashboardSettingsOverlay
import com.ts10.inspector.ui.DesignGrid
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.ui.LocalDashboardColors
import kotlin.math.min

@Composable
fun AstraLauncherScreen(
    modifier: Modifier = Modifier,
    actions: LauncherActions = LauncherActions(),
) {
    var activeModal by rememberSaveable { mutableStateOf(LauncherModal.None) }
    var overlayPermissionRequested by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val inspectionMode = LocalInspectionMode.current
    var overlayAllowed by rememberSaveable {
        mutableStateOf(inspectionMode || Settings.canDrawOverlays(context))
    }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        overlayAllowed = Settings.canDrawOverlays(context)
    }
    val bluetoothPhoneState by AndroidBluetoothPhoneClient.state.collectAsStateWithLifecycle()
    val colors = LocalDashboardColors.current
    LaunchedEffect(inspectionMode, overlayAllowed) {
        if (!inspectionMode && overlayAllowed) {
            HybridOverlayService.showDashboard(context)
        }
    }

    LaunchedEffect(inspectionMode, overlayAllowed, overlayPermissionRequested) {
        if (!inspectionMode && !overlayAllowed && !overlayPermissionRequested) {
            overlayPermissionRequested = true
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }
    }

    LaunchedEffect(bluetoothPhoneState.callState) {
        if (bluetoothPhoneState.callState == BluetoothCallState.RINGING) {
            activeModal = LauncherModal.None
            if (overlayAllowed) {
                HybridOverlayService.showPhone(context)
            } else {
                activeModal = LauncherModal.Phone
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(colors.background)) {
        val availableWidth = this.maxWidth
        val availableHeight = this.maxHeight
        val compactTask = availableWidth < availableHeight
        val layout = LauncherLayout(
            scale = min(
                availableWidth.value / if (compactTask) {
                    DesignGrid.VehiclePanelWidth
                } else {
                    DesignGrid.Width
                },
                availableHeight.value / DesignGrid.Height,
            ),
        )

        Box(
            modifier = Modifier.size(
                width = layout.dp(
                    if (compactTask) DesignGrid.VehiclePanelWidth else DesignGrid.Width,
                ),
                height = layout.dp(DesignGrid.Height),
            ),
        ) {
            val interactiveActions = actions.copy(
                onWeather = {
                    if (compactTask && overlayAllowed) {
                        HybridOverlayService.toggleWeather(context)
                    } else {
                        activeModal = activeModal.toggle(LauncherModal.Weather)
                    }
                    actions.onWeather()
                },
                onPhone = {
                    if (compactTask && overlayAllowed) {
                        HybridOverlayService.togglePhone(context)
                    } else {
                        activeModal = activeModal.toggle(LauncherModal.Phone)
                    }
                    actions.onPhone()
                },
                onApplications = {
                    if (compactTask && overlayAllowed) {
                        HybridOverlayService.toggleApplications(context)
                    } else {
                        activeModal = activeModal.toggle(LauncherModal.Applications)
                    }
                    actions.onApplications()
                },
                onCamera = {
                    actions.onOpenCameraFullScreen()
                },
                onSelectMediaSource = {
                    if (compactTask && overlayAllowed) {
                        HybridOverlayService.toggleMediaSources(context)
                    } else {
                        activeModal = activeModal.toggle(LauncherModal.MediaSources)
                    }
                },
            )
            VehiclePanel(
                actions = interactiveActions,
                layout = layout,
                modifier = Modifier.size(
                    width = layout.dp(DesignGrid.VehiclePanelWidth),
                    height = layout.dp(DesignGrid.Height),
                ),
            )
            if (!overlayAllowed && !compactTask) {
                VehicleTopIndicators(
                    layout = layout,
                    modifier = Modifier
                        .offset(
                            x = layout.dp(
                                (DesignGrid.Width - DesignGrid.CoolantIndicatorWidth) / 2f,
                            ),
                        )
                        .size(
                            layout.dp(DesignGrid.CoolantIndicatorWidth),
                            layout.dp(DesignGrid.VehicleTopIndicatorHeight),
                        ),
                )
            }
            if (!compactTask) {
                // The former fullscreen control is intentionally gone. A future resizing
                // interaction belongs on a draggable divider at this panel boundary.
                ContentPanel(
                    modifier = Modifier
                        .offset(x = layout.dp(DesignGrid.VehiclePanelWidth))
                        .size(
                            width = layout.dp(DesignGrid.Width - DesignGrid.VehiclePanelWidth),
                            height = layout.dp(DesignGrid.Height),
                        ),
                )
                if (activeModal == LauncherModal.Phone) {
                    PhoneOverlay(
                        layout = layout,
                        onDismiss = { activeModal = LauncherModal.None },
                    )
                }
                if (activeModal == LauncherModal.Weather) {
                    val weatherState by LauncherRuntime.weatherState.collectAsStateWithLifecycle()
                    WeatherOverlay(
                        state = weatherState,
                        layout = layout,
                        onDismiss = { activeModal = LauncherModal.None },
                    )
                }
                if (activeModal == LauncherModal.Applications) {
                    ApplicationsOverlay(
                        layout = layout,
                        onDismiss = { activeModal = LauncherModal.None },
                        onLaunchApplication = { packageName ->
                            activeModal = LauncherModal.None
                            launchConfiguredApplication(context, packageName)
                        },
                        onOpenSettings = { activeModal = LauncherModal.Settings },
                    )
                }
                if (activeModal == LauncherModal.Settings) {
                    DashboardSettingsOverlay(
                        layout = layout,
                        onDismiss = { activeModal = LauncherModal.None },
                    )
                }
                if (activeModal == LauncherModal.MediaSources) {
                    MediaSourcesOverlay(
                        layout = layout,
                        onDismiss = { activeModal = LauncherModal.None },
                        onSelect = { packageName ->
                            activeModal = LauncherModal.None
                            LauncherRuntime.selectMediaSource(context, packageName)
                        },
                    )
                }
            }
        }
    }
}

private enum class LauncherModal {
    None,
    Weather,
    Applications,
    Phone,
    Settings,
    MediaSources,
}

private fun LauncherModal.toggle(target: LauncherModal): LauncherModal =
    if (this == target) LauncherModal.None else target

@Preview(widthDp = 800, heightDp = 469, showBackground = true)
@Composable
private fun AstraLauncherPreview() {
    AstraLauncherScreen()
}
