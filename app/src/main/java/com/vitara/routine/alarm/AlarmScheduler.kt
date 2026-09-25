package com.vitara.routine.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.vitara.routine.data.BlockEvent
import com.vitara.routine.data.RoutineBlock
import com.vitara.routine.data.RoutineRepository
import com.vitara.routine.data.Schedule
import com.vitara.routine.ui.MainActivity
import org.json.JSONObject
import android.util.Log

/**
 * Arms routines with [AlarmManager].
 *
 * `setAlarmClock()` is used whenever exact alarms are allowed: it is exact, it
 * fires in Doze, it survives battery optimisation and it shows the upcoming
 * alarm to the user in the status bar. When the permission is missing (Android
 * 12/12L after the user revoked it) the alarm is still set with
 * `setAndAllowWhileIdle()` so a routine is never silently dropped.
 */
object AlarmScheduler {

    const val EXTRA_BLOCK_ID = "com.vitara.routine.extra.BLOCK_ID"
    const val EXTRA_BLOCK_JSON = "com.vitara.routine.extra.BLOCK_JSON"
    const val EXTRA_EVENT = "com.vitara.routine.extra.EVENT"
    const val EXTRA_TEST = "com.vitara.routine.extra.TEST"
    const val EXTRA_SNOOZE = "com.vitara.routine.extra.SNOOZE"

    /** Synthetic id used by the "Test alarm" button. */
    const val TEST_BLOCK_ID = -99
    private const val TEST_REQUEST_BASE = 990_000
    private const val SNOOZE_REQUEST_BASE = 980_000

    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun canScheduleExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    /** Re-arms every routine. Called on app start, on boot and when permissions change. */
    fun scheduleAll(context: Context) {
        AlarmNotifications.ensureChannels(context)
        RoutineRepository.all(context).forEach { schedule(context, it) }
    }

    fun schedule(context: Context, block: RoutineBlock) {
        cancel(context, block.id)
        if (!block.enabled || block.days.isEmpty()) return
        for (event in BlockEvent.entries) {
            val at = Schedule.nextOccurrence(block, event) ?: continue
            set(context, block, event, at.toInstant().toEpochMilli(), test = false)
        }
    }

    fun cancel(context: Context, blockId: Int) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        for (event in BlockEvent.entries) {
            am.cancel(operation(context, blockId, null, event, test = false))
        }
    }

    /** Rings in [delaySeconds] seconds so sound + vibration + full screen can be verified. */
    fun scheduleTest(context: Context, block: RoutineBlock, delaySeconds: Long = 5) {
        set(context, block, BlockEvent.START, System.currentTimeMillis() + delaySeconds * 1000, test = true)
    }

    /** Rings the same moment again after the user pressed Snooze. */
    fun scheduleSnooze(context: Context, block: RoutineBlock, event: BlockEvent, minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        set(context, block, event, at, test = false, snooze = true)
    }

    fun blockFromIntent(intent: Intent, context: Context): RoutineBlock? {
        val json = intent.getStringExtra(EXTRA_BLOCK_JSON)
        if (!json.isNullOrEmpty()) {
            try {
                return RoutineBlock.fromJson(JSONObject(json))
            } catch (_: Throwable) {
                // fall through to the stored routine
            }
        }
        return RoutineRepository.get(context, intent.getIntExtra(EXTRA_BLOCK_ID, -1))
    }

    fun eventFrom(intent: Intent): BlockEvent = BlockEvent.from(intent.getStringExtra(EXTRA_EVENT))

    fun putBlock(intent: Intent, block: RoutineBlock): Intent =
        intent.putExtra(EXTRA_BLOCK_ID, block.id).putExtra(EXTRA_BLOCK_JSON, block.toJson().toString())

    private fun set(
        context: Context,
        block: RoutineBlock,
        event: BlockEvent,
        triggerAt: Long,
        test: Boolean,
        snooze: Boolean = false
    ) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val operation = operation(context, block.id, block, event, test, snooze)
        val exact = canScheduleExact(context)
        if (exact) {
            val showIntent = PendingIntent.getActivity(
                context,
                requestCode(block.id, event, test, snooze) + 7,
                Intent(context, MainActivity::class.java),
                FLAGS
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), operation)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        }
        Log.i(
            TAG,
            "Armed \"${block.title}\" ${event.name} for " +
                java.text.DateFormat.getDateTimeInstance().format(java.util.Date(triggerAt)) +
                if (exact) " (exact)" else " (inexact - grant \"Alarms & reminders\")"
        )
    }

    private const val TAG = "AlarmScheduler"

    private fun operation(
        context: Context,
        blockId: Int,
        block: RoutineBlock?,
        event: BlockEvent,
        test: Boolean,
        snooze: Boolean = false
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.vitara.routine.ACTION_ALARM"
            putExtra(EXTRA_BLOCK_ID, blockId)
            putExtra(EXTRA_EVENT, event.name)
            putExtra(EXTRA_TEST, test)
            putExtra(EXTRA_SNOOZE, snooze)
            if (block != null) putBlock(this, block)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(blockId, event, test, snooze),
            intent,
            FLAGS
        )
    }

    private fun requestCode(blockId: Int, event: BlockEvent, test: Boolean, snooze: Boolean = false): Int =
        when {
            test -> TEST_REQUEST_BASE + event.ordinal
            snooze -> SNOOZE_REQUEST_BASE + event.ordinal
            else -> Math.abs(blockId) * 2 + event.ordinal
        }
}
