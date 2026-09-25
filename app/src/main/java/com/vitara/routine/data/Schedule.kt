package com.vitara.routine.data

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The two moments every routine can ring at. */
enum class BlockEvent {
    /** The block starts: "wake up", "study time". */
    START,

    /** The block is over: "rest time", "sleep now". */
    END;

    val isStart: Boolean get() = this == START

    companion object {
        fun from(name: String?): BlockEvent = if (name.equals(END.name, true)) END else START
    }
}

/** A routine event placed on the calendar. */
data class ScheduledEvent(
    val block: RoutineBlock,
    val event: BlockEvent,
    val time: ZonedDateTime
) {
    /** Minute of day of this event. */
    val minute: Int get() = if (event.isStart) block.startMinute else block.endMinute
}

object Schedule {

    fun zone(): ZoneId = ZoneId.systemDefault()

    /**
     * Next time [block] rings for [event], strictly after [from], or null when the
     * routine is disabled / has no days. Blocks that cross midnight report their
     * END on the following day.
     */
    fun nextOccurrence(
        block: RoutineBlock,
        event: BlockEvent,
        from: ZonedDateTime = ZonedDateTime.now()
    ): ZonedDateTime? {
        if (!block.enabled || block.days.isEmpty()) return null
        if (event == BlockEvent.END && block.durationMinutes == 0) return null
        for (offset in 0L..15L) {
            val date = from.toLocalDate().plusDays(offset)
            val anchor = if (event == BlockEvent.END && block.crossesMidnight) date.minusDays(1) else date
            if (anchor.dayOfWeek.value !in block.days) continue
            val candidate = at(date, event, block)
            if (candidate.isAfter(from)) return candidate
        }
        return null
    }

    /** Every occurrence of [block]/[event] starting at [from], used by the "next up" panel. */
    fun upcoming(
        block: RoutineBlock,
        event: BlockEvent,
        from: ZonedDateTime = ZonedDateTime.now(),
        limit: Int = 1
    ): List<ZonedDateTime> {
        val result = mutableListOf<ZonedDateTime>()
        var cursor = from
        while (result.size < limit) {
            val next = nextOccurrence(block, event, cursor) ?: break
            result.add(next)
            cursor = next.plusMinutes(1)
        }
        return result
    }

    /** Events of the current local day, in chronological order (before and after now). */
    fun eventsToday(context: android.content.Context, now: ZonedDateTime = ZonedDateTime.now()): List<ScheduledEvent> {
        val today: LocalDate = now.toLocalDate()
        val result = mutableListOf<ScheduledEvent>()
        for (block in RoutineRepository.all(context)) {
            for (event in BlockEvent.entries) {
                if (!block.enabled || block.days.isEmpty()) continue
                if (event == BlockEvent.END && block.durationMinutes == 0) continue
                val anchor = if (event == BlockEvent.END && block.crossesMidnight) today.minusDays(1) else today
                if (anchor.dayOfWeek.value !in block.days) continue
                result += ScheduledEvent(block, event, at(today, event, block))
            }
        }
        return result.sortedBy { it.time }
    }

    /** The next ring of any routine, or null when everything is switched off. */
    fun nextEvent(context: android.content.Context, from: ZonedDateTime = ZonedDateTime.now()): ScheduledEvent? =
        RoutineRepository.all(context)
            .flatMap { block ->
                BlockEvent.entries.mapNotNull { event ->
                    nextOccurrence(block, event, from)?.let { ScheduledEvent(block, event, it) }
                }
            }
            .minByOrNull { it.time.toInstant() }

    private fun at(date: LocalDate, event: BlockEvent, block: RoutineBlock): ZonedDateTime {
        val minute = if (event.isStart) block.startMinute else block.endMinute
        val time = LocalTime.of((minute / 60) % 24, minute % 60)
        return ZonedDateTime.of(date, time, zone())
    }
}
