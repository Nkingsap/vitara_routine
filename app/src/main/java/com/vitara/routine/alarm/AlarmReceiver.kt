package com.vitara.routine.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Delivered by [AlarmScheduler]. The next occurrence of the routine is armed
 * *before* ringing, so the routine keeps running even if the user never presses
 * Dismiss.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val request = RingRequest.from(intent, context) ?: run {
            Log.w(TAG, "Alarm fired but the routine could not be read from the intent")
            return
        }
        if (!request.test && !AlarmScheduler.isMasterAlarmEnabled(context)) {
            Log.i(TAG, "Master alarm is OFF, ignoring received alarm for \"${request.block.title}\"")
            return
        }
        Log.i(TAG, "Firing ${request.event.name} of \"${request.block.title}\" (test=${request.test})")
        if (!request.test && !request.snooze) {
            if (!request.block.enabled || request.block.days.isEmpty()) return
            // Arm the next occurrence so a missed dismissal never breaks the chain.
            AlarmScheduler.schedule(context, request.block)
        }
        RingController.startRinging(context, request)
    }

    companion object {
        private const val TAG = "AlarmReceiver"
    }
}
