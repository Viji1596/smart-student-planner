package com.smartstudent.planner.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * A task as far as the report is concerned. [completedAt] is the exact time it was finished (when known);
 * [completedOn] is the day (known for older tasks too).
 */
data class ReportTask(
    val title: String,
    val due: LocalDate?,
    val completed: Boolean,
    val completedOn: LocalDate?,
    val completedAt: LocalDateTime? = null,
    val id: String = "",
)

data class HabitReport(val title: String, val done: Int, val expected: Int, val streak: Int)

/** For one day: how many different tasks were scheduled, and how many of those were finished. */
data class DayStat(val date: LocalDate, val scheduled: Int, val finished: Int)

/** The deadline rule: a task is on time up to 30 minutes after the end of its due day. */
object Deadline {
    const val GRACE_MINUTES = 30L

    /** The moment a task due on [due] starts to count as late. */
    fun cutoff(due: LocalDate): LocalDateTime = due.plusDays(1).atStartOfDay().plusMinutes(GRACE_MINUTES)

    /** True / false for a finished task with a due date, null when there is nothing to judge. */
    fun onTime(task: ReportTask): Boolean? {
        val due = task.due ?: return null
        task.completedAt?.let { return !it.isAfter(cutoff(due)) }
        return task.completedOn?.let { !it.isAfter(due) }
    }
}

object Grading {
    fun grade(score: Int): String = when {
        score >= 90 -> "A+"
        score >= 80 -> "A"
        score >= 70 -> "B"
        score >= 60 -> "C"
        score >= 50 -> "D"
        else -> "E"
    }

    fun words(score: Int): String = when {
        score >= 90 -> "Outstanding"
        score >= 80 -> "Great"
        score >= 70 -> "Good"
        score >= 60 -> "Fair"
        score >= 50 -> "Needs attention"
        else -> "Time to reset"
    }
}

data class PerformanceReport(
    val days: Int,
    val from: LocalDate,
    val to: LocalDate,
    /** Days in the range for which something was finished or missed (a plan alone says nothing yet). */
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
    /** Finished within the deadline (up to 30 minutes after the end of the due day counts), finished late, still open past it. */
    val finishedOnTime: Int = 0,
    val finishedLate: Int = 0,
    val missedInPeriod: Int = 0,
    /** How many different tasks were scheduled over the days of the period, and how many of them were finished that day. */
    val scheduledTasks: Int = 0,
    val finishedScheduled: Int = 0,
    val dayStats: List<DayStat> = emptyList(),
    val score: Int? = null,
    val grade: String = "",
    val insights: List<String> = emptyList(),
) {
    val finishedPercent: Int? get() = if (scheduledTasks > 0) Math.round(100.0 * finishedScheduled / scheduledTasks).toInt() else null

    companion object {
        /**
         * @param sessions session history (any dates; only those in the range, and the period before it, are used)
         * @param today the last day of the report
         * @param now the moment the report is made (tasks past their deadline at this moment count as missed)
         */
        fun build(
            days: Int,
            today: LocalDate,
            sessions: List<PlannedSession>,
            tasks: List<ReportTask>,
            habits: List<HabitInfo>,
            completions: Map<String, Set<LocalDate>>,
            now: LocalDateTime = today.atTime(23, 59),
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

            // ---- deadlines: on time (up to 30 minutes after the due day), late, and still open past it
            val judged = completedInRange.mapNotNull { t -> Deadline.onTime(t)?.let { t to it } }
            val finishedOnTime = judged.count { it.second }
            val finishedLate = judged.count { !it.second }
            val periodStart = from.atStartOfDay()
            val missedInPeriod = open.count { t -> t.due != null && Deadline.cutoff(t.due) <= now && Deadline.cutoff(t.due) >= periodStart }
            val overdueOpen = open.count { t -> t.due != null && Deadline.cutoff(t.due) <= now }

            // ---- each day: how many tasks were scheduled, and how many of those were finished
            val taskRows = inRange.filter { it.kind == WorkKind.TASK }
            val completedByDay = completedInRange.groupBy { it.completedOn!! }
            val dayStats = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= today }.map { day ->
                val rows = taskRows.filter { it.date == day }
                val scheduled = rows.map { it.refId }.toSet()
                val doneIds = rows.filter { it.status == SessionStatus.DONE }.map { it.refId }.toSet()
                val completedIds = (completedByDay[day] ?: emptyList()).map { it.id }.filter { it.isNotEmpty() }.toSet()
                DayStat(day, scheduled.size, scheduled.count { it in doneIds || it in completedIds })
            }.toList()
            val scheduledTasks = dayStats.sumOf { it.scheduled }
            val finishedScheduled = dayStats.sumOf { it.finished }

            // ---- one score from the two things that matter: finishing what was scheduled, and meeting deadlines
            val dayRate = if (scheduledTasks > 0) 100.0 * finishedScheduled / scheduledTasks else null
            val deadlineBase = finishedOnTime + finishedLate + missedInPeriod
            val deadlineRate = if (deadlineBase > 0) 100.0 * finishedOnTime / deadlineBase else null
            val score = when {
                dayRate != null && deadlineRate != null -> Math.round(0.5 * dayRate + 0.5 * deadlineRate).toInt()
                dayRate != null -> Math.round(dayRate).toInt()
                deadlineRate != null -> Math.round(deadlineRate).toInt()
                else -> null
            }

            val habitReports = habits.map { habitReport(it, from, today, completions[it.id] ?: emptySet()) }
            val trackedDays = inRange.filter { it.status != SessionStatus.PLANNED }.map { it.date }.distinct().size

            return PerformanceReport(
                days = days,
                from = from,
                to = today,
                trackedDays = trackedDays,
                sessionsDone = done.size,
                sessionsMissed = missedKeys.size,
                minutesDone = minutesDone,
                minutesDonePrevious = if (trackedPrevious) previousMinutes else null,
                completionPercent = if (finished > 0) Math.round(100.0 * doneKeys.size / finished).toInt() else null,
                bestDay = best?.key,
                bestDayMinutes = best?.value ?: 0,
                tasksCompleted = completedInRange.size,
                tasksOpen = open.size,
                overdue = overdueOpen,
                dueSoon = open.count { it.due != null && Deadline.cutoff(it.due) > now && it.due <= today.plusDays(7) },
                onTimePercent = if (judged.isNotEmpty()) Math.round(100.0 * finishedOnTime / judged.size).toInt() else null,
                mostMissed = mostMissed,
                habits = habitReports,
                todayDoneMinutes = sessions.filter { it.date == today && it.status == SessionStatus.DONE }.sumOf { it.duration },
                todayPlannedLeftMinutes = sessions.filter { it.date == today && it.status == SessionStatus.PLANNED }.sumOf { it.duration },
                finishedOnTime = finishedOnTime,
                finishedLate = finishedLate,
                missedInPeriod = missedInPeriod,
                scheduledTasks = scheduledTasks,
                finishedScheduled = finishedScheduled,
                dayStats = dayStats,
                score = score,
                grade = score?.let { Grading.grade(it) } ?: "",
                insights = if (days > 1) insights(inRange, dayStats, judged, mostMissed, taskRows) else emptyList(),
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

        private fun bucket(startMin: Int): String = when {
            startMin < 12 * 60 -> "morning"
            startMin < 17 * 60 -> "afternoon"
            startMin < 21 * 60 -> "evening"
            else -> "late evening"
        }

        /** What the numbers say about how the student works. Only things with enough data behind them. */
        private fun insights(
            inRange: List<PlannedSession>,
            dayStats: List<DayStat>,
            judged: List<Pair<ReportTask, Boolean>>,
            mostMissed: String?,
            taskRows: List<PlannedSession>,
        ): List<String> {
            val out = mutableListOf<String>()

            // 1. which weekdays go well and which don't
            val byWeekday = dayStats.filter { it.scheduled >= 2 }.groupBy { it.date.dayOfWeek }
                .mapValues { e -> e.value.sumOf { it.finished } to e.value.sumOf { it.scheduled } }
            if (byWeekday.size >= 2) {
                fun rate(p: Pair<Int, Int>) = 100.0 * p.first / p.second
                val bestDay = byWeekday.maxByOrNull { rate(it.value) }!!
                val worstDay = byWeekday.minByOrNull { rate(it.value) }!!
                if (bestDay.key != worstDay.key && rate(bestDay.value) - rate(worstDay.value) >= 30) {
                    fun name(d: java.time.DayOfWeek) = d.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + "s"
                    out += "You're strongest on ${name(bestDay.key)} (${bestDay.value.first} of ${bestDay.value.second} scheduled tasks finished) " +
                        "and weakest on ${name(worstDay.key)} (${worstDay.value.first} of ${worstDay.value.second}). " +
                        "Keep ${name(worstDay.key)} lighter."
                }
            }

            // 2. the time of day that works
            val timed = inRange.filter { it.kind == WorkKind.TASK && it.status != SessionStatus.PLANNED }
            val byBucket = timed.groupBy { bucket(it.startMin) }.mapValues { e ->
                val doneCount = e.value.count { it.status == SessionStatus.DONE }
                val missedCount = e.value.filter { it.status == SessionStatus.MISSED }.map { it.date to it.refId }.distinct().size
                doneCount to (doneCount + missedCount)
            }.filter { it.value.second >= 3 }
            if (byBucket.size >= 2) {
                fun rate(p: Pair<Int, Int>) = Math.round(100.0 * p.first / p.second).toInt()
                val bestBucket = byBucket.maxByOrNull { rate(it.value) }!!
                val worstBucket = byBucket.minByOrNull { rate(it.value) }!!
                if (rate(bestBucket.value) - rate(worstBucket.value) >= 25) {
                    out += "You finish more in the ${bestBucket.key} (${rate(bestBucket.value)}%) than in the ${worstBucket.key} (${rate(worstBucket.value)}%). " +
                        "Plan your hardest work for the ${bestBucket.key}."
                }
            }

            // 3. how close to the deadline you finish
            val withDue = judged.map { it.first }.filter { it.due != null }
            if (withDue.size >= 3) {
                val early = withDue.count { t ->
                    val day = t.completedOn ?: return@count false
                    !day.isAfter(t.due!!.minusDays(1))
                }
                val lastMinute = withDue.size - early
                when {
                    lastMinute * 100 >= withDue.size * 60 ->
                        out += "You tend to finish close to deadlines ($lastMinute of ${withDue.size} on the due day or later). Starting a day earlier would give you a safety margin."
                    early * 100 >= withDue.size * 60 ->
                        out += "You usually finish ahead of deadlines ($early of ${withDue.size} at least a day early). That's a strong habit."
                }
            }

            // 4. something that keeps slipping
            if (mostMissed != null) {
                val daysMissed = inRange.filter { it.status == SessionStatus.MISSED && it.title == mostMissed }.map { it.date }.distinct().size
                if (daysMissed >= 2) {
                    out += "“$mostMissed” keeps slipping (missed on $daysMissed days). Try a smaller first step, or a different time of day."
                }
            }

            // 5. how steady you are
            val active = inRange.filter { it.status == SessionStatus.DONE }.map { it.date }.distinct().size
            val tracked = inRange.filter { it.status != SessionStatus.PLANNED }.map { it.date }.distinct().size
            if (tracked >= 4) {
                when {
                    active * 100 >= tracked * 80 -> out += "You worked on $active of $tracked tracked days. That's steady consistency."
                    active * 100 <= tracked * 40 -> out += "You worked on only $active of $tracked tracked days. Shorter sessions on more days usually works better than big pushes."
                }
            }
            return out.take(4)
        }
    }

    /** A friendly, plain-text version of the report. */
    fun toText(): String {
        val lines = mutableListOf(if (days == 1) "Here's how today is going:" else "Here's how the last $days days went:")

        if (score != null) lines += "Grade $grade · $score/100 (${Grading.words(score)})"

        if (todayDoneMinutes > 0 || todayPlannedLeftMinutes > 0) {
            lines += "• Today so far: ${TimeUtil.formatDuration(todayDoneMinutes)} done, ${TimeUtil.formatDuration(todayPlannedLeftMinutes)} still planned."
        }

        // finished versus scheduled, said the way a friend would
        val pct = finishedPercent
        if (pct != null) {
            val tail = "${finishedScheduled} of $scheduledTasks scheduled task${if (scheduledTasks == 1) "" else "s"} ($pct%)"
            lines += "• " + when {
                finishedScheduled == scheduledTasks && scheduledTasks >= 2 -> "Congratulations! You finished every scheduled task: $tail."
                pct >= 80 -> "Congratulations! You finished $tail."
                pct >= 50 -> "Good progress: you finished $tail."
                finishedScheduled == 0 -> "None of the $scheduledTasks scheduled task${if (scheduledTasks == 1) "" else "s"} is finished yet."
                else -> "You finished $tail. There's room to catch up."
            }
        } else {
            lines += "• No tasks were scheduled in this period yet."
        }

        if (finishedOnTime + finishedLate + missedInPeriod > 0) {
            lines += "• Deadlines: $finishedOnTime finished on time (up to 30 minutes after the due day counts), $finishedLate finished late" +
                (if (missedInPeriod > 0) ", $missedInPeriod missed (still open past the deadline)" else "") + "."
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

        if (insights.isNotEmpty()) {
            lines += ""
            lines += "What I notice about how you work:"
            insights.forEach { lines += "– $it" }
        }

        lines += ""
        lines += advice()
        return lines.joinToString("\n")
    }

    /** A short notification: a title and a few lines. */
    fun notification(): Pair<String, String> {
        val label = when {
            days == 1 -> "Today"
            days <= 7 -> "Your week"
            else -> "Your month"
        }
        val title = when {
            scheduledTasks > 0 -> "$label: $finishedScheduled of $scheduledTasks tasks done" + if (grade.isNotEmpty()) " · Grade $grade" else ""
            else -> "$label: summary"
        }
        val parts = mutableListOf<String>()
        finishedPercent?.let { pct ->
            parts += when {
                pct >= 80 -> "Congratulations, great work!"
                pct >= 50 -> "Good progress."
                else -> "Tomorrow is a fresh start."
            }
        }
        if (finishedOnTime + finishedLate + missedInPeriod > 0) {
            parts += "$finishedOnTime on time · $finishedLate late" + if (missedInPeriod > 0) " · $missedInPeriod missed" else ""
        }
        if (minutesDone > 0) parts += "${TimeUtil.formatDuration(minutesDone)} of focused work."
        insights.firstOrNull()?.let { parts += it }
        if (parts.isEmpty()) parts += "Open the app to see your progress."
        return title to parts.joinToString("\n")
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
