package com.appmanager

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Background check: load the catalog and notify if anything can be updated. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        return try {
            val apps = AppRepository.load(
                applicationContext, prefs.sources, prefs.favoriteDevs, prefs.localDir
            )
            val updates = apps.filter { it.hasUpdate }
            if (updates.isNotEmpty()) Notifier.showUpdates(applicationContext, updates)
            else Notifier.clear(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
