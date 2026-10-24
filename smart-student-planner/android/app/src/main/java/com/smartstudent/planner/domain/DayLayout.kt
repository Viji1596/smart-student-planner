package com.smartstudent.planner.domain

/**
 * Lays committed sessions out on the day: what is free, and what is a break between two sessions.
 * Only free blocks that still lie in the future are considered when [nowMin] is given.
 */
data class DayLayoutResult(
    val leftoverFree: List<TimeBlock>,
    val breaks: List<TimeBlock>,
)

object DayLayout {
    private const val MIN_VISIBLE_GAP = Scheduler.MIN_VISIBLE_GAP

    /**
     * A gap between two sessions that is no longer than [maxBreakMin] is a break; a longer one is free time.
     */
    fun build(plan: DayPlan, sessions: List<Session>, nowMin: Int?, maxBreakMin: Int): DayLayoutResult {
        val earliest = nowMin?.let { ((it + 4) / 5) * 5 }
        val free = mutableListOf<TimeBlock>()
        val breaks = mutableListOf<TimeBlock>()

        for (block in plan.freeBlocks) {
            val start = if (earliest != null) maxOf(block.startMin, earliest) else block.startMin
            val end = block.endMin
            if (end <= start) continue

            val inside = sessions
                .filter { it.startMin >= start && it.endMin <= end }
                .sortedBy { it.startMin }

            var cursor = start
            var first = true
            for (s in inside) {
                if (s.startMin > cursor) {
                    val gap = TimeBlock(cursor, s.startMin, "", BlockKind.FREE)
                    when {
                        !first && gap.duration <= maxBreakMin ->
                            breaks += TimeBlock(gap.startMin, gap.endMin, "Break", BlockKind.BREAK)
                        gap.duration >= MIN_VISIBLE_GAP ->
                            free += TimeBlock(gap.startMin, gap.endMin, "Free time", BlockKind.FREE)
                    }
                }
                cursor = maxOf(cursor, s.endMin)
                first = false
            }
            if (end - cursor >= MIN_VISIBLE_GAP) {
                free += TimeBlock(cursor, end, "Free time", BlockKind.FREE)
            }
        }
        return DayLayoutResult(free.sortedBy { it.startMin }, breaks.sortedBy { it.startMin })
    }
}
