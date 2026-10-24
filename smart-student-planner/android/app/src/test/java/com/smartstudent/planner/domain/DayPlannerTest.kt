package com.smartstudent.planner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayPlannerTest {

    private fun t(hm: String) = TimeUtil.parse(hm)!!

    private val rules = DayRules(
        wakeMin = t("06:30"),
        sleepMin = t("23:00"),
        morningRoutineMin = 45,
        meals = listOf(
            MealSlot("Breakfast", t("08:00"), 20),
            MealSlot("Lunch", t("13:00"), 40),
            MealSlot("Dinner", t("20:00"), 40),
        ),
    )

    private fun free(plan: DayPlan) = plan.freeBlocks.map { TimeUtil.format(it.startMin) to TimeUtil.format(it.endMin) }

    @Test
    fun emptyDay_hasFreeTimeBetweenRoutineAndMeals() {
        val plan = DayPlanner.plan(rules, emptyList())
        assertEquals(
            listOf("07:15" to "08:00", "08:20" to "13:00", "13:40" to "20:00", "20:40" to "23:00"),
            free(plan),
        )
        assertEquals(45 + 280 + 380 + 140, plan.freeMinutes)
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun classSplitsFreeTime() {
        val plan = DayPlanner.plan(rules, listOf(FixedEntry("Maths", t("09:00"), t("11:00"))))
        assertEquals(
            listOf("07:15" to "08:00", "08:20" to "09:00", "11:00" to "13:00", "13:40" to "20:00", "20:40" to "23:00"),
            free(plan),
        )
    }

    @Test
    fun classOverlappingLunch_isReportedAndNotDoubleCounted() {
        val plan = DayPlanner.plan(rules, listOf(FixedEntry("Lab", t("12:30"), t("14:00"))))
        assertEquals(listOf(Conflict("Lab", "Lunch")), plan.conflicts)
        // Lab 12:30-14:00 swallows lunch 13:00-13:40, so the free gap runs 08:20-12:30 then 14:00-20:00
        assertEquals(
            listOf("07:15" to "08:00", "08:20" to "12:30", "14:00" to "20:00", "20:40" to "23:00"),
            free(plan),
        )
    }

    @Test
    fun overlappingClasses_areMergedForFreeTime() {
        val plan = DayPlanner.plan(
            rules,
            listOf(FixedEntry("A", t("10:00"), t("11:30")), FixedEntry("B", t("11:00"), t("12:00"))),
        )
        assertEquals(1, plan.conflicts.size)
        assertEquals("08:20" to "10:00", free(plan)[1])
        assertEquals("12:00" to "13:00", free(plan)[2])
    }

    @Test
    fun gapsShorterThanMinimumAreDropped() {
        // Class ends 10 minutes before lunch -> that 10 minute gap is not "free time"
        val plan = DayPlanner.plan(rules, listOf(FixedEntry("Class", t("09:00"), t("12:50"))))
        assertTrue(free(plan).none { it.first == "12:50" })
        assertEquals(listOf("07:15" to "08:00", "08:20" to "09:00", "13:40" to "20:00", "20:40" to "23:00"), free(plan))
    }

    @Test
    fun entriesOutsideAwakeWindowAreIgnored() {
        val plan = DayPlanner.plan(rules, listOf(FixedEntry("Night owl", t("23:30"), t("23:59")), FixedEntry("Early", t("05:00"), t("06:00"))))
        assertTrue(plan.conflicts.isEmpty())
        assertEquals(45 + 280 + 380 + 140, plan.freeMinutes)
    }

    @Test
    fun entryPartlyOutsideWindowIsClipped() {
        val plan = DayPlanner.plan(rules, listOf(FixedEntry("Late class", t("22:00"), t("23:45"))))
        assertEquals("20:40" to "22:00", free(plan).last())
    }

    @Test
    fun sleepAfterMidnightExtendsTheWindow() {
        val late = rules.copy(wakeMin = t("07:00"), sleepMin = t("00:30"), morningRoutineMin = 0, meals = emptyList())
        val plan = DayPlanner.plan(late, emptyList())
        assertEquals(7 * 60, plan.windowStart)
        assertEquals(24 * 60 + 30, plan.windowEnd)
        assertEquals(17 * 60 + 30, plan.freeMinutes)
    }

    @Test
    fun midnightSnackBelongsToTheNightWindow() {
        val late = DayRules(t("07:00"), t("01:00"), 0, listOf(MealSlot("Snack", t("00:15"), 15)))
        val plan = DayPlanner.plan(late, emptyList())
        val snack = plan.blocks.first { it.kind == BlockKind.MEAL }
        assertEquals(24 * 60 + 15, snack.startMin)
    }

    @Test
    fun blocksAreSortedAndCoverTheWholeWindow() {
        val plan = DayPlanner.plan(rules, listOf(FixedEntry("Maths", t("09:00"), t("11:00"))))
        assertEquals(plan.blocks.sortedBy { it.startMin }, plan.blocks)
        val busy = plan.blocks.filter { it.kind != BlockKind.FREE }.sumOf { it.duration }
        // no gap in this scenario is smaller than the 15 minute minimum, so nothing is dropped
        assertEquals(plan.windowEnd - plan.windowStart, busy + plan.freeMinutes)
    }

    @Test
    fun timeParsing() {
        assertEquals(450, TimeUtil.parse("07:30"))
        assertEquals(0, TimeUtil.parse("00:00"))
        assertEquals(1439, TimeUtil.parse("23:59"))
        assertNull(TimeUtil.parse("24:00"))
        assertNull(TimeUtil.parse("7:30"))
        assertNull(TimeUtil.parse("07:60"))
        assertNull(TimeUtil.parse("abc"))
    }

    @Test
    fun timeFormatting() {
        assertEquals("07:05", TimeUtil.format(425))
        assertEquals("00:30", TimeUtil.format(24 * 60 + 30))
        assertEquals("45 min", TimeUtil.formatDuration(45))
        assertEquals("3 h", TimeUtil.formatDuration(180))
        assertEquals("2 h 15 min", TimeUtil.formatDuration(135))
    }
}
