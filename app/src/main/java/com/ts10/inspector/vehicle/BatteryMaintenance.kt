package com.ts10.inspector.vehicle

import com.ts10.inspector.ui.AppLocale
import androidx.annotation.StringRes
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ts10.inspector.MainActivity
import com.ts10.inspector.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal enum class BatteryMaintenanceLevel {
    NOT_CONFIGURED,
    OK,
    WARNING,
    OVERDUE,
}

internal data class BatteryMaintenanceState(
    val installationDate: LocalDate? = null,
    val lifetimeYears: Int = 4,
    val warningMonths: Int = 3,
    val replacementDate: LocalDate? = null,
    val warningDate: LocalDate? = null,
    val daysUntilReplacement: Long? = null,
    val level: BatteryMaintenanceLevel = BatteryMaintenanceLevel.NOT_CONFIGURED,
) {
    @get:StringRes
    val statusText: Int
        get() = when (level) {
            BatteryMaintenanceLevel.NOT_CONFIGURED -> R.string.battery_not_configured
            BatteryMaintenanceLevel.OK -> R.string.battery_ok
            BatteryMaintenanceLevel.WARNING -> R.string.battery_warning
            BatteryMaintenanceLevel.OVERDUE -> R.string.battery_overdue
        }

}

internal fun calculateBatteryMaintenance(
    installationDate: LocalDate?,
    lifetimeYears: Int,
    warningMonths: Int,
    today: LocalDate,
): BatteryMaintenanceState {
    val safeYears = lifetimeYears.coerceIn(1, 10)
    val safeWarning = warningMonths.coerceIn(1, 24)
    val replacement = installationDate?.plusYears(safeYears.toLong())
    val warning = replacement?.minusMonths(safeWarning.toLong())
    val level = when {
        replacement == null || warning == null -> BatteryMaintenanceLevel.NOT_CONFIGURED
        !today.isBefore(replacement) -> BatteryMaintenanceLevel.OVERDUE
        !today.isBefore(warning) -> BatteryMaintenanceLevel.WARNING
        else -> BatteryMaintenanceLevel.OK
    }
    return BatteryMaintenanceState(
        installationDate = installationDate,
        lifetimeYears = safeYears,
        warningMonths = safeWarning,
        replacementDate = replacement,
        warningDate = warning,
        daysUntilReplacement = replacement?.let { ChronoUnit.DAYS.between(today, it) },
        level = level,
    )
}

internal class BatteryMaintenancePreferences private constructor(private val context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(read())
    val state: StateFlow<BatteryMaintenanceState> = mutableState.asStateFlow()

    fun refresh() {
        mutableState.value = read()
        notifyIfNeeded(mutableState.value)
    }

    fun setInstallationDate(date: LocalDate?) = update(KEY_INSTALLATION_DATE, date?.toString())

    fun setLifetimeYears(years: Int) {
        preferences.edit().putInt(KEY_LIFETIME_YEARS, years.coerceIn(1, 10)).apply()
        refresh()
    }

    fun setWarningMonths(months: Int) {
        preferences.edit().putInt(KEY_WARNING_MONTHS, months.coerceIn(1, 24)).apply()
        refresh()
    }

    private fun update(key: String, value: String?) {
        preferences.edit().also { editor ->
            if (value == null) editor.remove(key) else editor.putString(key, value)
        }.apply()
        refresh()
    }

    private fun read(): BatteryMaintenanceState = calculateBatteryMaintenance(
        installationDate = preferences.getString(KEY_INSTALLATION_DATE, null).toLocalDateOrNull(),
        lifetimeYears = preferences.getInt(KEY_LIFETIME_YEARS, DEFAULT_LIFETIME_YEARS),
        warningMonths = preferences.getInt(KEY_WARNING_MONTHS, DEFAULT_WARNING_MONTHS),
        today = LocalDate.now(),
    )

    private fun notifyIfNeeded(state: BatteryMaintenanceState) {
        if (state.level != BatteryMaintenanceLevel.WARNING && state.level != BatteryMaintenanceLevel.OVERDUE) {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val replacementKey = "${state.replacementDate}:${state.level}"
        if (preferences.getString(KEY_LAST_NOTIFICATION, null) == replacementKey) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, AppLocale.string(context, R.string.battery_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (state.level == BatteryMaintenanceLevel.OVERDUE) {
            AppLocale.string(context, R.string.battery_notify_overdue)
        } else {
            AppLocale.string(context, R.string.battery_notify_warning)
        }
        val message = AppLocale.string(context, R.string.battery_notify_message, state.replacementDate.toString())
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.icon_fuel)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build(),
        )
        preferences.edit().putString(KEY_LAST_NOTIFICATION, replacementKey).apply()
    }

    companion object {
        const val DEFAULT_LIFETIME_YEARS = 4
        const val DEFAULT_WARNING_MONTHS = 3
        private const val FILE_NAME = "battery_maintenance_preferences"
        private const val KEY_INSTALLATION_DATE = "installation_date"
        private const val KEY_LIFETIME_YEARS = "lifetime_years"
        private const val KEY_WARNING_MONTHS = "warning_months"
        private const val KEY_LAST_NOTIFICATION = "last_notification"
        private const val CHANNEL_ID = "battery_maintenance"
        private const val NOTIFICATION_ID = 412

        @Volatile private var instance: BatteryMaintenancePreferences? = null

        fun get(context: Context): BatteryMaintenancePreferences = instance ?: synchronized(this) {
            instance ?: BatteryMaintenancePreferences(context.applicationContext).also { instance = it }
        }
    }
}

private fun String?.toLocalDateOrNull(): LocalDate? =
    this?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() }
