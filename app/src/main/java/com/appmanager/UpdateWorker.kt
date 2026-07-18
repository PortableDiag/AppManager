package com.appmanager

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Background check: load the catalog, auto-install updates for apps the user opted in, and
 * notify about any remaining updates. Auto-install runs silently only for apps App Manager
 * installed (Android 12+); otherwise the install step posts its own tap-to-confirm
 * notification, so those apps are dropped from the generic "updates available" list here.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        return try {
            val apps = AppRepository.load(
                applicationContext, prefs.sources, prefs.favoriteDevs, prefs.localDir
            )
            val updates = apps.filter { it.hasUpdate }
            val (auto, rest) = updates.partition {
                it.source != null && prefs.isAutoUpdate(it.packageName)
            }

            val failed = mutableListOf<ManagedApp>()
            for (app in auto) {
                try {
                    autoInstall(app)
                } catch (_: Exception) {
                    // Couldn't fetch/stage this one — fall back to notifying about it.
                    failed += app
                }
            }

            // Notify about updates we didn't (successfully) auto-install. Successful auto ones
            // either install silently or raise their own tap-to-confirm notification.
            val notify = rest + failed
            if (notify.isNotEmpty()) Notifier.showUpdates(applicationContext, notify)
            else Notifier.clear(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private suspend fun autoInstall(app: ManagedApp) {
        val apk = when (val src = app.source) {
            is ApkSource.Local -> src.file
            is ApkSource.Remote -> Installer.download(applicationContext, src.url) {}
            null -> return
        }
        Installer.install(applicationContext, apk, app.packageName, app.label, background = true)
    }
}
