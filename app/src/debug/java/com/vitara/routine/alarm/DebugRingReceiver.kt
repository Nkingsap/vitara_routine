package com.vitara.routine.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vitara.routine.data.BlockEvent
import com.vitara.routine.data.RoutineBlock
import com.vitara.routine.data.RoutineRepository
import com.vitara.routine.util.TimeText
import java.time.LocalTime

/**
 * Debug builds only — never shipped in a release APK.
 *
 * Rings (or dismisses) immediately so the complete alarm chain can be verified
 * from adb without tapping anything:
 *
 * ```
 * adb shell am broadcast -a com.vitara.routine.DEBUG_RING -n com.vitara.routine/.alarm.DebugRingReceiver
 * adb shell am broadcast -a com.vitara.routine.DEBUG_RING --ez dismiss true -n com.vitara.routine/.alarm.DebugRingReceiver
 * ```
 */
class DebugRingReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DebugRingReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("dismiss", false)) {
            RingController.dismiss(context, AlarmScheduler.TEST_BLOCK_ID)
            return
        }

        if (intent.getBooleanExtra("timetable", false)) {
            val everyDay = intent.getBooleanExtra("alldays", false)
            val days = if (everyDay) RoutineBlock.EVERY_DAY else setOf(1, 2)
            val added = RoutineRepository.installTimetable(context, days)
            AlarmScheduler.scheduleAll(context)
            Log.i(TAG, "Timetable (days=$days): added $added routine(s), total ${RoutineRepository.all(context).size}")
            return
        }

        val now = LocalTime.now()
        val minute = now.hour * 60 + now.minute
        val event = if (intent.getBooleanExtra("end", false)) BlockEvent.END else BlockEvent.START
        val block = RoutineBlock(
            id = AlarmScheduler.TEST_BLOCK_ID,
            title = intent.getStringExtra("title") ?: "Preview alarm",
            message = "Triggered from adb at ${TimeText.nowClock()}.",
            startMinute = minute,
            endMinute = (minute + 1) % RoutineBlock.MINUTES_PER_DAY,
            days = RoutineBlock.EVERY_DAY,
            colorIndex = 1,
            vibrate = !intent.getBooleanExtra("novibrate", false),
            ringAtEnd = false,
            fullScreen = true,
            enabled = true
        )
        RingController.startRinging(context, RingRequest(block, event, test = true))
    }
}
