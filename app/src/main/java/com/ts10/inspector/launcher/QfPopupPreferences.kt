package com.ts10.inspector.launcher

import android.content.Context
import android.content.Intent
import com.ts10.inspector.settings.DashboardPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shows or hides the QF vehicle app's own door and climate popups. */
internal object QfPopupPreferences {
    private var qfDoorPopupApplyJob: Job? = null
    private var qfClimatePopupApplyJob: Job? = null

    fun setQfDoorPopupEnabled(context: Context, enabled: Boolean) {
        applyQfDoorPopupPreference(context.applicationContext, enabled)
    }

    fun setQfClimatePopupEnabled(context: Context, enabled: Boolean) {
        applyQfClimatePopupPreference(context.applicationContext, enabled)
    }

    fun reapplyQfPopupPreferences(context: Context) {
        val appContext = context.applicationContext
        applyQfDoorPopupPreference(appContext)
        applyQfClimatePopupPreference(appContext)
    }

    private fun applyQfDoorPopupPreference(
        context: Context,
        enabled: Boolean = DashboardPreferences.current(context).qfDoorPopupEnabled,
    ) {
        qfDoorPopupApplyJob?.cancel()
        sendQfPopupPreference(context, QF_POPUP_TYPE_DOORS, enabled, "doors")
        qfDoorPopupApplyJob = retryQfPopupPreference(
            context,
            QF_POPUP_TYPE_DOORS,
            enabled,
            "doors",
        )
    }

    private fun applyQfClimatePopupPreference(
        context: Context,
        enabled: Boolean = DashboardPreferences.current(context).qfClimatePopupEnabled,
    ) {
        qfClimatePopupApplyJob?.cancel()
        sendQfPopupPreference(context, QF_POPUP_TYPE_CLIMATE, enabled, "climatizador")
        qfClimatePopupApplyJob = retryQfPopupPreference(
            context,
            QF_POPUP_TYPE_CLIMATE,
            enabled,
            "climatizador",
        )
    }

    private fun retryQfPopupPreference(
        context: Context,
        popupType: Byte,
        enabled: Boolean,
        popupName: String,
    ): Job = LauncherStore.scope.launch {
        QF_POPUP_RETRY_DELAYS_MS.forEach { retryDelay ->
            delay(retryDelay)
            sendQfPopupPreference(context, popupType, enabled, popupName)
        }
    }

    private fun sendQfPopupPreference(
        context: Context,
        popupType: Byte,
        enabled: Boolean,
        popupName: String,
    ): Boolean = runCatching {
            context.sendBroadcast(
                Intent(ACTION_QF_POPUP_ENABLE)
                    .setPackage(QF_VEHICLE_PACKAGE)
                    .putExtra(EXTRA_QF_POPUP_TYPE, popupType)
                    .putExtra(EXTRA_QF_POPUP_ENABLED, enabled),
            )
            QfDiagnosticsRecorder.addDiagnosticLog(
                if (enabled) {
                    "Command sent to restore the QF $popupName popup"
                } else {
                    "Command sent to hide the QF $popupName popup"
                },
            )
            true
        }.getOrElse { error ->
            QfDiagnosticsRecorder.addDiagnosticLog("Could not change the QF $popupName popup: ${error.message}")
            false
        }

    private const val QF_VEHICLE_PACKAGE = "com.qf.vehicle"
    private const val EXTRA_QF_POPUP_TYPE = "extra_popup_type"
    private const val EXTRA_QF_POPUP_ENABLED = "extra_popup_enable"
    private const val QF_POPUP_TYPE_CLIMATE: Byte = 1
    private const val QF_POPUP_TYPE_DOORS: Byte = 3
    private val QF_POPUP_RETRY_DELAYS_MS = longArrayOf(750L, 2_500L, 6_000L)
}
