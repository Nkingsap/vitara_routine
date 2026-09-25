package com.vitara.routine.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.vitara.routine.R
import com.vitara.routine.alarm.AlarmNotifications
import com.vitara.routine.alarm.AlarmScheduler
import com.vitara.routine.data.RoutineBlock
import com.vitara.routine.data.RoutineRepository
import com.vitara.routine.data.Schedule
import com.vitara.routine.util.TimeText

class MainActivity : AppCompatActivity(), BlockAdapter.Listener {

    private lateinit var blockAdapter: BlockAdapter
    private lateinit var clockText: TextView
    private lateinit var nextUpTitle: TextView
    private lateinit var nextUpDetail: TextView
    private lateinit var permissionRow: View
    private lateinit var permissionRowDetail: TextView
    private lateinit var emptyState: View

    private val handler = Handler(Looper.getMainLooper())

    private val clockTicker = object : Runnable {
        override fun run() {
            clockText.text = TimeText.nowClock()
            handler.postDelayed(this, 1000)
        }
    }

    private val refreshTicker = object : Runnable {
        override fun run() {
            renderNextUp()
            handler.postDelayed(this, 20_000)
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        clockText = findViewById(R.id.clockText)
        nextUpTitle = findViewById(R.id.nextUpTitle)
        nextUpDetail = findViewById(R.id.nextUpDetail)
        permissionRow = findViewById(R.id.permissionRow)
        permissionRowDetail = findViewById(R.id.permissionRowDetail)
        emptyState = findViewById(R.id.emptyState)

        blockAdapter = BlockAdapter(this)
        findViewById<RecyclerView>(R.id.blockList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = blockAdapter
            isNestedScrollingEnabled = false
        }

        findViewById<MaterialButton>(R.id.newRoutineButton).setOnClickListener {
            startActivity(BlockEditActivity.intent(this, 0))
        }
        findViewById<MaterialButton>(R.id.emptyTemplateButton).setOnClickListener {
            confirmTimetable()
        }
        val openSettings = View.OnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.settingsButton).setOnClickListener(openSettings)
        permissionRow.setOnClickListener(openSettings)
    }

    override fun onResume() {
        super.onResume()
        // Re-arming on every visit also repairs alarms the system dropped (if master alarm enabled).
        AlarmScheduler.scheduleAll(this)
        clockText.text = TimeText.nowClock()
        handler.post(clockTicker)
        handler.post(refreshTicker)
        render()
    }

    override fun onPause() {
        handler.removeCallbacks(clockTicker)
        handler.removeCallbacks(refreshTicker)
        super.onPause()
    }

    private fun render() {
        val blocks = RoutineRepository.all(this)
        blockAdapter.submit(blocks)
        emptyState.visibility = if (blocks.isEmpty()) View.VISIBLE else View.GONE

        renderNextUp()

        val missing = AlarmSetup.missing(this) { askNotifications() }
        if (missing.isEmpty()) {
            permissionRow.visibility = View.GONE
        } else {
            permissionRow.visibility = View.VISIBLE
            permissionRowDetail.text = missing.joinToString(" · ") { getString(it.titleRes) }
        }
    }

    private fun renderNextUp() {
        if (!AlarmScheduler.isMasterAlarmEnabled(this)) {
            nextUpTitle.text = getString(R.string.alarms_paused_title)
            nextUpDetail.text = getString(R.string.alarms_paused_detail)
            return
        }
        val next = Schedule.nextEvent(this)
        if (next == null) {
            nextUpTitle.text = getString(R.string.nothing_scheduled)
            nextUpDetail.text = getString(R.string.empty_routines)
            return
        }
        val verb = if (next.event.isStart) "Starts" else "Ends"
        nextUpTitle.text = next.block.title
        nextUpDetail.text = "$verb ${TimeText.relativeDay(next.time)} at " +
            "${TimeText.clock(next.minute)} · ${TimeText.countdown(next.time)}"
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
            .setNeutralButton(R.string.mon_to_fri) { _, _ -> installTimetable(RoutineBlock.WEEKDAYS) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun installTimetable(days: Set<Int>) {
        val added = RoutineRepository.installTimetable(this, days)
        AlarmScheduler.scheduleAll(this)
        render()
        showToast(
            if (added == 0) {
                getString(R.string.timetable_added_none)
            } else {
                resources.getQuantityString(R.plurals.timetable_added, added, added)
            }
        )
    }

    // ------------------------------------------------------------- routine list

    override fun onBlockClicked(block: RoutineBlock) {
        startActivity(BlockEditActivity.intent(this, block.id))
    }

    override fun onBlockEnabledChanged(block: RoutineBlock, enabled: Boolean) {
        RoutineRepository.setEnabled(this, block.id, enabled)
        if (enabled) {
            AlarmScheduler.schedule(this, block.copy(enabled = true))
            showToast("${block.title} armed")
        } else {
            AlarmScheduler.cancel(this, block.id)
            showToast("${block.title} switched off")
        }
        render()
    }

    override fun onBlockMenu(block: RoutineBlock, anchor: View) {
        val labels = arrayOf("Edit", "Delete")
        androidx.appcompat.widget.PopupMenu(this, anchor).apply {
            labels.forEachIndexed { index, label -> menu.add(0, index, index, label) }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    0 -> startActivity(BlockEditActivity.intent(this@MainActivity, block.id))
                    1 -> confirmDelete(block)
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            show()
        }
    }

    private fun confirmDelete(block: RoutineBlock) {
        AlertDialog.Builder(this)
            .setTitle("\"${block.title}\"")
            .setMessage(R.string.delete)
            .setPositiveButton(R.string.delete) { _, _ ->
                RoutineRepository.delete(this, block.id)
                AlarmScheduler.cancel(this, block.id)
                AlarmNotifications.cancelBlock(this, block.id)
                Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
