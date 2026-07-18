package com.appmanager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Posts (and clears) the "updates available" notification from the background check. */
object Notifier {
    private const val CHANNEL = "updates"
    private const val NOTIF_ID = 1001

    fun showUpdates(context: Context, updates: List<ManagedApp>) {
        if (updates.isEmpty()) { clear(context); return }
        ensureChannel(context)

        val title = if (updates.size == 1)
            context.getString(R.string.updates_available_one)
        else
            context.getString(R.string.updates_available_many, updates.size)

        val names = updates.joinToString(", ") { it.label }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_SHOW_UPDATES, true)
        }
        val updateAllIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_START_UPDATE_ALL, true)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val openPi = PendingIntent.getActivity(context, 0, openIntent, flags)
        val updateAllPi = PendingIntent.getActivity(context, 1, updateAllIntent, flags)

        val notif = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle(title)
            .setContentText(names)
            .setStyle(NotificationCompat.BigTextStyle().bigText(names))
            .setContentIntent(openPi)
            .addAction(0, context.getString(R.string.update_all), updateAllPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — nothing to do.
        }
    }

    /**
     * A per-app notification whose tap launches the system install confirmation. Used when a
     * background auto-update can't run silently (the app wasn't installed by App Manager) and
     * so still needs one tap from the user.
     */
    fun showInstallAction(context: Context, pkg: String?, label: String, confirm: Intent) {
        ensureChannel(context)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(context, (pkg ?: label).hashCode(), confirm, flags)

        val notif = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle(context.getString(R.string.update_ready_title, label))
            .setContentText(context.getString(R.string.update_ready_text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(installActionId(pkg ?: label), notif)
        } catch (_: SecurityException) {
        }
    }

    private fun installActionId(key: String): Int = 2000 + (key.hashCode() and 0xFFFF)

    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.updates_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }
}
