package com.ts10.inspector.camera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.ts10.inspector.launcher.HybridOverlayService
import com.ts10.inspector.ui.AstraDashboardTheme
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.vision.LocalVisionPipeline

class UsbCameraFullScreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocalVisionPipeline.releaseUsbForViewer()
        setContent {
            AstraDashboardTheme {
                ExternalCameraLayer(
                    layout = LauncherLayout(scale = 1f),
                    requestPermission = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            LocalVisionPipeline.reclaimUsbAfterViewer()
            HybridOverlayService.showDashboard(this)
        }
        super.onDestroy()
    }
}
