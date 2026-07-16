package com.appmanager

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Schedules (or cancels) the periodic background update check per [Prefs.autoUpdateMode]. */
object UpdateScheduler {
    private const val WORK = "auto_update_check"

    fun apply(context: Context, mode: Int) {
        val wm = WorkManager.getInstance(context)
        if (mode == Prefs.AUTO_OFF) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val hours = if (mode == Prefs.AUTO_WEEKLY) 24L * 7 else 24L
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(hours, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
