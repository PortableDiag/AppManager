package com.appmanager

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Tracks whether any App Manager screen is up. Android refuses to start an activity — an install
 * confirm, the uninstall dialog — for an app that is not on screen, so the local API needs to
 * know which of those it can actually show and which must become a notification instead.
 */
class AppManagerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { started++ }
            override fun onActivityStopped(activity: Activity) { started-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        com.appmanager.api.ApiService.startIfEnabled(this)
    }

    companion object {
        @Volatile
        private var started = 0

        val visible: Boolean get() = started > 0
    }
}
