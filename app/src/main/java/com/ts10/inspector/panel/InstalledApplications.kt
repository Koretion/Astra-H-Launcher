package com.ts10.inspector.panel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class InstalledApplication(
    val label: String,
    val packageName: String,
    val icon: Drawable,
)

private fun loadInstalledApplications(context: Context): List<InstalledApplication> {
    val packageManager = context.packageManager
    val launchableIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return packageManager.queryIntentActivities(launchableIntent, 0)
        .asSequence()
        .filter { it.activityInfo.packageName != context.packageName }
        .distinctBy { it.activityInfo.packageName }
        .map {
            InstalledApplication(
                label = it.loadLabel(packageManager).toString(),
                packageName = it.activityInfo.packageName,
                icon = it.loadIcon(packageManager),
            )
        }
        .sortedBy { it.label.lowercase() }
        .toList()
}

/**
 * Shared list of launchable apps. It is reloaded when a screen that lists apps is opened and
 * whenever Android installs, updates or removes a package.
 */
private object InstalledApplicationCatalog {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableApplications = MutableStateFlow<List<InstalledApplication>>(emptyList())
    val applications: StateFlow<List<InstalledApplication>> = mutableApplications.asStateFlow()
    private var receiverRegistered = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refresh(context.applicationContext)
        }
    }

    fun refresh(context: Context) {
        val applicationContext = context.applicationContext
        registerPackageReceiver(applicationContext)
        scope.launch {
            mutex.withLock {
                mutableApplications.value = loadInstalledApplications(applicationContext)
            }
        }
    }

    @Synchronized
    private fun registerPackageReceiver(context: Context) {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }
}

/** PackageManager enumeration and icon loading are intentionally kept off the UI thread. */
@Composable
internal fun rememberInstalledApplications(): List<InstalledApplication> {
    val applicationContext = LocalContext.current.applicationContext
    LaunchedEffect(applicationContext) { InstalledApplicationCatalog.refresh(applicationContext) }
    val applications by InstalledApplicationCatalog.applications.collectAsStateWithLifecycle()
    return applications
}

internal fun applicationLabel(
    applications: List<InstalledApplication>,
    packageName: String?,
    fallback: String,
): String = applications.firstOrNull { it.packageName == packageName }?.label ?: fallback
