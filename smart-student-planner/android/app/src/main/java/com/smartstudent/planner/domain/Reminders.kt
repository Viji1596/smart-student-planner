package com.smartstudent.planner.domain

import java.time.LocalDate

/** What the user wants to be reminded about. Kept on the device, not synced. */
data class ReminderSettings(
    val enabled: Boolean = false,
    /** A reminder before each study session starts. */
    val startReminder: Boolean = true,
    /** How many minutes before a session (or a trip, meal...) the reminder comes. 0 = at the start. */
    val leadMin: Int = 5,
    /** A reminder shortly before each study session ends. */
    val endingSoon: Boolean = true,
    /** How many minutes before the end of a session that reminder comes. 0 = off. */
    val endLeadMin: Int = 5,
    /** A reminder at the moment a session ends ("Time's up"). */
    val atEnd: Boolean = true,
    val travel: Boolean = true,
    val mealsAndRest: Boolean = false,
    val morningSummary: Boolean = true,
)

enum class ReminderKind { SESSION, SESSION_ENDING, SESSION_END, TRAVEL, MEAL_REST, MORNING }

data class Reminder(
    val date: LocalDate,
    val minute: Int,
    val kind: ReminderKind,
    val title: String,
    val text: String,
    /** Stable and unique for one reminder, so it can be replaced or cancelled later. */
    val key: String,
    /** The study session this reminder is about (used for the Done / Later buttons). */
    val sessionId: String? = null,
)

/** What the morning summary says about one day. */
data class DaySummary(val date: LocalDate, val wakeMin: Int, val sessionCount: Int, val plannedMinutes: Int)

object ReminderPlanner {
    /**
     * The reminders that should still happen, given the committed plan for [today] and the day's
     * fixed blocks. Anything whose time has already passed is left out.
     */
    fun build(
        settings: ReminderSettings,
        today: LocalDate,
        nowMin: Int,
        plan: DayPlan,
        sessions: List<PlannedSession>,
        summaries: List<DaySummary>,
    ): List<Reminder> {
        if (!settings.enabled) return emptyList()
        val lead = settings.leadMin.coerceAtLeast(0)
        val out = mutableListOf<Reminder>()

        for (s in sessions.filter { it.status == SessionStatus.PLANNED }) {
            // 1. Before it starts. Normally "lead" minutes before. If that moment has already passed but the
            //    session hasn't started (a task planned for a few minutes from now), remind at the start.
            if (settings.startReminder) {
                val leadAt = s.startMin - lead
                val at = when {
                    leadAt > nowMin -> leadAt
                    s.startMin > nowMin -> s.startMin
                    else -> -1
                }
                if (at >= 0) {
                    out += Reminder(
                        date = today,
                        minute = at,
                        kind = ReminderKind.SESSION,
                        title = if (at == s.startMin) "Time to start: ${s.title}" else "Starting soon: ${s.title}",
                        text = "${TimeUtil.format(s.startMin)}–${TimeUtil.format(s.endMin)} · ${TimeUtil.formatDuration(s.duration)}",
                        key = "$today|S|${s.id}",
                    )
                }
            }

            // 2. Shortly before it ends. Skipped for a session so short that this moment would fall
            //    before it even starts, and when the moment has already passed.
            if (settings.endingSoon && settings.endLeadMin > 0) {
                val at = s.endMin - settings.endLeadMin
                if (at > s.startMin && at > nowMin) {
                    out += Reminder(
                        date = today,
                        minute = at,
                        kind = ReminderKind.SESSION_ENDING,
                        title = "${s.title} ends in ${settings.endLeadMin} min",
                        text = "Ends at ${TimeUtil.format(s.endMin)}. Tick it off in the app when you finish.",
                        key = "$today|E|${s.id}",
                    )
                }
            }

            // 3. When it ends.
            if (settings.atEnd && s.endMin > nowMin) {
                out += Reminder(
                    date = today,
                    minute = s.endMin,
                    kind = ReminderKind.SESSION_END,
                    title = "Time's up: ${s.title}",
                    text = "Did you finish? Tap Done, or Later to plan it again.",
                    key = "$today|X|${s.id}",
                    sessionId = s.id,
                )
            }
        }

        if (settings.travel) {
            for (b in plan.blocks.filter { it.kind == BlockKind.TRAVEL }) {
                val at = b.startMin - lead
                if (at <= nowMin) continue
                out += Reminder(
                    date = today,
                    minute = at,
                    kind = ReminderKind.TRAVEL,
                    title = if (lead > 0) "Time to leave in $lead min" else "Time to leave",
                    text = "${b.label} · ${TimeUtil.formatDuration(b.duration)}",
                    key = "$today|T|${b.startMin}",
                )
            }
        }

        if (settings.mealsAndRest) {
            for (b in plan.blocks.filter { it.kind == BlockKind.MEAL || it.kind == BlockKind.REST }) {
                val at = b.startMin - lead
                if (at <= nowMin) continue
                out += Reminder(
                    date = today,
                    minute = at,
                    kind = ReminderKind.MEAL_REST,
                    title = if (lead > 0) "${b.label} in $lead min" else b.label,
                    text = "${TimeUtil.format(b.startMin)}–${TimeUtil.format(b.endMin)}",
                    key = "$today|M|${b.startMin}",
                )
            }
        }

        if (settings.morningSummary) {
            for (d in summaries) {
                if (d.date.isBefore(today)) continue
                if (d.date == today && d.wakeMin <= nowMin) continue
                out += Reminder(
                    date = d.date,
                    minute = d.wakeMin,
                    kind = ReminderKind.MORNING,
                    title = "Good morning",
                    text = if (d.sessionCount == 0) "Nothing planned for today yet."
                    else "${d.sessionCount} ${if (d.sessionCount == 1) "session" else "sessions"} planned · " +
                        "${TimeUtil.formatDuration(d.plannedMinutes)} of work",
                    key = "${d.date}|W",
                )
            }
        }

        return out.sortedWith(compareBy({ it.date }, { it.minute }))
    }

    /** The minutes (today) at which to check for missed sessions: just after each one ends. */
    fun missedChecks(sessions: List<PlannedSession>, nowMin: Int): List<Int> =
        sessions.filter { it.status == SessionStatus.PLANNED }
            .map { it.endMin + PlanReconciler.GRACE_MIN }
            .filter { it > nowMin }
            .distinct()
            .sorted()
}

/** Today's plan assembled from the committed sessions (see [PlanReconciler]). */
fun DayBuilder.buildWithCommitted(
    rules: DayRules,
    entries: List<FixedEntry>,
    tasks: List<TaskInfo>,
    habits: List<HabitInfo>,
    completions: Map<String, Set<LocalDate>>,
    date: LocalDate,
    nowMin: Int,
    committed: List<PlannedSession>,
): DayView {
    val doneToday = committed.filter { it.status == SessionStatus.DONE && it.kind == WorkKind.TASK }
        .groupBy { it.refId }.mapValues { e -> e.value.sumOf { it.duration } }
    // The live view supplies the day's structure and "what doesn't fit"; the sessions come from the plan.
    val live = build(rules, entries, tasks, habits, completions, date, nowMin, doneToday)

    val upcoming = committed
        .filter { it.status == SessionStatus.PLANNED && it.endMin > nowMin }
        .sortedBy { it.startMin }
        .map { it.toSession() }
    val layout = DayLayout.build(
        live.plan, upcoming, nowMin,
        maxBreakMin = maxOf(rules.breaks.shortMin, rules.breaks.longMin),
    )
    val schedule = SchedulePlan(upcoming, live.schedule.unscheduled, layout.leftoverFree, layout.breaks)

    val busy = live.plan.blocks.filter { it.kind != BlockKind.FREE && it.endMin > nowMin }.map { BusyItem(it) }
    val timeline: List<TimelineItem> =
        (busy + upcoming.map { SessionItem(it) } + layout.leftoverFree.map { FreeItem(it) } +
            layout.breaks.map { BreakItem(it) })
            .sortedWith(compareBy({ it.startMin }, { it.endMin }))

    return DayView(live.plan, schedule, live.doneHabits, timeline)
}
