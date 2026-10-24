package com.smartstudent.planner.domain

import java.time.LocalDate

sealed interface PlaceAllResult {
    data class Placed(val starts: List<Int>) : PlaceAllResult
    data class Refused(val reason: String) : PlaceAllResult
}

/**
 * Swapping and moving a whole task: all of its sessions for the rest of today, not one slot.
 * A long task is often planned as several sessions, and when the student says "swap maths and physics" they mean the
 * tasks, with all the time each has.
 */
object TaskMover {

    /**
     * Swaps two tasks. The time the two tasks have together (the slots of all their sessions) is shared out again:
     * the task that was later now goes first, and each keeps exactly the minutes it had. A task's work may be cut into
     * pieces where a slot ends. The result is pinned, so re-planning keeps it where the student put it.
     *
     * @param a the upcoming (not started) planned sessions of the first task, all with the same refId
     * @param b the same for the second task
     */
    fun swap(date: LocalDate, a: List<PlannedSession>, b: List<PlannedSession>): List<PlannedSession> {
        require(a.isNotEmpty() && b.isNotEmpty()) { "both tasks need a session to swap" }
        val slots = (a + b).sortedBy { it.startMin }.map { it.startMin to it.endMin }
        val aStartsFirst = a.minOf { it.startMin } <= b.minOf { it.startMin }
        val order = if (aStartsFirst) listOf(b, a) else listOf(a, b)

        val out = mutableListOf<PlannedSession>()
        var slot = 0
        var cursor = slots[0].first
        for (task in order) {
            val template = task.minByOrNull { it.startMin }!!
            var left = task.sumOf { it.duration }
            while (left > 0) {
                while (cursor >= slots[slot].second) {
                    slot++
                    cursor = slots[slot].first
                }
                val take = minOf(left, slots[slot].second - cursor)
                val last = out.lastOrNull()
                if (last != null && last.kind == template.kind && last.refId == template.refId && last.endMin == cursor) {
                    out[out.lastIndex] = last.copy(endMin = cursor + take) // slots that touch make one session
                } else {
                    out += template.copy(
                        id = PlanReconciler.sessionId(date, template.kind, template.refId, cursor),
                        startMin = cursor,
                        endMin = cursor + take,
                        status = SessionStatus.PLANNED,
                        pinned = true,
                    )
                }
                cursor += take
                left -= take
            }
        }
        return out
    }

    /**
     * Places all of a task's sessions ([durations], in order) back to back from [requestedStart]. The first one goes
     * where asked (or, if [flexible], at the nearest free time); the others follow it, in the nearest free time after
     * the one before. Nothing is changed unless all of them fit.
     */
    fun placeAll(
        plan: DayPlan,
        blockers: List<TimeBlock>,
        durations: List<Int>,
        requestedStart: Int,
        nowMin: Int,
        flexible: Boolean,
    ): PlaceAllResult {
        val taken = blockers.toMutableList()
        val starts = mutableListOf<Int>()
        var next = requestedStart
        for ((i, duration) in durations.withIndex()) {
            when (val r = SessionMover.place(plan, taken, duration, next, nowMin, flexible || i > 0)) {
                is MoveResult.Placed -> {
                    starts += r.startMin
                    taken += TimeBlock(r.startMin, r.startMin + duration, "task", BlockKind.CLASS)
                    next = r.startMin + duration
                }
                is MoveResult.Refused -> {
                    val reason = if (i == 0) r.reason
                    else "All of it (${TimeUtil.formatDuration(durations.sum())}) doesn't fit from then: the rest, ${TimeUtil.formatDuration(durations.drop(i).sum())}, has no free time left today."
                    return PlaceAllResult.Refused(reason)
                }
            }
        }
        return PlaceAllResult.Placed(starts)
    }
}
