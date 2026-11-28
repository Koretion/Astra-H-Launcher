package com.ts10.inspector.ui

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

@Immutable
internal data class DashboardClock(val dateLabel: String, val timeLabel: String)

@Composable
internal fun rememberSystemClock(): DashboardClock {
    val context = LocalContext.current
    val formats = rememberDisplayFormats()
    var clock by remember(formats) { mutableStateOf(currentSystemClock(formats)) }

    DisposableEffect(context, formats) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                clock = currentSystemClock(formats)
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

private fun currentSystemClock(formats: DisplayFormats): DashboardClock {
    val now = ZonedDateTime.now()
    return DashboardClock(dateLabel = formats.dashboardDate(now), timeLabel = formats.time(now))
}
