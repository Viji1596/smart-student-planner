package com.smartstudent.planner.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** An open (not completed) task, in plain form. */
data class TaskInfo(
    val id: String,
    val title: String,
    val priority: String,
    val estimatedMinutes: Int,
    val spentMinutes: Int,
    val dueDay: LocalDate?,
)

enum class HabitKind { DAILY, DAYS, WEEKLY }

/**
 * A recurring task.
 *  DAILY  - every day
 *  DAYS   - on fixed weekdays (1 = Monday ... 7 = Sunday), e.g. Mon/Wed/Fri
 *  WEEKLY - [timesPerWeek] times a week, on whichever days suit (spread out evenly by default)
 */
data class HabitInfo(
    val id: String,
    val title: String,
    val minutes: Int,
    val priority: String,
    val kind: HabitKind,
    val days: Set<Int>,
    val timesPerWeek: Int,
)

object HabitRules {
    private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** Evenly spread target days for n sessions a week, as 0-based weekdays (Monday = 0). */
    fun weeklyTargets(timesPerWeek: Int): List<Int> {
        val n = timesPerWeek.coerceIn(1, 7)
        return (0 until n).map { k -> (k * 7) / n }
    }

    /**
     * Is [habit] due on [date]? [done] holds the dates the habit was completed.
     * A habit already done on [date] is not "due" any more.
     */
    fun isDue(habit: HabitInfo, date: LocalDate, done: Set<LocalDate>): Boolean {
        if (date in done) return false
        return when (habit.kind) {
            HabitKind.DAILY -> true
            HabitKind.DAYS -> date.dayOfWeek.value in habit.days
            HabitKind.WEEKLY -> {
                val n = habit.timesPerWeek.coerceIn(1, 7)
                val todayIndex = date.dayOfWeek.value - 1
                val weekStart = date.minusDays(todayIndex.toLong())
                val doneThisWeek = done.count { !it.isBefore(weekStart) && !it.isAfter(date) }
                if (doneThisWeek >= n) {
                    false
                } else {
                    val expectedByToday = weeklyTargets(n).count { it <= todayIndex }
                    val daysLeftInWeek = 7 - todayIndex
                    // Behind the even schedule, or now or never to still reach the weekly goal.
                    doneThisWeek < expectedByToday || (n - doneThisWeek) >= daysLeftInWeek
                }
            }
        }
    }

    fun doneThisWeek(done: Set<LocalDate>, date: LocalDate): Int {
        val weekStart = date.minusDays((date.dayOfWeek.value - 1).toLong())
        return done.count { !it.isBefore(weekStart) && !it.isAfter(weekStart.plusDays(6)) }
    }

    fun describe(habit: HabitInfo): String = when (habit.kind) {
        HabitKind.DAILY -> "Every day"
        HabitKind.DAYS ->
            if (habit.days.size == 7) "Every day"
            else habit.days.sorted().filter { it in 1..7 }.joinToString(", ") { DAY_NAMES[it - 1] }
        HabitKind.WEEKLY -> "${habit.timesPerWeek}× per week"
    }
}

object WorkPlanner {
    /** How urgent is something due on [dueDay], seen from [date]? See [WorkItem.tier]. */
    fun tierFor(dueDay: LocalDate?, date: LocalDate): Int {
        if (dueDay == null) return 5
        val daysUntil = ChronoUnit.DAYS.between(date, dueDay)
        return when {
            daysUntil < 0 -> 0
            daysUntil == 0L -> 1
            daysUntil <= 2 -> 2
            daysUntil <= 7 -> 3
            else -> 4
        }
    }

    /** The slice of each open task that should be worked on during [date]. */
    fun taskItems(
        tasks: List<TaskInfo>,
        date: LocalDate,
        maxSession: Int = Scheduler.MAX_SESSION,
    ): List<WorkItem> = tasks.mapNotNull { task ->
        val remaining = task.estimatedMinutes - task.spentMinutes
        if (remaining <= 0) return@mapNotNull null

        val tier = tierFor(task.dueDay, date)
        val share = when {
            task.dueDay == null -> minOf(remaining, 60)
            tier <= 1 -> remaining // overdue or due today: all of it
            remaining <= maxSession -> remaining // small enough to finish in one go
            else -> {
                // Spread a big task over the days left, but not in slivers.
                val daysInclusive = (ChronoUnit.DAYS.between(date, task.dueDay) + 1).toInt()
                val perDay = (remaining + daysInclusive - 1) / daysInclusive
                perDay.coerceAtLeast(30).coerceAtMost(remaining)
            }
        }
        WorkItem(
            kind = WorkKind.TASK,
            refId = task.id,
            title = task.title,
            minutes = share,
            priority = Priority.parse(task.priority),
            tier = tier,
            dueDay = task.dueDay,
            divisible = true,
        )
    }

    fun habitItems(
        habits: List<HabitInfo>,
        completions: Map<String, Set<LocalDate>>,
        date: LocalDate,
    ): List<WorkItem> = habits.mapNotNull { habit ->
        if (!HabitRules.isDue(habit, date, completions[habit.id] ?: emptySet())) return@mapNotNull null
        WorkItem(
            kind = WorkKind.HABIT,
            refId = habit.id,
            title = habit.title,
            minutes = habit.minutes,
            priority = Priority.parse(habit.priority),
            tier = 1,
            dueDay = null,
            divisible = false,
        )
    }
}

sealed interface TimelineItem {
    val startMin: Int
    val endMin: Int
}

data class BusyItem(val block: TimeBlock) : TimelineItem {
    override val startMin: Int get() = block.startMin
    override val endMin: Int get() = block.endMin
}

data class SessionItem(val session: Session) : TimelineItem {
    override val startMin: Int get() = session.startMin
    override val endMin: Int get() = session.endMin
}

data class FreeItem(val block: TimeBlock) : TimelineItem {
    override val startMin: Int get() = block.startMin
    override val endMin: Int get() = block.endMin
}

data class BreakItem(val block: TimeBlock) : TimelineItem {
    override val startMin: Int get() = block.startMin
    override val endMin: Int get() = block.endMin
}

data class DayView(
    val plan: DayPlan,
    val schedule: SchedulePlan,
    val doneHabits: List<HabitInfo>,
    val timeline: List<TimelineItem>,
)

object DayBuilder {
    /**
     * Everything the Home screen needs for [date].
     * [nowMin] is the current time (minutes since midnight) when planning today; pass null for other days.
     * [tasks] must contain only open tasks.
     */
    fun build(
        rules: DayRules,
        entries: List<FixedEntry>,
        tasks: List<TaskInfo>,
        habits: List<HabitInfo>,
        completions: Map<String, Set<LocalDate>>,
        date: LocalDate,
        nowMin: Int?,
    ): DayView {
        val plan = DayPlanner.plan(rules, entries)
        val earliest = nowMin?.let { ((it + 4) / 5) * 5 }

        val items = WorkPlanner.taskItems(tasks, date) + WorkPlanner.habitItems(habits, completions, date)
        val schedule = Scheduler.schedule(
            free = plan.freeBlocks,
            items = items,
            earliestStart = earliest,
            breakMin = rules.breaks.shortMin,
            longBreakAfterMin = rules.breaks.longAfterMin,
            longBreakMin = rules.breaks.longMin,
        )

        val doneHabits = habits.filter { completions[it.id]?.contains(date) == true }

        val busy = plan.blocks
            .filter { it.kind != BlockKind.FREE && (nowMin == null || it.endMin > nowMin) }
            .map { BusyItem(it) }
        val timeline: List<TimelineItem> =
            (busy + schedule.sessions.map { SessionItem(it) } + schedule.leftoverFree.map { FreeItem(it) } +
                schedule.breaks.filter { nowMin == null || it.endMin > nowMin }.map { BreakItem(it) })
                .sortedWith(compareBy({ it.startMin }, { it.endMin }))

        return DayView(plan, schedule, doneHabits, timeline)
    }

    /**
     * Plans [days] consecutive days starting at [startDate] and returns one DayView per day.
     *
     * Days are planned in order, and work placed on an earlier day is not planned again on a
     * later one. So a task due tomorrow shows up once (today if there is room, otherwise
     * tomorrow), a big task is spread across the days, and a "2 times a week" habit is counted
     * against the week as it is planned.
     *
     * [nowMin] only applies to the first day (today).
     */
    fun buildRange(
        rules: DayRules,
        entriesByWeekday: Map<Int, List<FixedEntry>>,
        tasks: List<TaskInfo>,
        habits: List<HabitInfo>,
        completions: Map<String, Set<LocalDate>>,
        startDate: LocalDate,
        days: Int,
        nowMin: Int?,
    ): List<DayView> {
        var openTasks = tasks
        val done: MutableMap<String, MutableSet<LocalDate>> =
            completions.mapValues { it.value.toMutableSet() }.toMutableMap()
        val views = mutableListOf<DayView>()

        for (i in 0 until days) {
            val date = startDate.plusDays(i.toLong())
            val view = build(
                rules = rules,
                entries = entriesByWeekday[date.dayOfWeek.value] ?: emptyList(),
                tasks = openTasks,
                habits = habits,
                completions = done,
                date = date,
                nowMin = if (i == 0) nowMin else null,
            )
            views += view

            // Whatever was planned today counts as covered for the days after it.
            val plannedPerTask = view.schedule.sessions
                .filter { it.item.kind == WorkKind.TASK }
                .groupBy({ it.item.refId }, { it.duration })
                .mapValues { it.value.sum() }
            openTasks = openTasks.map { task ->
                val planned = plannedPerTask[task.id] ?: 0
                if (planned == 0) task
                else task.copy(spentMinutes = minOf(task.estimatedMinutes, task.spentMinutes + planned))
            }
            for (session in view.schedule.sessions) {
                if (session.item.kind == WorkKind.HABIT) {
                    done.getOrPut(session.item.refId) { mutableSetOf() }.add(date)
                }
            }
        }
        return views
    }
}
