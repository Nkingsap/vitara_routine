package com.vitara.routine.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The five Vitara Routine brand colours. The order is stored per routine
 * (as [RoutineBlock.colorIndex]) so the palette can be extended later.
 */
enum class PaletteColor(val label: String, val hex: String) {
    FROSTED_BLUE("Frosted Blue", "#7bdff2"),
    ICY_AQUA("Icy Aqua", "#b2f7ef"),
    MINT_CREAM("Mint Cream", "#eff7f6"),
    PETAL_FROST("Petal Frost", "#f7d6e0"),
    BLUSH_POP("Blush Pop", "#f2b5d4");

    companion object {
        fun of(index: Int): PaletteColor {
            val all = entries
            return all[((index % all.size) + all.size) % all.size]
        }
    }
}

/**
 * One routine block, e.g. "Study 04:00 - 05:00 on Monday..Sunday".
 *
 * @param startMinute minutes from midnight when the block starts (0..1439)
 * @param endMinute   minutes from midnight when the block ends. A value lower
 *                    than [startMinute] means the block runs past midnight.
 * @param days        ISO days of week, 1 = Monday .. 7 = Sunday
 * @param soundUri    persisted content:// uri of the alarm sound, null = system default
 */
data class RoutineBlock(
    val id: Int = 0,
    val title: String = "",
    /** Legacy field kept so old stores still load; nothing in the UI depends on it. */
    val emoji: String = "",
    val iconKey: String = "alarm",
    val message: String = "",
    val startMinute: Int = 6 * 60,
    val endMinute: Int = 7 * 60,
    val days: Set<Int> = EVERY_DAY,
    val colorIndex: Int = 0,
    val soundUri: String? = null,
    val vibrate: Boolean = true,
    val ringAtEnd: Boolean = true,
    val fullScreen: Boolean = true,
    val enabled: Boolean = true
) {
    val color: PaletteColor get() = PaletteColor.of(colorIndex)

    /** Length of the block in minutes. A block that crosses midnight still reports its real length. */
    val durationMinutes: Int
        get() = ((endMinute - startMinute) + MINUTES_PER_DAY) % MINUTES_PER_DAY

    /** True when the end time is on the following day (e.g. 22:00 -> 06:00). */
    val crossesMidnight: Boolean get() = endMinute <= startMinute

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("emoji", emoji)
        put("icon", iconKey)
        put("message", message)
        put("start", startMinute)
        put("end", endMinute)
        put("days", JSONArray(days.sorted()))
        put("color", colorIndex)
        put("sound", soundUri ?: JSONObject.NULL)
        put("vibrate", vibrate)
        put("ring_at_end", ringAtEnd)
        put("full_screen", fullScreen)
        put("enabled", enabled)
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60

        val EVERY_DAY: Set<Int> = (1..7).toSet()
        val WEEKDAYS: Set<Int> = (1..5).toSet()
        val WEEKENDS: Set<Int> = setOf(6, 7)

        fun fromJson(o: JSONObject): RoutineBlock {
            val arr = o.optJSONArray("days") ?: JSONArray()
            val days = (0 until arr.length()).map { arr.getInt(it) }.filter { it in 1..7 }.toSet()
            val sound = if (o.isNull("sound")) null else o.optString("sound").ifBlank { null }
            val legacyEmoji = o.optString("emoji", "")
            val iconKey = o.optString("icon", "").ifBlank { legacyIconKey(legacyEmoji) }
            return RoutineBlock(
                id = o.optInt("id", 0),
                title = o.optString("title", ""),
                emoji = legacyEmoji,
                iconKey = iconKey,
                message = o.optString("message", ""),
                startMinute = o.optInt("start", 6 * 60),
                endMinute = o.optInt("end", 7 * 60),
                days = days.ifEmpty { EVERY_DAY },
                colorIndex = o.optInt("color", 0),
                soundUri = sound,
                vibrate = o.optBoolean("vibrate", true),
                ringAtEnd = o.optBoolean("ring_at_end", true),
                fullScreen = o.optBoolean("full_screen", true),
                enabled = o.optBoolean("enabled", true)
            )
        }
    }
}
