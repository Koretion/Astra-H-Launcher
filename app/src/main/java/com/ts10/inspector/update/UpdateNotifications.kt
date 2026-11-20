package com.ts10.inspector.update

import androidx.annotation.StringRes
import com.ts10.inspector.ui.AppLocale
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.ts10.inspector.R

/** Qué hacer al encontrar una versión nueva, del launcher o del firmware GMLAN-USB. */
internal enum class UpdateMode(@StringRes val label: Int) {
    OFF(R.string.update_mode_off),
    NOTIFY(R.string.update_mode_notify),
    DOWNLOAD(R.string.update_mode_download),
    INSTALL(R.string.update_mode_install),
}

private const val CHANNEL_ID = "launcher_updates"

/** Aviso de actualización; al tocarlo se envía [action] a [LauncherUpdateReceiver]. */
internal fun showUpdateNotification(context: Context, id: Int, title: String, text: String, action: String) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, AppLocale.string(context, R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT),
    )
    val tap = PendingIntent.getBroadcast(
        context,
        action.hashCode(),
        Intent(context, LauncherUpdateReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    manager.notify(
        id,
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build(),
    )
}
