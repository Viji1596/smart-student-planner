package com.smartstudent.planner.domain

/**
 * Pure Kotlin (no Android classes) so it can be unit-tested on the JVM.
 * All times are "minutes since midnight". A sleep time earlier than the wake time
 * means "after midnight" (e.g. wake 07:00, sleep 00:30 -> window 420..1470).
 */
enum class BlockKind { MORNING, MEAL, CLASS, REST, TRAVEL, BREAK, FREE }

data class TimeBlock(
    val startMin: Int,
    val endMin: Int,
    val label: String,
    val kind: BlockKind,
) {
    val duration: Int get() = endMin - startMin
}

data class MealSlot(val name: String, val startMin: Int, val durationMin: Int)

/** A protected rest block in the middle of the day (a nap, or just time to switch off). */
data class RestSlot(val startMin: Int, val durationMin: Int)

/**
 * Time spent travelling on days that have something fixed at college.
 * [toMin] and [fromMin] are separate because the way there and the way back often differ
 * (bus routes, walking uphill...). [arriveEarlyMin] is how long before the first class you
 * want to be there. Leave both at 0 to switch travel off.
 */
data class Commute(val toMin: Int = 0, val fromMin: Int = 0, val arriveEarlyMin: Int = 0)

/**
 * How rest is built into study time: a short break between sessions, and a longer one once
 * [longAfterMin] minutes of work have piled up without one (0 = no long breaks).
 */
data class BreakRules(val shortMin: Int = 10, val longAfterMin: Int = 120, val longMin: Int = 20)

data class DayRules(
    val wakeMin: Int,
    val sleepMin: Int,
    val morningRoutineMin: Int,
    val meals: List<MealSlot>,
    val rest: RestSlot? = null,
    val commute: Commute = Commute(),
    val breaks: BreakRules = BreakRules(),
)

data class FixedEntry(val title: String, val startMin: Int, val endMin: Int)

data class Conflict(val first: String, val second: String)

data class DayPlan(
    val windowStart: Int,
    val windowEnd: Int,
    /** Everything in the awake window, sorted by start time, including FREE blocks. */
    val blocks: List<TimeBlock>,
    val conflicts: List<Conflict>,
) {
    val freeBlocks: List<TimeBlock> get() = blocks.filter { it.kind == BlockKind.FREE }
    val freeMinutes: Int get() = freeBlocks.sumOf { it.duration }
}

object DayPlanner {
    /** Gaps shorter than this are not worth planning anything into. */
    const val DEFAULT_MIN_FREE_GAP = 15

    fun plan(
        rules: DayRules,
        entries: List<FixedEntry>,
        minFreeGap: Int = DEFAULT_MIN_FREE_GAP,
        /** Time that is taken but isn't a class: no travel is planned for it (e.g. a session placed by hand). */
        extraBusy: List<FixedEntry> = emptyList(),
    ): DayPlan {
        val windowStart = rules.wakeMin
        val windowEnd = if (rules.sleepMin > rules.wakeMin) rules.sleepMin else rules.sleepMin + MINUTES_PER_DAY

        val busy = mutableListOf<TimeBlock>()

        if (rules.morningRoutineMin > 0) {
            busy += TimeBlock(
                windowStart, windowStart + rules.morningRoutineMin,
                "Morning routine", BlockKind.MORNING,
            )
        }
        for (meal in rules.meals) {
            // A meal at 00:15 belongs to the "night" part of a window that runs past midnight.
            var start = meal.startMin
            if (start < windowStart && start + MINUTES_PER_DAY < windowEnd) start += MINUTES_PER_DAY
            busy += TimeBlock(start, start + meal.durationMin, meal.name, BlockKind.MEAL)
        }
        rules.rest?.takeIf { it.durationMin > 0 }?.let { rest ->
            var start = rest.startMin
            if (start < windowStart && start + MINUTES_PER_DAY < windowEnd) start += MINUTES_PER_DAY
            // A rest that would sit on top of a meal starts when the meal ends.
            val meals = busy.filter { it.kind == BlockKind.MEAL }
            repeat(meals.size + 1) {
                meals.filter { it.startMin < start + rest.durationMin && start < it.endMin }
                    .maxByOrNull { it.endMin }?.let { start = it.endMin }
            }
            busy += TimeBlock(start, start + rest.durationMin, "Rest", BlockKind.REST)
        }
        for (entry in entries) {
            busy += TimeBlock(entry.startMin, entry.endMin, entry.title, BlockKind.CLASS)
        }

        // Travel only happens on days that have something fixed at college.
        if (entries.isNotEmpty()) {
            val commute = rules.commute
            val firstStart = entries.minOf { it.startMin }
            val lastEnd = entries.maxOf { it.endMin }
            // Meals are never travelled through. When classes start after lunch, the trip to college would run
            // into lunch, so it is moved earlier: you arrive first and eat at college. Likewise a trip home that
            // would run into a meal starts after it.
            val meals = busy.filter { it.kind == BlockKind.MEAL }
            if (commute.toMin > 0) {
                var arrive = firstStart - commute.arriveEarlyMin
                // ...but never so early that it runs into the morning routine: then the overlap is reported instead.
                val earliestLeave = windowStart + rules.morningRoutineMin
                repeat(meals.size + 1) {
                    meals.filter { it.startMin < arrive && arrive - commute.toMin < it.endMin }
                        .minByOrNull { it.startMin }
                        ?.takeIf { it.startMin - commute.toMin >= earliestLeave }
                        ?.let { arrive = it.startMin }
                }
                busy += TimeBlock(arrive - commute.toMin, arrive, "Travel to college", BlockKind.TRAVEL)
            }
            if (commute.fromMin > 0) {
                var leave = lastEnd
                repeat(meals.size + 1) {
                    meals.filter { it.startMin < leave + commute.fromMin && leave < it.endMin }
                        .maxByOrNull { it.endMin }?.let { leave = it.endMin }
                }
                busy += TimeBlock(leave, leave + commute.fromMin, "Travel home", BlockKind.TRAVEL)
            }
        }

        for (extra in extraBusy) busy += TimeBlock(extra.startMin, extra.endMin, extra.title, BlockKind.CLASS)

        // Keep only what falls inside the awake window.
        val clipped = busy.mapNotNull { block ->
            val s = maxOf(block.startMin, windowStart)
            val e = minOf(block.endMin, windowEnd)
            if (e > s) block.copy(startMin = s, endMin = e) else null
        }.sortedWith(compareBy({ it.startMin }, { it.endMin }))

        val conflicts = mutableListOf<Conflict>()
        for (i in clipped.indices) {
            for (j in i + 1 until clipped.size) {
                val a = clipped[i]
                val b = clipped[j]
                if (a.startMin < b.endMin && b.startMin < a.endMin) conflicts += Conflict(a.label, b.label)
            }
        }

        val free = mutableListOf<TimeBlock>()
        var cursor = windowStart
        for (block in clipped) {
            if (block.startMin - cursor >= minFreeGap) {
                free += TimeBlock(cursor, block.startMin, "Free time", BlockKind.FREE)
            }
            cursor = maxOf(cursor, block.endMin)
        }
        if (windowEnd - cursor >= minFreeGap) {
            free += TimeBlock(cursor, windowEnd, "Free time", BlockKind.FREE)
        }

        val all = (clipped + free).sortedWith(compareBy({ it.startMin }, { it.endMin }))
        return DayPlan(windowStart, windowEnd, all, conflicts)
    }

    private const val MINUTES_PER_DAY = 24 * 60
}

object TimeUtil {
    private val HM = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")

    /** "07:30" -> 450, or null if it isn't a valid HH:mm time. */
    fun parse(hm: String): Int? {
        val match = HM.matchEntire(hm.trim()) ?: return null
        return match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
    }

    /** 450 -> "07:30". Values past midnight wrap (1470 -> "00:30"). */
    fun format(minutes: Int): String {
        val m = ((minutes % 1440) + 1440) % 1440
        return (m / 60).toString().padStart(2, '0') + ":" + (m % 60).toString().padStart(2, '0')
    }

    /** 135 -> "2 h 15 min", 45 -> "45 min", 180 -> "3 h". */
    fun formatDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h h"
            else -> "$h h $m min"
        }
    }
}
