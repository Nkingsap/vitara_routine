package com.vitara.routine.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the Dismiss action on the alarm notification. */
class AlarmActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val blockId = intent.getIntExtra(AlarmScheduler.EXTRA_BLOCK_ID, -1)
        if (blockId == -1) return
        when (intent.action) {
            ACTION_DISMISS -> RingController.dismiss(context, blockId)
            ACTION_SNOOZE -> {
                val request = RingRequest.from(intent, context) ?: RingService.current
                if (request != null) {
                    RingController.snooze(context, request)
                } else {
                    RingController.dismiss(context, blockId)
                }
            }
        }
    }

    companion object {
        const val ACTION_DISMISS = "com.vitara.routine.action.NOTIFICATION_DISMISS"
        const val ACTION_SNOOZE = "com.vitara.routine.action.NOTIFICATION_SNOOZE"
    }
}
