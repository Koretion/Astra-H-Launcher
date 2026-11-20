package com.ts10.inspector.ui

import androidx.compose.ui.platform.LocalConfiguration
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Immutable
internal data class DashboardClock(val dateLabel: String, val timeLabel: String)

@Composable
internal fun rememberSystemClock(): DashboardClock {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var clock by remember(locale) { mutableStateOf(currentSystemClock(locale)) }

    DisposableEffect(context, locale) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                clock = currentSystemClock(locale)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return clock
}

private fun currentSystemClock(locale: Locale): DashboardClock {
    val now = ZonedDateTime.now()
    val date = now.format(DateTimeFormatter.ofPattern("EEEE d", locale))
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    return DashboardClock(
        dateLabel = date,
        timeLabel = now.format(DateTimeFormatter.ofPattern("HH:mm", locale)),
    )
}
