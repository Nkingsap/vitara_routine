package com.vitara.routine.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.vitara.routine.R
import com.vitara.routine.alarm.AlarmNotifications
import com.vitara.routine.alarm.AlarmScheduler
import com.vitara.routine.data.BlockEvent
import com.vitara.routine.data.PaletteColor
import com.vitara.routine.data.RoutineBlock
import com.vitara.routine.data.RoutineRepository
import com.vitara.routine.data.Schedule
import com.vitara.routine.util.TimeText
import java.time.LocalDate

/** Creates or edits a single routine. */
class BlockEditActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_BLOCK_ID = "com.vitara.routine.extra.EDIT_BLOCK_ID"
        private const val PICK_FROM_DEVICE = "com.vitara.routine.PICK_FROM_DEVICE"

        /** Ink colour from the palette: readable on every pastel chip. */
        private const val INK = "#1E3A44"

        fun intent(context: Context, blockId: Int): Intent =
            Intent(context, BlockEditActivity::class.java).putExtra(EXTRA_BLOCK_ID, blockId)
    }

    /**
     * Quick starting points so a routine can be created in two taps. The chip colour
     * is the palette entry the template uses, which is also how the routine is tinted.
     */
    private data class Preset(
        val label: String,
        val title: String,
        val message: String,
        val color: PaletteColor,
        val minutes: Int,
        val ringAtEnd: Boolean
    )

    private val presets = listOf(
        Preset("Wake up", "Wake up", "Good morning. Get out of bed now.", PaletteColor.FROSTED_BLUE, 15, false),
        Preset("Study", "Study", "Study block started. Phone down, focus on the work.", PaletteColor.BLUSH_POP, 60, true),
        Preset("Rest", "Rest", "Take a real break.", PaletteColor.ICY_AQUA, 30, true),
        Preset("Cooking", "Cooking", "Cooking time. Step away from the books.", PaletteColor.PETAL_FROST, 30, true),
        Preset("Clean up", "Clean up / Fresh up", "Clean up and fresh up.", PaletteColor.ICY_AQUA, 30, true),
        Preset("Meal", "Food / Rest", "Eat and rest.", PaletteColor.PETAL_FROST, 30, true),
        Preset("Exercise", "Exercise", "Time to move your body.", PaletteColor.FROSTED_BLUE, 45, true),
        Preset("Sleep", "Sleep", "Phone away, lights off, sleep.", PaletteColor.FROSTED_BLUE, 480, false)
    )

    private var blockId: Int = 0
    private var startMinute: Int = 6 * 60
    private var endMinute: Int = 7 * 60
    private var days: MutableSet<Int> = RoutineBlock.EVERY_DAY.toMutableSet()
    private var colorIndex: Int = PaletteColor.FROSTED_BLUE.ordinal

    /** Kept only so a routine edited here keeps the icon key it was stored with. */
    private var iconKey: String = "alarm"
    private var soundUri: String? = null
    private var soundLabel: String = ""

    private val pickSound = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {
                // Some providers grant no persistable access; the uri still works for this session.
            }
            soundUri = uri.toString()
            soundLabel = displayName(uri)
            renderSound()
        }
    }

    private lateinit var titleInput: TextInputEditText
    private lateinit var messageInput: TextInputEditText
    private lateinit var startButton: MaterialButton
    private lateinit var endButton: MaterialButton
    private lateinit var windowHint: TextView
    private lateinit var dayGroup: ChipGroup
    private lateinit var soundName: TextView
    private lateinit var vibrateSwitch: MaterialSwitch
    private lateinit var ringEndSwitch: MaterialSwitch
    private lateinit var fullScreenSwitch: MaterialSwitch
    private lateinit var enabledSwitch: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_block_edit)

        titleInput = findViewById(R.id.titleInput)
        messageInput = findViewById(R.id.messageInput)
        startButton = findViewById(R.id.startTimeButton)
        endButton = findViewById(R.id.endTimeButton)
        windowHint = findViewById(R.id.windowHint)
        dayGroup = findViewById(R.id.dayGroup)
        soundName = findViewById(R.id.soundName)
        vibrateSwitch = findViewById(R.id.vibrateSwitch)
        ringEndSwitch = findViewById(R.id.ringEndSwitch)
        fullScreenSwitch = findViewById(R.id.fullScreenSwitch)
        enabledSwitch = findViewById(R.id.enabledSwitch)

        blockId = intent.getIntExtra(EXTRA_BLOCK_ID, 0)
        val existing = if (blockId != 0) RoutineRepository.get(this, blockId) else null
        val editing = existing != null

        findViewById<TextView>(R.id.editorHeading).setText(
            if (editing) R.string.editor_edit_title else R.string.editor_new_title
        )
        findViewById<MaterialButton>(R.id.deleteButton).visibility =
            if (editing) View.VISIBLE else View.GONE

        buildDayChips()

        if (existing != null) {
            titleInput.setText(existing.title)
            messageInput.setText(existing.message)
            startMinute = existing.startMinute
            endMinute = existing.endMinute
            days = existing.days.toMutableSet()
            colorIndex = existing.colorIndex
            iconKey = existing.iconKey
            soundUri = existing.soundUri
            soundLabel = existing.soundUri?.let { displayName(Uri.parse(it)) } ?: ""
            vibrateSwitch.isChecked = existing.vibrate
            ringEndSwitch.isChecked = existing.ringAtEnd
            fullScreenSwitch.isChecked = existing.fullScreen
            enabledSwitch.isChecked = existing.enabled
        } else {
            vibrateSwitch.isChecked = true
            ringEndSwitch.isChecked = true
            fullScreenSwitch.isChecked = true
            enabledSwitch.isChecked = true
        }

        buildPresets()

        syncDayChips()
        renderSound()
        renderWindow()

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        startButton.setOnClickListener { pickTime(startMinute) { startMinute = it; renderWindow() } }
        endButton.setOnClickListener { pickTime(endMinute) { endMinute = it; renderWindow() } }
        findViewById<MaterialButton>(R.id.changeSoundButton).setOnClickListener { chooseSound() }
        findViewById<MaterialButton>(R.id.everyDayButton).setOnClickListener { setDays(RoutineBlock.EVERY_DAY) }
        findViewById<MaterialButton>(R.id.weekdaysButton).setOnClickListener { setDays(RoutineBlock.WEEKDAYS) }
        findViewById<MaterialButton>(R.id.weekendsButton).setOnClickListener { setDays(RoutineBlock.WEEKENDS) }
        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.deleteButton).setOnClickListener { confirmDelete() }
    }

    // --------------------------------------------------------------- presets

    private fun buildPresets() {
        val group = findViewById<ChipGroup>(R.id.presetGroup)
        group.removeAllViews()
        presets.forEach { preset ->
            val chip = Chip(this).apply {
                text = preset.label
                isCheckable = false
                isClickable = true
                chipBackgroundColor = ColorStateList.valueOf(Color.parseColor(preset.color.hex))
                setTextColor(Color.parseColor(INK))
                setOnClickListener { applyPreset(preset) }
            }
            group.addView(chip)
        }
    }

    private fun applyPreset(preset: Preset) {
        titleInput.setText(preset.title)
        messageInput.setText(preset.message)
        colorIndex = preset.color.ordinal
        endMinute = (startMinute + preset.minutes) % RoutineBlock.MINUTES_PER_DAY
        ringEndSwitch.isChecked = preset.ringAtEnd
        vibrateSwitch.isChecked = true
        renderWindow()
    }

    // --------------------------------------------------------------- days

    private fun buildDayChips() {
        dayGroup.removeAllViews()
        (1..7).forEach { day ->
            val chip = Chip(this).apply {
                text = TimeText.dayShort(day)
                isCheckable = true
                tag = day
            }
            dayGroup.addView(chip)
        }
        dayGroup.setOnCheckedStateChangeListener { _, _ ->
            days = collectDays().toMutableSet()
        }
    }

    private fun collectDays(): Set<Int> =
        (0 until dayGroup.childCount)
            .mapNotNull { index ->
                val chip = dayGroup.getChildAt(index) as? Chip ?: return@mapNotNull null
                if (chip.isChecked) chip.tag as? Int else null
            }
            .toSet()

    private fun syncDayChips() {
        (0 until dayGroup.childCount).forEach { index ->
            val chip = dayGroup.getChildAt(index) as? Chip ?: return@forEach
            chip.isChecked = days.contains(chip.tag as? Int)
        }
    }

    private fun setDays(selection: Set<Int>) {
        days = selection.toMutableSet()
        syncDayChips()
    }

    // --------------------------------------------------------------- time / sound

    private fun renderWindow() {
        startButton.text = TimeText.clock(startMinute)
        endButton.text = TimeText.clock(endMinute)
        val length = ((endMinute - startMinute) + RoutineBlock.MINUTES_PER_DAY) % RoutineBlock.MINUTES_PER_DAY
        val overnight = endMinute <= startMinute
        windowHint.text = buildString {
            append(TimeText.duration(length))
            if (overnight) {
                append("  ·  ")
                append(getString(R.string.overnight_hint))
            }
        }
    }

    private fun pickTime(current: Int, onPicked: (Int) -> Unit) {
        val hour = current / 60
        val minute = current % 60
        android.app.TimePickerDialog(
            this,
            { _, pickedHour, pickedMinute -> onPicked(pickedHour * 60 + pickedMinute) },
            hour,
            minute,
            // 12-hour picker with AM/PM so the time you set is the time you hear.
            false
        ).show()
    }

    private fun renderSound() {
        soundName.text = soundLabel.ifBlank { getString(R.string.default_alarm_sound) }
    }

    /** Lists the device alarm/ringtone sounds plus "pick a file". */
    private fun chooseSound() {
        val manager = android.media.RingtoneManager(this)
        manager.setType(android.media.RingtoneManager.TYPE_ALARM or android.media.RingtoneManager.TYPE_RINGTONE)

        val labels = mutableListOf(getString(R.string.default_alarm_sound))
        val values = mutableListOf<String?>(null)
        try {
            val cursor = manager.cursor
            for (position in 0 until cursor.count) {
                cursor.moveToPosition(position)
                val title = cursor.getString(android.media.RingtoneManager.TITLE_COLUMN_INDEX)
                labels.add(title ?: "Sound ${position + 1}")
                values.add(manager.getRingtoneUri(position)?.toString())
            }
        } catch (_: Throwable) {
            // A device without a ringtone provider still gets the default + file picker.
        }
        labels.add(getString(R.string.pick_sound))
        values.add(PICK_FROM_DEVICE)

        val checked = values.indexOf(soundUri).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.label_sound)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                if (values[which] == PICK_FROM_DEVICE) {
                    pickSound.launch(arrayOf("audio/*"))
                } else {
                    soundUri = values[which]
                    soundLabel = labels[which]
                    renderSound()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun displayName(uri: Uri): String {
        try {
            android.media.RingtoneManager.getRingtone(this, uri)?.getTitle(this)?.let { title ->
                if (title.isNotBlank()) return title
            }
        } catch (_: Throwable) {
            // fall through to the provider display name
        }
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Throwable) {
            // ignore unreadable providers
        }
        return uri.lastPathSegment ?: "Custom sound"
    }

    // --------------------------------------------------------------- persistence

    private fun save() {
        val title = titleInput.text?.toString()?.trim().orEmpty()
        val pickedDays = collectDays()
        when {
            title.isEmpty() -> return toast(getString(R.string.title_required))
            startMinute == endMinute -> return toast(getString(R.string.same_time_error))
            pickedDays.isEmpty() -> return toast(getString(R.string.no_days_error))
        }

        val block = RoutineBlock(
            id = blockId,
            title = title,
            iconKey = iconKey,
            message = messageInput.text?.toString()?.trim().orEmpty(),
            startMinute = startMinute,
            endMinute = endMinute,
            days = pickedDays,
            colorIndex = colorIndex,
            soundUri = soundUri,
            vibrate = vibrateSwitch.isChecked,
            ringAtEnd = ringEndSwitch.isChecked,
            fullScreen = fullScreenSwitch.isChecked,
            enabled = enabledSwitch.isChecked
        )

        val stored = RoutineRepository.save(this, block)
        AlarmScheduler.cancel(this, stored.id)
        if (stored.enabled) AlarmScheduler.schedule(this, stored)
        AlarmNotifications.ensureChannels(this)
        toast(getString(R.string.saved))

        // The most common reason a brand new routine seems to do nothing: the time was
        // read as AM when PM was meant, so the first ring lands tomorrow. Say so.
        val firstRing = Schedule.nextOccurrence(stored, BlockEvent.START)
        if (stored.enabled && firstRing != null && !firstRing.toLocalDate().isEqual(LocalDate.now())) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.first_ring_title, TimeText.relativeDay(firstRing)))
                .setMessage(
                    getString(
                        R.string.first_ring_message,
                        TimeText.clock(stored.startMinute),
                        TimeText.relativeDay(firstRing),
                        TimeText.clock(stored.startMinute)
                    )
                )
                .setPositiveButton(R.string.ring_it_now) { _, _ ->
                    AlarmScheduler.scheduleTest(this, stored, 3)
                    toast(getString(R.string.ringing_soon))
                    finish()
                }
                .setNegativeButton(android.R.string.ok) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            return
        }
        finish()
    }

    private fun confirmDelete() {
        if (blockId == 0) return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(R.string.delete)
            .setPositiveButton(R.string.delete) { _, _ ->
                RoutineRepository.delete(this, blockId)
                AlarmScheduler.cancel(this, blockId)
                AlarmNotifications.cancelBlock(this, blockId)
                toast(getString(R.string.deleted))
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
