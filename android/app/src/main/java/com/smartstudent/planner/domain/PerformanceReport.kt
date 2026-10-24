package com.smartstudent.planner.domain

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** A task as far as the report is concerned. [completedOn] is when it was finished, if known. */
data class ReportTask(
    val title: String,
    val due: LocalDate?,
    val completed: Boolean,
    val completedOn: LocalDate?,
)

data class HabitReport(val title: String, val done: Int, val expected: Int, val streak: Int)

data class PerformanceReport(
    val days: Int,
    val from: LocalDate,
    val to: LocalDate,
    /** Days in the range for which session history exists (history is kept from the update that added reports). */
    val trackedDays: Int,
    val sessionsDone: Int,
    val sessionsMissed: Int,
    val minutesDone: Int,
    /** Minutes done in the period just before this one, or null when there's no history to compare with. */
    val minutesDonePrevious: Int?,
    val completionPercent: Int?,
    val bestDay: LocalDate?,
    val bestDayMinutes: Int,
    val tasksCompleted: Int,
    val tasksOpen: Int,
    val overdue: Int,
    val dueSoon: Int,
    val onTimePercent: Int?,
    val mostMissed: String?,
    val habits: List<HabitReport>,
    /** What has been finished today, and what is still planned for the rest of it (minutes). */
    val todayDoneMinutes: Int = 0,
    val todayPlannedLeftMinutes: Int = 0,
) {
    companion object {
        /**
         * @param sessions session history (any dates; only those in the range, and the period before it, are used)
         * @param today the last day of the report
         */
        fun build(
            days: Int,
            today: LocalDate,
            sessions: List<PlannedSession>,
            tasks: List<ReportTask>,
            habits: List<HabitInfo>,
            completions: Map<String, Set<LocalDate>>,
        ): PerformanceReport {
            val from = today.minusDays((days - 1).toLong())
            val previousFrom = from.minusDays(days.toLong())
            val inRange = sessions.filter { it.date in from..today }
            val inPrevious = sessions.filter { it.date >= previousFrom && it.date < from }

            val done = inRange.filter { it.status == SessionStatus.DONE }
            // A task missed three times that evening is one miss, and not a miss at all if it was done later.
            val doneKeys = done.map { Triple(it.date, it.kind, it.refId) }.toSet()
            val missedKeys = inRange.filter { it.status == SessionStatus.MISSED }
                .map { Triple(it.date, it.kind, it.refId) }.toSet() - doneKeys

            val minutesByDay = done.groupBy { it.date }.mapValues { e -> e.value.sumOf { it.duration } }
            val best = minutesByDay.maxByOrNull { it.value }
            val minutesDone = done.sumOf { it.duration }
            val previousMinutes = inPrevious.filter { it.status == SessionStatus.DONE }.sumOf { it.duration }
            val trackedPrevious = inPrevious.map { it.date }.distinct().isNotEmpty()

            val finished = doneKeys.size + missedKeys.size
            val mostMissed = inRange.filter { it.status == SessionStatus.MISSED && Triple(it.date, it.kind, it.refId) in missedKeys }
                .groupBy { it.refId }.maxByOrNull { e -> e.value.map { it.date }.distinct().size }
                ?.value?.firstOrNull()?.title

            val open = tasks.filter { !it.completed }
            val completedInRange = tasks.filter { it.completed && it.completedOn != null && it.completedOn in from..today }
            val withDue = completedInRange.filter { it.due != null }

            return PerformanceReport(
                days = days,
                from = from,
                to = today,
                // a day only counts as "known" once something was finished or missed on it: a plan alone says nothing yet
                trackedDays = inRange.filter { it.status != SessionStatus.PLANNED }.map { it.date }.distinct().size,
                sessionsDone = done.size,
                sessionsMissed = missedKeys.size,
                minutesDone = minutesDone,
                minutesDonePrevious = if (trackedPrevious) previousMinutes else null,
                completionPercent = if (finished > 0) Math.round(100.0 * doneKeys.size / finished).toInt() else null,
                bestDay = best?.key,
                bestDayMinutes = best?.value ?: 0,
                tasksCompleted = completedInRange.size,
                tasksOpen = open.size,
                overdue = open.count { it.due != null && it.due < today },
                dueSoon = open.count { it.due != null && it.due >= today && it.due <= today.plusDays(7) },
                onTimePercent = if (withDue.isNotEmpty()) Math.round(100.0 * withDue.count { it.completedOn!! <= it.due!! } / withDue.size).toInt() else null,
                mostMissed = mostMissed,
                habits = habits.map { habitReport(it, from, today, completions[it.id] ?: emptySet()) },
                todayDoneMinutes = sessions.filter { it.date == today && it.status == SessionStatus.DONE }.sumOf { it.duration },
                todayPlannedLeftMinutes = sessions.filter { it.date == today && it.status == SessionStatus.PLANNED }.sumOf { it.duration },
            )
        }

        private fun habitReport(habit: HabitInfo, from: LocalDate, today: LocalDate, doneDates: Set<LocalDate>): HabitReport {
            val range = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= today }.toList()
            val inRange = doneDates.count { it in from..today }
            val weeks = Math.max(1, Math.ceil(range.size / 7.0).toInt())
            val expected = when (habit.kind) {
                HabitKind.DAILY -> range.size
                HabitKind.DAYS -> range.count { it.dayOfWeek.value in habit.days }
                HabitKind.WEEKLY -> habit.timesPerWeek * weeks
            }
            // the streak: scheduled days done in a row, counting back from today (or yesterday, if today isn't over)
            var streak = 0
            if (habit.kind != HabitKind.WEEKLY) {
                var day = if (today in doneDates) today else today.minusDays(1)
                var steps = 0
                while (steps < 400) {
                    val scheduled = habit.kind == HabitKind.DAILY || day.dayOfWeek.value in habit.days
                    if (scheduled) {
                        if (day in doneDates) streak++ else break
                    }
                    day = day.minusDays(1)
                    steps++
                }
            }
            return HabitReport(habit.title, minOf(inRange, expected), expected, streak)
        }
    }

    /** A friendly, plain-text version of the report. */
    fun toText(): String {
        val lines = mutableListOf(if (days == 1) "Here's how today is going:" else "Here's how the last $days days went:")

        if (todayDoneMinutes > 0 || todayPlannedLeftMinutes > 0) {
            lines += "• Today so far: ${TimeUtil.formatDuration(todayDoneMinutes)} done, ${TimeUtil.formatDuration(todayPlannedLeftMinutes)} still planned."
        }

        if (trackedDays == 0) {
            lines += "• I've only just started keeping a history of finished sessions, so there isn't much to compare yet. It fills in as you tick off or finish work from now on."
        } else {
            lines += "• You did ${TimeUtil.formatDuration(minutesDone)} of planned work in $sessionsDone session${if (sessionsDone == 1) "" else "s"}" +
                (completionPercent?.let { " and finished $it% of what you planned." } ?: ".")
            if (minutesDonePrevious != null && minutesDonePrevious > 0) {
                val change = minutesDone - minutesDonePrevious
                lines += when {
                    change > 0 -> "• That's ${TimeUtil.formatDuration(change)} more than the period before. Nice."
                    change < 0 -> "• That's ${TimeUtil.formatDuration(-change)} less than the period before."
                    else -> "• Same as the period before."
                }
            }
            if (bestDay != null && days > 1 && trackedDays >= 2) {
                lines += "• Your strongest day was ${bestDay.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} (${TimeUtil.formatDuration(bestDayMinutes)})."
            }
            if (sessionsMissed > 0) {
                lines += "• $sessionsMissed session${if (sessionsMissed == 1) "" else "s"} didn't get done" + (mostMissed?.let { ", mostly “$it”." } ?: ".")
            }
        }

        lines += "• Tasks: $tasksCompleted finished, $tasksOpen still open" +
            (if (overdue > 0) ", $overdue overdue" else "") + (if (dueSoon > 0) ", $dueSoon due in the next week" else "") + "." +
            (onTimePercent?.let { " $it% of the finished ones were on time." } ?: "")

        val goodHabits = habits.filter { it.expected > 0 }
        if (goodHabits.isNotEmpty()) {
            lines += "• Habits:"
            for (h in goodHabits) {
                lines += "   – ${h.title}: ${h.done}/${h.expected}" + if (h.streak >= 2) " (${h.streak}-day streak)" else ""
            }
        }

        lines += ""
        lines += advice()
        return lines.joinToString("\n")
    }

    private fun advice(): String = when {
        overdue >= 3 -> "Overdue work is piling up. Want me to show what to tackle first, or lighten today's plan?"
        // praise and warnings need enough days behind them to mean something
        days > 1 && trackedDays >= 3 && (completionPercent ?: 100) < 50 -> "Plans slip sometimes. A lighter plan you finish beats a full one you don't: I can lighten today if you like."
        days > 1 && trackedDays >= 3 && (completionPercent ?: 0) >= 80 -> "That's a steady stretch. Keep the rhythm."
        days == 1 && todayDoneMinutes > 0 -> "Good, you've made a start today. Say “what should I focus on?” for what's next."
        habits.any { it.streak >= 5 } -> "Those streaks are worth protecting."
        else -> "Small steps count. Ask me what to focus on and I'll point you to a good starting point."
    }
}
