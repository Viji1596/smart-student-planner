package com.smartstudent.planner.domain

sealed interface MoveResult {
    data class Placed(val startMin: Int) : MoveResult
    data class Refused(val reason: String) : MoveResult
}

/**
 * Can a planned session be moved to a time the student asked for? A session may only sit in free time:
 * not on a class, a meal, travel, rest or the morning routine, not on another session placed by hand,
 * and not in the past. When the request is "around the evening" ([flexible]) the nearest free slot is used.
 */
object SessionMover {
    private fun roundUp5(m: Int) = ((m + 4) / 5) * 5

    /**
     * @param plan today's day structure (classes, meals, ... and the free blocks between them)
     * @param blockers sessions that must not be overlapped (placed by hand, or already under way)
     */
    fun place(
        plan: DayPlan,
        blockers: List<TimeBlock>,
        durationMin: Int,
        requestedStart: Int,
        nowMin: Int,
        flexible: Boolean,
    ): MoveResult {
        val earliest = roundUp5(nowMin)

        fun fits(start: Int): Boolean {
            val end = start + durationMin
            return start >= earliest &&
                plan.freeBlocks.any { start >= it.startMin && end <= it.endMin } &&
                blockers.none { start < it.endMin && it.startMin < end }
        }

        if (fits(requestedStart)) return MoveResult.Placed(requestedStart)

        // the nearest slot that does fit, at or after the requested time
        var next: Int? = null
        var s = roundUp5(maxOf(requestedStart, earliest))
        while (s + durationMin <= plan.windowEnd) {
            if (fits(s)) {
                next = s
                break
            }
            s += 5
        }

        if (flexible) {
            return next?.let { MoveResult.Placed(it) }
                ?: MoveResult.Refused("There's no free slot of ${TimeUtil.formatDuration(durationMin)} left today after ${TimeUtil.format(requestedStart)}.")
        }

        val hint = next?.let { " The next free slot of ${TimeUtil.formatDuration(durationMin)} starts at ${TimeUtil.format(it)}." } ?: ""
        val end = requestedStart + durationMin
        val reason = when {
            requestedStart < earliest -> "That time has already passed."
            requestedStart < plan.windowStart || end > plan.windowEnd ->
                "That's outside your day (${TimeUtil.format(plan.windowStart)} to ${TimeUtil.format(plan.windowEnd)})."
            else -> {
                val busy = plan.blocks.firstOrNull { it.kind != BlockKind.FREE && requestedStart < it.endMin && it.startMin < end }
                val other = blockers.firstOrNull { requestedStart < it.endMin && it.startMin < end }
                when {
                    busy != null -> "That overlaps ${busy.label} (${TimeUtil.format(busy.startMin)}–${TimeUtil.format(busy.endMin)})."
                    other != null -> "That overlaps ${other.label} (${TimeUtil.format(other.startMin)}–${TimeUtil.format(other.endMin)})."
                    else -> "That slot is too short for ${TimeUtil.formatDuration(durationMin)}."
                }
            }
        }
        return MoveResult.Refused(reason + hint)
    }
}
