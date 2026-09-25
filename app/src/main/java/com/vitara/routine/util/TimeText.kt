package com.vitara.routine.util

import android.content.Context
import com.vitara.routine.data.RoutineBlock
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** All user visible time/day formatting lives here. */
object TimeText {

    /** 12-hour clock, e.g. "10:40 PM" - the picker and every label use this. */
    private val TIME_12H: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

    fun clock(minuteOfDay: Int): String = timeOf(minuteOfDay).format(TIME_12H)

    fun nowClock(): String = LocalDateTime.now().format(TIME_12H)

    /** "4:10 AM – 6:30 AM", or just "4:00 AM" for a routine that only rings once. */
    fun window(block: RoutineBlock): String =
        if (!block.ringAtEnd) {
            clock(block.startMinute)
        } else {
            "${clock(block.startMinute)} – ${clock(block.endMinute)}"
        }

    /** "04:00 - 05:00", with a next-day marker for overnight blocks. */
    fun range(block: RoutineBlock): String =
        range(block.startMinute, block.endMinute, block.crossesMidnight)

    fun range(startMinute: Int, endMinute: Int, nextDay: Boolean = false): String {
        val suffix = if (nextDay) " (+1)" else ""
        return "${clock(startMinute)} - ${clock(endMinute)}$suffix"
    }

    /** "52 m", "1 h 15 m". */
    fun duration(minutes: Int): String = when {
        minutes <= 0 -> "0 m"
        minutes < 60 -> "$minutes m"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} m"
    }

    /** "in 2 h 15 m", "in 45 m", "in 20 s". */
    fun countdown(target: ZonedDateTime, now: ZonedDateTime = ZonedDateTime.now()): String {
        val seconds = ChronoUnit.SECONDS.between(now, target)
        return when {
            seconds <= 0 -> "now"
            seconds < 60 -> "in ${seconds} s"
            seconds < 3600 -> "in ${seconds / 60} m"
            else -> "in ${duration((seconds / 60).toInt())}"
        }
    }

    fun relativeDay(time: ZonedDateTime, today: java.time.LocalDate = java.time.LocalDate.now()): String = when (time.toLocalDate()) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> time.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }

    fun dayShort(day: Int): String =
        DayOfWeek.of(day.coerceIn(1, 7)).getDisplayName(TextStyle.SHORT, Locale.getDefault())

    /** "Every day", "Weekdays", "Weekends" or "Mon, Wed, Fri". */
    fun days(context: Context, days: Set<Int>): String = when {
        days.isEmpty() -> "Never"
        days.size == 7 -> context.getString(com.vitara.routine.R.string.every_day)
        days == RoutineBlock.WEEKDAYS -> context.getString(com.vitara.routine.R.string.weekdays)
        days == RoutineBlock.WEEKENDS -> context.getString(com.vitara.routine.R.string.weekends)
        else -> days.sorted().joinToString(", ") { dayShort(it) }
    }

    private fun timeOf(minuteOfDay: Int): LocalTime {
        val safe = ((minuteOfDay % RoutineBlock.MINUTES_PER_DAY) + RoutineBlock.MINUTES_PER_DAY) % RoutineBlock.MINUTES_PER_DAY
        return LocalTime.of(safe / 60, safe % 60)
    }
}
