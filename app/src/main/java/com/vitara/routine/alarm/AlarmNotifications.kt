package com.vitara.routine.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vitara.routine.R
import com.vitara.routine.data.BlockEvent
import com.vitara.routine.data.RoutineBlock
import com.vitara.routine.ui.RingActivity
import com.vitara.routine.util.TimeText

/**
 * Builds the alarm notification. It is intentionally created here (not only in
 * the service) so the alert still exists when Android refuses to start a
 * foreground service from the background: the notification channel itself rings
 * and vibrates, and carries the full screen intent that launches [RingActivity].
 */
object AlarmNotifications {

    const val CHANNEL_ALARMS = "vitara_routine_alarms"

    /** Snooze length offered on the ringing screen and the notification. */
    const val SNOOZE_MINUTES = 5

    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    val VIBRATION_PATTERN = longArrayOf(0, 700, 350, 700, 350, 1100, 500)

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ALARMS) != null) return

        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        val channel = NotificationChannel(
            CHANNEL_ALARMS,
            context.getString(R.string.channel_alarms_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_alarms_desc)
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            if (sound != null) {
                setSound(
                    sound,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        }
        nm.createNotificationChannel(channel)
    }

    fun notificationId(blockId: Int, event: BlockEvent): Int = Math.abs(blockId) * 10 + event.ordinal

    fun build(
        context: Context,
        block: RoutineBlock,
        event: BlockEvent,
        test: Boolean,
        alert: Boolean
    ): Notification {
        ensureChannels(context)

        val ringPi = ringPendingIntent(context, block, event, test)
        val snoozePi = PendingIntent.getBroadcast(
            context,
            notificationId(block.id, event) * 4 + 1,
            Intent(context, AlarmActionReceiver::class.java)
                .setAction(AlarmActionReceiver.ACTION_SNOOZE)
                .putExtra(AlarmScheduler.EXTRA_BLOCK_ID, block.id)
                .putExtra(AlarmScheduler.EXTRA_EVENT, event.name),
            FLAGS
        )
        val dismissPi = PendingIntent.getBroadcast(
            context,
            notificationId(block.id, event) * 4 + 3,
            Intent(context, AlarmActionReceiver::class.java)
                .setAction(AlarmActionReceiver.ACTION_DISMISS)
                .putExtra(AlarmScheduler.EXTRA_BLOCK_ID, block.id)
                .putExtra(AlarmScheduler.EXTRA_EVENT, event.name),
            FLAGS
        )

        val heading = when {
            test -> context.getString(R.string.ring_preview)
            event.isStart -> block.title
            else -> "${block.title} - ${context.getString(R.string.ring_ended)}"
        }
        val fallback = if (event.isStart) {
            context.getString(R.string.ring_started)
        } else {
            context.getString(R.string.ring_ended)
        }
        val line = "${block.message.ifBlank { fallback }}  ·  ${TimeText.range(block)}"

        return NotificationCompat.Builder(context, CHANNEL_ALARMS)
            .setSmallIcon(R.drawable.ic_alarm)
            .setColor(Color.parseColor(block.color.hex))
            .setContentTitle(heading)
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(!alert)
            .setContentIntent(ringPi)
            .setFullScreenIntent(ringPi, true)
            .addAction(0, context.getString(R.string.notif_snooze, SNOOZE_MINUTES), snoozePi)
            .addAction(0, context.getString(R.string.notif_dismiss), dismissPi)
            .build()
    }

    /** Posts the audible alarm notification. The foreground service reuses the same id. */
    fun post(context: Context, block: RoutineBlock, event: BlockEvent, test: Boolean) {
        if (!notificationsEnabled(context)) return
        try {
            NotificationManagerCompat.from(context)
                .notify(notificationId(block.id, event), build(context, block, event, test, alert = true))
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS was revoked between the check and the post.
        }
    }

    fun cancel(context: Context, blockId: Int, event: BlockEvent) {
        NotificationManagerCompat.from(context).cancel(notificationId(blockId, event))
    }

    fun cancelBlock(context: Context, blockId: Int) {
        BlockEvent.entries.forEach { cancel(context, blockId, it) }
    }

    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Android 14 only lets alarm/calling apps show full screen intents without an extra grant. */
    fun canUseFullScreen(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return nm.canUseFullScreenIntent()
    }

    private fun ringPendingIntent(
        context: Context,
        block: RoutineBlock,
        event: BlockEvent,
        test: Boolean
    ): PendingIntent {
        val intent = RingActivity.intent(context, block, event, test)
        return PendingIntent.getActivity(context, notificationId(block.id, event) * 4, intent, FLAGS)
    }
}
