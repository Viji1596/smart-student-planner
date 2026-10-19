package com.smartstudent.planner.domain

import java.time.LocalDate

/**
 * The "plan my day" brain. Pure Kotlin (no Android classes) so it can be unit-tested on the JVM.
 *
 * Pipeline for one date:
 *   WorkPlanner  -> turns open tasks and due habits into WorkItems (how many minutes, how urgent)
 *   Scheduler    -> places WorkItems into the free blocks left over by DayPlanner
 *   DayBuilder   -> glues everything together into a timeline the UI can show
 */
enum class Priority(val weight: Int) {
    LOW(1), MEDIUM(2), HIGH(3);

    companion object {
        fun parse(value: String): Priority = when (value.trim().lowercase()) {
            "high" -> HIGH
            "low" -> LOW
            else -> MEDIUM
        }
    }
}

enum class WorkKind { TASK, HABIT }

/**
 * Something to fit into the day.
 * tier: 0 = overdue, 1 = due today / habit, 2 = due within 2 days, 3 = within a week,
 *       4 = later, 5 = no deadline (filler).
 */
data class WorkItem(
    val kind: WorkKind,
    val refId: String,
    val title: String,
    val minutes: Int,
    val priority: Priority,
    val tier: Int,
    val dueDay: LocalDate?,
    /** Tasks can be split over several sessions; habits are done in one go. */
    val divisible: Boolean,
)

data class Session(val startMin: Int, val endMin: Int, val item: WorkItem) {
    val duration: Int get() = endMin - startMin
}

enum class UnscheduledReason { NO_ROOM, DAILY_LIMIT }

data class Unscheduled(val item: WorkItem, val minutes: Int, val reason: UnscheduledReason)

data class SchedulePlan(
    val sessions: List<Session>,
    val unscheduled: List<Unscheduled>,
    /** Free time still left after the sessions (gaps shorter than 15 minutes are left out). */
    val leftoverFree: List<TimeBlock>,
    /** The breaks between back-to-back sessions (short ones, and the long rests). */
    val breaks: List<TimeBlock> = emptyList(),
) {
    val plannedMinutes: Int get() = sessions.sumOf { it.duration }
}

object Scheduler {
    const val BREAK_MIN = 10
    const val LONG_BREAK_AFTER_MIN = 120
    const val LONG_BREAK_MIN = 20
    const val MAX_SESSION = 90
    const val MIN_CHUNK = 25
    const val MIN_VISIBLE_GAP = 15
    /** Non-urgent work (tier 3+) is capped so the plan never fills every free minute. */
    const val MAX_NON_URGENT_MINUTES = 480
    const val LAST_URGENT_TIER = 2

    private const val MIN_SLOT = 5

    private class Slot(var start: Int, val end: Int) {
        val length: Int get() = end - start
        /** Minutes worked in this slot since it began or since the last long break. */
        var worked: Int = 0
    }

    fun schedule(
        free: List<TimeBlock>,
        items: List<WorkItem>,
        earliestStart: Int? = null,
        breakMin: Int = BREAK_MIN,
        longBreakAfterMin: Int = LONG_BREAK_AFTER_MIN,
        longBreakMin: Int = LONG_BREAK_MIN,
        maxSession: Int = MAX_SESSION,
        minChunk: Int = MIN_CHUNK,
        nonUrgentCap: Int = MAX_NON_URGENT_MINUTES,
    ): SchedulePlan {
        val slots = mutableListOf<Slot>()
        for (block in free.sortedBy { it.startMin }) {
            val start = if (earliestStart != null) maxOf(block.startMin, earliestStart) else block.startMin
            if (block.endMin - start >= MIN_VISIBLE_GAP) slots += Slot(start, block.endMin)
        }

        val ordered = items.sortedWith(
            compareBy<WorkItem>(
                { it.tier },
                { -it.priority.weight },
                { it.dueDay ?: LocalDate.MAX },
                { it.minutes },
                { it.title },
            )
        )

        val sessions = mutableListOf<Session>()
        val unscheduled = mutableListOf<Unscheduled>()
        val breakCandidates = mutableListOf<TimeBlock>()
        var plannedNonUrgent = 0

        for (item in ordered) {
            val urgent = item.tier <= LAST_URGENT_TIER
            val budget = if (urgent) Int.MAX_VALUE else (nonUrgentCap - plannedNonUrgent).coerceAtLeast(0)

            var target = minOf(item.minutes, budget)
            if (!item.divisible && target < item.minutes) target = 0 // a habit is all or nothing
            if (target < item.minutes) {
                unscheduled += Unscheduled(item, item.minutes - target, UnscheduledReason.DAILY_LIMIT)
            }

            var remaining = target
            while (remaining > 0) {
                val desired = if (item.divisible) minOf(remaining, maxSession) else remaining
                var slot = slots.firstOrNull { it.length >= desired }
                var length = desired

                if (slot == null && item.divisible) {
                    // Nothing big enough: use the largest gap, as long as it is a worthwhile chunk.
                    val smallestWorthwhile = minOf(minChunk, remaining)
                    slot = slots.filter { it.length >= smallestWorthwhile }.maxByOrNull { it.length }
                    if (slot != null) length = minOf(desired, slot.length)
                }
                if (slot == null) break

                val sessionEnd = slot.start + length
                sessions += Session(slot.start, sessionEnd, item)

                // Rest between sessions: a short break, or a long one after enough work in a row.
                slot.worked += length
                val gap = if (longBreakAfterMin > 0 && slot.worked >= longBreakAfterMin) {
                    slot.worked = 0
                    maxOf(longBreakMin, breakMin)
                } else {
                    breakMin
                }
                if (gap > 0) {
                    breakCandidates += TimeBlock(sessionEnd, minOf(sessionEnd + gap, slot.end), "Break", BlockKind.BREAK)
                }
                slot.start = sessionEnd + gap
                remaining -= length
                if (!urgent) plannedNonUrgent += length
                slots.removeAll { it.length < MIN_SLOT }
            }
            if (remaining > 0) unscheduled += Unscheduled(item, remaining, UnscheduledReason.NO_ROOM)
        }

        val leftover = slots
            .filter { it.length >= MIN_VISIBLE_GAP }
            .map { TimeBlock(it.start, it.end, "Free time", BlockKind.FREE) }

        // A break only matters if another session follows it straight away.
        val breaks = breakCandidates
            .filter { b -> b.endMin > b.startMin && sessions.any { it.startMin == b.endMin } }
            .sortedBy { it.startMin }

        return SchedulePlan(sessions.sortedBy { it.startMin }, unscheduled, leftover, breaks)
    }
}
