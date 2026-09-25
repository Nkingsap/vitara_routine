package com.vitara.routine.data

import android.content.Context
import org.json.JSONArray

/**
 * Routines are stored as a JSON array in SharedPreferences: no database, no
 * migrations, and the exact same encoding can be moved straight into a
 * broadcast intent (see AlarmScheduler).
 */
object RoutineRepository {
    private const val PREFS = "vitara_routine_store"
    private const val KEY_BLOCKS = "blocks"
    private const val KEY_NEXT_ID = "next_id"
    private const val KEY_SEEDED = "seeded"
    private const val KEY_MASTER_ALARM = "master_alarm_enabled"

    /**
     * Days the shipped timetable runs on: the whole week.
     */
    private val TIMETABLE_DAYS: Set<Int> = RoutineBlock.EVERY_DAY

    /** "4:10 AM" -> 250 minutes from midnight. */
    private fun at(hour: Int, minute: Int): Int = hour * 60 + minute

    private fun block(
        title: String,
        iconKey: String,
        startMinute: Int,
        endMinute: Int,
        colour: PaletteColor,
        message: String,
        days: Set<Int>,
        ringAtEnd: Boolean = true
    ): RoutineBlock = RoutineBlock(
        title = title,
        emoji = "",
        iconKey = iconKey,
        message = message,
        startMinute = startMinute,
        endMinute = endMinute,
        days = days,
        colorIndex = colour.ordinal,
        soundUri = null,
        vibrate = true,
        ringAtEnd = ringAtEnd,
        fullScreen = true,
        enabled = true
    )

    /**
     * The default plan: 04:00 wake up, five study blocks adding up to 10 hours,
     * cooking, clean up, rest and food breaks between them.
     */
    fun timetable(days: Set<Int> = TIMETABLE_DAYS): List<RoutineBlock> {
        fun b(
            title: String,
            iconKey: String,
            startMinute: Int,
            endMinute: Int,
            colour: PaletteColor,
            message: String,
            ringAtEnd: Boolean = true
        ) = block(title, iconKey, startMinute, endMinute, colour, message, days, ringAtEnd)

        return listOf(
            b("Wake up", "alarm", at(4, 0), at(4, 10), PaletteColor.FROSTED_BLUE,
                "Good morning. Get out of bed now.", ringAtEnd = false),
            b("Study", "study", at(4, 10), at(6, 30), PaletteColor.BLUSH_POP,
                "Study block. Phone down - deep focus."),
            b("Cooking", "cooking", at(6, 30), at(7, 0), PaletteColor.PETAL_FROST,
                "Cooking time. Step away from the books."),
            b("Clean up / Fresh up", "clean", at(7, 0), at(7, 30), PaletteColor.ICY_AQUA,
                "Clean up and fresh up."),
            b("Rest / House work", "rest", at(13, 0), at(14, 0), PaletteColor.ICY_AQUA,
                "Rest and house work. Recharge before the next study block."),
            b("Study", "study", at(14, 0), at(17, 0), PaletteColor.BLUSH_POP,
                "Study block. Phone down - deep focus."),
            b("Rest / House work", "rest", at(17, 0), at(17, 30), PaletteColor.ICY_AQUA,
                "Short break. Stretch, water, then back."),
            b("Study", "study", at(17, 30), at(19, 0), PaletteColor.BLUSH_POP,
                "Study block. Phone down - deep focus."),
            b("Cooking", "cooking", at(19, 0), at(19, 30), PaletteColor.PETAL_FROST,
                "Cooking time. Step away from the books."),
            b("Study", "study", at(19, 30), at(20, 30), PaletteColor.BLUSH_POP,
                "Study block. Phone down - deep focus."),
            b("Food / Rest", "meal", at(20, 30), at(21, 0), PaletteColor.PETAL_FROST,
                "Eat and rest. Last study block starts at 9:00 PM."),
            b("Study", "study", at(21, 0), at(23, 0), PaletteColor.BLUSH_POP,
                "Final study block of the day. Finish strong.")
        )
    }

    /**
     * Loads the timetable into an existing list and returns how many routines were
     * added or re-targeted: a block that is already there keeps its own tweaks but
     * its days are switched to [days], so "Every day" also works on an existing
     * Mon & Tue install.
     */
    @Synchronized
    fun installTimetable(context: Context, days: Set<Int> = TIMETABLE_DAYS): Int {
        val existing = all(context).toMutableList()
        var changes = 0
        timetable(days).forEach { candidate ->
            val index = existing.indexOfFirst {
                it.title == candidate.title && it.startMinute == candidate.startMinute
            }
            when {
                index < 0 -> {
                    existing.add(candidate.copy(id = nextId(context)))
                    changes++
                }

                existing[index].days != days -> {
                    existing[index] = existing[index].copy(days = days)
                    changes++
                }
            }
        }
        if (changes == 0) return 0
        write(context, existing)
        return changes
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** All routines, sorted by start time. Seeds the example routine on first run. */
    @Synchronized
    fun all(context: Context): List<RoutineBlock> {
        val p = prefs(context)
        if (!p.getBoolean(KEY_SEEDED, false)) seedDefaults(context)
        val raw = p.getString(KEY_BLOCKS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length())
                .map { RoutineBlock.fromJson(arr.getJSONObject(it)) }
                .sortedWith(compareBy({ it.startMinute }, { it.title }))
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun get(context: Context, id: Int): RoutineBlock? = all(context).firstOrNull { it.id == id }

    /** Inserts (id == 0) or updates a routine and returns the stored copy. */
    @Synchronized
    fun save(context: Context, block: RoutineBlock): RoutineBlock {
        val list = all(context).toMutableList()
        val toSave = if (block.id == 0) block.copy(id = nextId(context)) else block
        val index = list.indexOfFirst { it.id == toSave.id }
        if (index >= 0) list[index] = toSave else list.add(toSave)
        write(context, list)
        return toSave
    }

    @Synchronized
    fun delete(context: Context, id: Int) {
        write(context, all(context).filterNot { it.id == id })
    }

    /** Copies a routine (disabled, so it cannot fire twice by accident). */
    @Synchronized
    fun duplicate(context: Context, id: Int): RoutineBlock? {
        val original = get(context, id) ?: return null
        return save(context, original.copy(id = 0, title = "${original.title} (copy)", enabled = false))
    }

    @Synchronized
    fun setEnabled(context: Context, id: Int, enabled: Boolean) {
        val list = all(context).toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        list[index] = list[index].copy(enabled = enabled)
        write(context, list)
    }

    fun isMasterAlarmEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MASTER_ALARM, true)

    @Synchronized
    fun setMasterAlarmEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MASTER_ALARM, enabled).apply()
    }

    private fun nextId(context: Context): Int {
        val p = prefs(context)
        val id = p.getInt(KEY_NEXT_ID, 1)
        p.edit().putInt(KEY_NEXT_ID, id + 1).apply()
        return id
    }

    private fun write(context: Context, list: List<RoutineBlock>) {
        val arr = JSONArray()
        list.sortedWith(compareBy({ it.startMinute }, { it.title })).forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_BLOCKS, arr.toString()).apply()
    }

    /** First launch: the timetable above becomes the routine list. */
    private fun seedDefaults(context: Context) {
        prefs(context).edit().putBoolean(KEY_SEEDED, true).apply()
        write(context, timetable().map { it.copy(id = nextId(context)) })
    }
}
