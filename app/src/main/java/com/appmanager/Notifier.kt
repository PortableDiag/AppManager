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

    /**
     * A background auto-update that failed outright. Nothing is listening for the install
     * broadcast when no activity is running, so without this the failure is invisible and
     * silently repeats on every check.
     */
    fun showInstallFailed(context: Context, pkg: String?, label: String, message: String?) {
        ensureChannel(context)
        val key = pkg ?: label
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_SHOW_UPDATES, true)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(context, installFailedId(key), openIntent, flags)

        val body = message?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.update_failed_text)
        val notif = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle(context.getString(R.string.update_failed_title, label))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(installFailedId(key), notif)
        } catch (_: SecurityException) {
        }
    }

    /** Drop the tap-to-install prompt for [key] once its install has resolved. */
    fun clearInstallAction(context: Context, key: String) {
        NotificationManagerCompat.from(context).cancel(installActionId(key))
    }

    private fun installActionId(key: String): Int = 2000 + (key.hashCode() and 0xFFFF)

    private fun installFailedId(key: String): Int = 70000 + (key.hashCode() and 0xFFFF)

    /**
     * Dismiss everything this app has posted. Called when the user opens App Manager: the
     * list itself now shows what's pending, so leaving stale update/install prompts behind
     * would only strand them (a tapped-later prompt points at an abandoned session).
     */
    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
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
