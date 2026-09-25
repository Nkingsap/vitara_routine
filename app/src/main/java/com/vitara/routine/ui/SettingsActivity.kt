package com.vitara.routine.ui

import android.Manifest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.vitara.routine.BuildConfig
import com.vitara.routine.R
import com.vitara.routine.alarm.AlarmScheduler
import com.vitara.routine.data.RoutineBlock
import com.vitara.routine.data.RoutineRepository

/**
 * Settings: which alarm capabilities are still missing, how the alarm sound
 * behaves, the timetable template and the app version.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var permissionContainer: LinearLayout
    private lateinit var permissionsHeader: TextView
    private lateinit var loadTimetableButton: MaterialButton

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        permissionContainer = findViewById(R.id.permissionContainer)
        permissionsHeader = findViewById(R.id.permissionsHeader)
        loadTimetableButton = findViewById(R.id.loadTimetableButton)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        loadTimetableButton.setOnClickListener { confirmTimetable() }
        findViewById<TextView>(R.id.aboutText).text =
            getString(R.string.version_line, BuildConfig.VERSION_NAME)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val missing = AlarmSetup.missing(this) { askNotifications() }

        // When nothing is missing the whole section disappears, instead of showing a
        // pointless "Grant" button next to a permission that is already granted.
        val visible = if (missing.isEmpty()) View.GONE else View.VISIBLE
        permissionsHeader.visibility = visible
        permissionContainer.visibility = visible

        permissionContainer.removeAllViews()
        missing.forEach { requirement ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.view_permission_banner, permissionContainer, false)
            row.findViewById<TextView>(R.id.bannerTitle).setText(requirement.titleRes)
            row.findViewById<TextView>(R.id.bannerMessage).setText(requirement.messageRes)
            row.findViewById<MaterialButton>(R.id.bannerAction).apply {
                setText(R.string.action_grant)
                setOnClickListener { requirement.grant(this@SettingsActivity) }
            }
            permissionContainer.addView(row)
        }
    }

    private fun askNotifications() {
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ------------------------------------------------------------------ template

    private fun confirmTimetable() {
        AlertDialog.Builder(this)
            .setTitle(R.string.add_timetable_title)
            .setMessage(R.string.add_timetable_message)
            .setPositiveButton(R.string.every_day) { _, _ -> installTimetable(RoutineBlock.EVERY_DAY) }
            .setNeutralButton(R.string.mon_tue) { _, _ -> installTimetable(setOf(1, 2)) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun installTimetable(days: Set<Int>) {
        val added = RoutineRepository.installTimetable(this, days)
        AlarmScheduler.scheduleAll(this)
        Toast.makeText(
            this,
            if (added == 0) {
                getString(R.string.timetable_added_none)
            } else {
                resources.getQuantityString(R.plurals.timetable_added, added, added)
            },
            Toast.LENGTH_SHORT
        ).show()
    }
}
