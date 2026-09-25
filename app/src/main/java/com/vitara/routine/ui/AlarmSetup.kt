package com.vitara.routine.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vitara.routine.R
import com.vitara.routine.alarm.AlarmNotifications
import com.vitara.routine.alarm.AlarmScheduler
import com.vitara.routine.alarm.RingController

/**
 * The alarm capabilities the app needs, in one place, so the home screen shows a
 * single summary row and the settings screen shows the details.
 */
object AlarmSetup {

    /** One missing capability, with the settings page that grants it. */
    data class Missing(
        val titleRes: Int,
        val messageRes: Int,
        val grant: (Activity) -> Unit
    )

    fun missing(activity: Activity, requestNotifications: () -> Unit): List<Missing> {
        val result = mutableListOf<Missing>()

        if (!notificationsGranted(activity)) {
            result += Missing(
                R.string.perm_notifications_title,
                R.string.perm_notifications_msg
            ) { requestNotifications() }
        }
        if (!RingController.canDrawOverOtherApps(activity)) {
            result += Missing(
                R.string.perm_overlay_title,
                R.string.perm_overlay_msg
            ) { open(it, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).setData(packageUri(it))) }
        }
        if (!AlarmScheduler.canScheduleExact(activity)) {
            result += Missing(
                R.string.perm_exact_title,
                R.string.perm_exact_msg
            ) { open(it, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(packageUri(it))) }
        }
        if (!AlarmNotifications.canUseFullScreen(activity)) {
            result += Missing(
                R.string.perm_fsi_title,
                R.string.perm_fsi_msg
            ) {
                open(
                    it,
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                        .setData(packageUri(it))
                )
            }
        }
        return result
    }

    fun notificationsGranted(context: Context): Boolean {
        val runtimeGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return runtimeGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun open(activity: Activity, intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (_: Throwable) {
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(packageUri(activity))
                )
            } catch (_: Throwable) {
                // nothing else we can do - the card stays visible so the user knows
            }
        }
    }

    private fun packageUri(context: Context): Uri = Uri.parse("package:${context.packageName}")
}
