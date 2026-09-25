package com.vitara.routine

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.vitara.routine.alarm.AlarmNotifications
import com.vitara.routine.alarm.AlarmScheduler

/**
 * Creates the alarm channel and re-arms every routine whenever the app starts,
 * which also repairs alarms the system dropped while the app was not running.
 */
class VitaraApp : Application(), Application.ActivityLifecycleCallbacks {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
        AlarmNotifications.ensureChannels(this)
        AlarmScheduler.scheduleAll(this)
    }

    override fun onActivityStarted(activity: Activity) {
        visibleActivities++
    }

    override fun onActivityStopped(activity: Activity) {
        if (visibleActivities > 0) visibleActivities--
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        @Volatile
        private var visibleActivities: Int = 0

        /** True while one of our screens is on top (used to open the ringing screen directly). */
        val isAppVisible: Boolean get() = visibleActivities > 0
    }
}
