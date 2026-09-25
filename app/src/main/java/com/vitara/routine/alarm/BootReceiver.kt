package com.vitara.routine.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms every routine after a reboot, an app update, a clock/timezone change
 * or when the exact alarm permission is granted again. Alarms never survive a
 * reboot on their own, so this receiver is what makes routines reliable.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.scheduleAll(context)
    }
}
