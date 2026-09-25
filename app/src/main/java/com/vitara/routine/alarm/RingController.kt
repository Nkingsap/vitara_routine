package com.vitara.routine.alarm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.vitara.routine.ui.RingActivity

/**
 * Single entry point for "a routine is due": posts the audible notification,
 * starts the looping sound/vibration service and takes over the screen.
 *
 * Taking over the screen has three routes, and all of them are used:
 *
 * 1. **App in the foreground** - the ringing screen is started directly.
 * 2. **Phone locked or asleep** - the notification's full screen intent launches it.
 * 3. **Phone unlocked with another app in front** - Android drops background activity
 *    starts unless the app holds `SYSTEM_ALERT_WINDOW` ("display over other apps"),
 *    which the platform treats as an explicit exemption
 *    (`BackgroundActivityStartController` -> `BAL_ALLOW_SAW_PERMISSION`). The home
 *    screen keeps asking for it, and a silent drop is detected and retried here.
 */
object RingController {

    private const val TAG = "RingController"

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var activeRequest: RingRequest? = null

    fun startRinging(context: Context, request: RingRequest) {
        val app = context.applicationContext
        activeRequest = request
        AlarmNotifications.ensureChannels(app)
        AlarmNotifications.post(app, request.block, request.event, request.test)
        RingService.start(app, request)
        if (request.block.fullScreen) openRingScreen(app, request)
    }

    /** Ends the alarm, whether it comes from the ringing screen or the notification button. */
    fun dismiss(context: Context, blockId: Int) {
        val app = context.applicationContext
        if (activeRequest?.block?.id == blockId) activeRequest = null
        AlarmNotifications.cancelBlock(app, blockId)
        RingService.requestDismiss(app, blockId)
    }

    /** Snoozes the current alarm: it rings again in a few minutes, nothing else changes. */
    fun snooze(context: Context, request: RingRequest, minutes: Int = AlarmNotifications.SNOOZE_MINUTES) {
        val app = context.applicationContext
        activeRequest = null
        AlarmScheduler.scheduleSnooze(app, request.block, request.event, minutes)
        AlarmNotifications.cancel(app, request.block.id, request.event)
        RingService.requestSnooze(app, request)
    }

    fun canDrawOverOtherApps(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * Brings up the full screen ringing UI. A blocked background start is dropped
     * silently by the framework, so the result is verified and retried; after the last
     * attempt a diagnostic message explains what to grant.
     */
    fun openRingScreen(context: Context, request: RingRequest, attempt: Int = 1) {
        if (RingActivity.isVisible) return

        try {
            context.startActivity(
                RingActivity.intent(context, request.block, request.event, request.test)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Ringing screen could not be started directly", t)
        }

        handler.postDelayed({
            if (RingActivity.isVisible || !isActive(request)) return@postDelayed
            if (attempt < MAX_ATTEMPTS) {
                openRingScreen(context, request, attempt + 1)
            } else {
                Log.w(TAG, diagnosticMessage(context, request))
            }
        }, if (attempt == 1) 700L else 1200L)
    }

    private const val MAX_ATTEMPTS = 3

    private fun isActive(request: RingRequest): Boolean {
        val active = activeRequest ?: return false
        return active.block.id == request.block.id && active.event == request.event
    }

    private fun diagnosticMessage(context: Context, request: RingRequest): String = buildString {
        append("Alarm screen could not take over the screen (\"")
        append(request.block.title)
        append("\"). ")
        if (!canDrawOverOtherApps(context)) {
            append("Grant \"display over other apps\" so alarms can appear over the current app. ")
        }
        if (!AlarmNotifications.notificationsEnabled(context)) {
            append("Notifications are blocked. ")
        }
        if (!AlarmNotifications.canUseFullScreen(context)) {
            append("\"Full screen alarms\" is not allowed in special app access. ")
        }
        append("Sound and vibration are still ringing; the notification's Dismiss button ends the alarm.")
    }
}
