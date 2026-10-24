package com.smartstudent.planner.domain

import java.time.LocalDate

/**
 * The committed plan for today.
 *
 * The live planner always plans "from now", so its sessions slide forward as time passes. To remind
 * you at the right time (and to notice a missed session) today's plan is frozen into PlannedSessions.
 * It is rebuilt only when something real changes: you finish or skip something, a task or habit
 * changes, a session is missed, or you ask for a re-plan.
 */
enum class SessionStatus { PLANNED, DONE, MISSED }

data class PlannedSession(
    val id: String,
    val date: LocalDate,
    val startMin: Int,
    val endMin: Int,
    val kind: WorkKind,
    val refId: String,
    val title: String,
    val priority: Priority,
    val status: SessionStatus,
    /** Placed by hand (for example by asking the assistant): re-planning keeps it where it is. */
    val pinned: Boolean = false,
) {
    val duration: Int get() = endMin - startMin

    /** The form the planner and the Home screen use for a session that is still to come. */
    fun toSession() = Session(
        startMin, endMin,
        WorkItem(kind, refId, title, duration, priority, tier = 1, dueDay = null, divisible = kind == WorkKind.TASK),
        plannedId = id,
        pinned = pinned,
    )
}

/**
 * One line in "Earlier today". A task missed three times is ONE entry ("Missed 3 times"), not three.
 * [count] is how many sessions were rolled into the line.
 */
data class HistoryEntry(
    val kind: WorkKind,
    val refId: String,
    val title: String,
    val status: SessionStatus, // DONE or MISSED
    val count: Int,
    /** The most recent session of this line. */
    val startMin: Int,
    val endMin: Int,
    /** DONE: total minutes done. MISSED: the longest single session (what "I did it" records). */
    val minutes: Int,
)

/** A session that was missed, and where its work went ([movedToMin] is null if today has no room). */
data class MissedInfo(val session: PlannedSession, val movedToMin: Int?)

data class ReplanResult(
    val sessions: List<PlannedSession>,
    val newlyMissed: List<MissedInfo>,
    val unscheduled: List<Unscheduled>,
)

object PlanReconciler {
    /**
     * Today's finished and missed sessions as one line per task or habit.
     * Missed lines for something that is already finished ([finishedRefIds]) are left out:
     * there is nothing left to do about them.
     */
    fun summarizeHistory(sessions: List<PlannedSession>, finishedRefIds: Set<String>): List<HistoryEntry> {
        val entries = mutableListOf<HistoryEntry>()
        val groups = sessions
            .filter { it.status == SessionStatus.DONE || it.status == SessionStatus.MISSED }
            .groupBy { Triple(it.kind, it.refId, it.status) }
        for ((key, group) in groups) {
            val (kind, refId, status) = key
            if (status == SessionStatus.MISSED && refId in finishedRefIds) continue
            val latest = group.maxByOrNull { it.startMin }!!
            entries += HistoryEntry(
                kind = kind,
                refId = refId,
                title = latest.title,
                status = status,
                count = group.size,
                startMin = latest.startMin,
                endMin = latest.endMin,
                minutes = if (status == SessionStatus.DONE) group.sumOf { it.duration } else group.maxOf { it.duration },
            )
        }
        return entries.sortedWith(compareBy({ it.endMin }, { it.title }))
    }

    /** How long after a session ends it can still be ticked off before it counts as missed. */
    const val GRACE_MIN = 5

    fun sessionId(date: LocalDate, kind: WorkKind, refId: String, startMin: Int) =
        "$date|${kind.name}|$refId|$startMin"

    /** Sessions that have ended but were neither ticked off nor marked missed yet (the grace period). */
    fun justEnded(sessions: List<PlannedSession>, nowMin: Int): List<PlannedSession> =
        sessions.filter { it.status == SessionStatus.PLANNED && it.endMin <= nowMin }

    /** True when at least one session is past its grace period, so the plan must be updated. */
    fun needsMissedHandling(sessions: List<PlannedSession>, nowMin: Int, graceMin: Int = GRACE_MIN): Boolean =
        sessions.any { it.status == SessionStatus.PLANNED && it.endMin + graceMin <= nowMin }

    /** PLANNED sessions that ended more than [graceMin] minutes ago become MISSED. */
    fun markMissed(sessions: List<PlannedSession>, nowMin: Int, graceMin: Int = GRACE_MIN): List<PlannedSession> =
        sessions.map {
            if (it.status == SessionStatus.PLANNED && it.endMin + graceMin <= nowMin) {
                it.copy(status = SessionStatus.MISSED)
            } else it
        }

    /**
     * Rebuilds today's plan from [nowMin] on.
     *  - finished (DONE) and MISSED sessions stay as history;
     *  - a session that is under way (started, not yet ended) stays, and its minutes are not planned again;
     *  - everything still to come is planned afresh, so missed work lands in the time that is left;
     *  - sessions in [skipIds] count as missed right now.
     */
    fun replanToday(
        rules: DayRules,
        entries: List<FixedEntry>,
        tasks: List<TaskInfo>,
        habits: List<HabitInfo>,
        completions: Map<String, Set<LocalDate>>,
        existing: List<PlannedSession>,
        date: LocalDate,
        nowMin: Int,
        skipIds: Set<String> = emptySet(),
    ): ReplanResult {
        val skipped = existing.map {
            if (it.id in skipIds && it.status == SessionStatus.PLANNED) it.copy(status = SessionStatus.MISSED) else it
        }
        val checked = markMissed(skipped, nowMin)

        val before = existing.associateBy { it.id }
        val newlyMissed = checked.filter {
            it.status == SessionStatus.MISSED && before[it.id]?.status == SessionStatus.PLANNED
        }

        val history = checked.filter { it.status != SessionStatus.PLANNED }
        val underWay = checked.filter { it.status == SessionStatus.PLANNED && it.startMin <= nowMin }

        // Sessions the student placed by hand stay put, as long as their task or habit still needs them.
        val taskById = tasks.associateBy { it.id }
        val pinnedAhead = checked.filter { s ->
            s.status == SessionStatus.PLANNED && s.pinned && s.startMin > nowMin && when (s.kind) {
                WorkKind.TASK -> taskById[s.refId]?.let { it.estimatedMinutes - it.spentMinutes > 0 } == true
                WorkKind.HABIT -> habits.any { it.id == s.refId } && date !in (completions[s.refId] ?: emptySet())
            }
        }
        val committedAhead = underWay + pinnedAhead

        // Work that is under way or pinned for later counts as done for the rest of the planning.
        val aheadMinutes = committedAhead.filter { it.kind == WorkKind.TASK }
            .groupBy { it.refId }.mapValues { e -> e.value.sumOf { it.duration } }
        val adjustedTasks = tasks.map { task ->
            val extra = aheadMinutes[task.id] ?: 0
            if (extra > 0) task.copy(spentMinutes = minOf(task.estimatedMinutes, task.spentMinutes + extra)) else task
        }
        val adjustedCompletions: MutableMap<String, Set<LocalDate>> = completions.toMutableMap()
        for (s in committedAhead) {
            if (s.kind == WorkKind.HABIT) adjustedCompletions[s.refId] = (adjustedCompletions[s.refId] ?: emptySet()) + date
        }

        val doneToday: Map<String, Int> =
            (history.filter { it.status == SessionStatus.DONE } + committedAhead)
                .filter { it.kind == WorkKind.TASK }
                .groupBy { it.refId }.mapValues { e -> e.value.sumOf { it.duration } }

        // Whatever comes next starts after the session under way, with the usual short break in between.
        val afterUnderWay = underWay.maxOfOrNull { it.endMin + rules.breaks.shortMin } ?: 0
        val earliest = maxOf(nowMin, afterUnderWay)
        val view = DayBuilder.build(
            rules, entries, adjustedTasks, habits, adjustedCompletions, date, earliest, doneToday,
            extraBusy = pinnedAhead.map { FixedEntry(it.title, it.startMin, it.endMin) },
        )

        val fresh = view.schedule.sessions.map {
            PlannedSession(
                id = sessionId(date, it.item.kind, it.item.refId, it.startMin),
                date = date,
                startMin = it.startMin,
                endMin = it.endMin,
                kind = it.item.kind,
                refId = it.item.refId,
                title = it.item.title,
                priority = it.item.priority,
                status = SessionStatus.PLANNED,
            )
        }

        val missedInfo = newlyMissed.map { missed ->
            MissedInfo(missed, fresh.firstOrNull { it.kind == missed.kind && it.refId == missed.refId }?.startMin)
        }

        return ReplanResult(
            sessions = (history + underWay + pinnedAhead + fresh).sortedWith(compareBy({ it.startMin }, { it.endMin })),
            newlyMissed = missedInfo,
            unscheduled = view.schedule.unscheduled,
        )
    }
}
