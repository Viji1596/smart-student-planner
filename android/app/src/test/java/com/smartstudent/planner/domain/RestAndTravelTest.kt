package com.smartstudent.planner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Random

class RestAndTravelTest {

    private fun t(hm: String) = TimeUtil.parse(hm)!!
    private fun hm(min: Int) = TimeUtil.format(min)
    private val mon = LocalDate.of(2026, 10, 19)

    private val base = DayRules(
        wakeMin = t("06:30"), sleepMin = t("23:00"), morningRoutineMin = 45,
        meals = listOf(
            MealSlot("Breakfast", t("08:00"), 20),
            MealSlot("Lunch", t("13:00"), 40),
            MealSlot("Dinner", t("20:00"), 40),
        ),
    )

    private fun spans(plan: DayPlan, kind: BlockKind) =
        plan.blocks.filter { it.kind == kind }.map { Triple(it.label, hm(it.startMin), hm(it.endMin)) }

    private val classes = listOf(FixedEntry("Maths", t("09:00"), t("11:00")), FixedEntry("Physics", t("14:00"), t("16:00")))

    // ------------------------------------------------------------- travel

    @Test
    fun hostelStudent_walksThereAndBack() {
        val rules = base.copy(commute = Commute(toMin = 30, fromMin = 30, arriveEarlyMin = 10))
        val plan = DayPlanner.plan(rules, classes)
        assertEquals(
            listOf(Triple("Travel to college", "08:20", "08:50"), Triple("Travel home", "16:00", "16:30")),
            spans(plan, BlockKind.TRAVEL),
        )
    }

    @Test
    fun dayScholar_wayThereAndBackCanDiffer() {
        val rules = base.copy(commute = Commute(toMin = 55, fromMin = 70, arriveEarlyMin = 0))
        val plan = DayPlanner.plan(rules, classes)
        assertEquals(
            listOf(Triple("Travel to college", "08:05", "09:00"), Triple("Travel home", "16:00", "17:10")),
            spans(plan, BlockKind.TRAVEL),
        )
    }

    @Test
    fun noTravelOnADayWithoutClasses() {
        val rules = base.copy(commute = Commute(30, 30, 10))
        val plan = DayPlanner.plan(rules, emptyList())
        assertTrue(spans(plan, BlockKind.TRAVEL).isEmpty())
    }

    @Test
    fun travelIsOffByDefault() {
        assertTrue(spans(DayPlanner.plan(base, classes), BlockKind.TRAVEL).isEmpty())
    }

    @Test
    fun onlyOneDirectionCanBeSet() {
        val plan = DayPlanner.plan(base.copy(commute = Commute(toMin = 0, fromMin = 40)), classes)
        assertEquals(listOf(Triple("Travel home", "16:00", "16:40")), spans(plan, BlockKind.TRAVEL))
    }

    @Test
    fun travelReducesFreeTimeAndNothingIsPlannedOnIt() {
        val plain = DayPlanner.plan(base, classes)
        val withTravel = DayPlanner.plan(base.copy(commute = Commute(30, 30, 10)), classes)
        assertTrue(withTravel.freeMinutes < plain.freeMinutes)

        val view = DayBuilder.build(
            base.copy(commute = Commute(30, 30, 10)), classes,
            listOf(TaskInfo("t1", "Assignment", "High", 300, 0, mon)), emptyList(), emptyMap(), mon, null,
        )
        val travel = view.plan.blocks.filter { it.kind == BlockKind.TRAVEL }
        for (s in view.schedule.sessions) {
            assertFalse(travel.any { it.startMin < s.endMin && s.startMin < it.endMin })
        }
    }

    @Test
    fun leavingBeforeTheMorningRoutineIsEndsIsFlagged() {
        // Class at 07:30, 30 minute walk: you'd have to leave at 07:00, during the morning routine.
        val plan = DayPlanner.plan(
            base.copy(commute = Commute(30, 0, 0)),
            listOf(FixedEntry("Early lab", t("07:30"), t("09:00"))),
        )
        assertTrue(plan.conflicts.any { it.first == "Morning routine" || it.second == "Morning routine" })
    }

    @Test
    fun travelHomeAfterBedtimeIsCutOff() {
        val plan = DayPlanner.plan(
            base.copy(commute = Commute(0, 60, 0)),
            listOf(FixedEntry("Evening class", t("21:00"), t("22:30"))),
        )
        val home = plan.blocks.single { it.kind == BlockKind.TRAVEL }
        assertEquals(t("23:00"), home.endMin)
    }

    // --------------------------------------------------------------- rest

    @Test
    fun dailyRestIsProtected() {
        val rules = base.copy(rest = RestSlot(t("16:00"), 30))
        val plan = DayPlanner.plan(rules, emptyList())
        assertEquals(listOf(Triple("Rest", "16:00", "16:30")), spans(plan, BlockKind.REST))
        assertFalse(plan.freeBlocks.any { it.startMin < t("16:30") && t("16:00") < it.endMin })
    }

    @Test
    fun restOfZeroMinutesMeansNoRest() {
        val plan = DayPlanner.plan(base.copy(rest = RestSlot(t("16:00"), 0)), emptyList())
        assertTrue(spans(plan, BlockKind.REST).isEmpty())
    }

    @Test
    fun nothingIsScheduledDuringTheDailyRest() {
        val rules = base.copy(rest = RestSlot(t("15:00"), 45))
        val view = DayBuilder.build(
            rules, emptyList(),
            listOf(TaskInfo("t1", "Project", "High", 480, 0, mon)), emptyList(), emptyMap(), mon, null,
        )
        val rest = view.plan.blocks.single { it.kind == BlockKind.REST }
        assertFalse(view.schedule.sessions.any { it.startMin < rest.endMin && rest.startMin < it.endMin })
    }

    @Test
    fun restThatOverlapsALectureIsReported() {
        val plan = DayPlanner.plan(base.copy(rest = RestSlot(t("15:00"), 30)), classes)
        assertTrue(plan.conflicts.any { "Rest" in listOf(it.first, it.second) && "Physics" in listOf(it.first, it.second) })
    }

    // ------------------------------------------------------------- breaks

    private fun task(title: String, minutes: Int) =
        WorkItem(WorkKind.TASK, title, title, minutes, Priority.MEDIUM, 1, null, true)

    private fun free(a: String, b: String) = TimeBlock(t(a), t(b), "Free time", BlockKind.FREE)

    @Test
    fun longBreakAfterTwoHoursOfWork() {
        val plan = Scheduler.schedule(
            listOf(free("09:00", "14:00")),
            listOf(task("A", 60), task("B", 60), task("C", 60)),
            breakMin = 10, longBreakAfterMin = 120, longBreakMin = 30,
        )
        val spans = plan.sessions.map { hm(it.startMin) to hm(it.endMin) }
        // A 09:00-10:00, short break, B 10:10-11:10 (2 h of work), long break of 30, C 11:40-12:40
        assertEquals(listOf("09:00" to "10:00", "10:10" to "11:10", "11:40" to "12:40"), spans)
        assertEquals(
            listOf("10:00" to "10:10", "11:10" to "11:40"),
            plan.breaks.map { hm(it.startMin) to hm(it.endMin) },
        )
    }

    @Test
    fun longBreaksCanBeSwitchedOff() {
        val plan = Scheduler.schedule(
            listOf(free("09:00", "14:00")),
            listOf(task("A", 60), task("B", 60), task("C", 60)),
            breakMin = 10, longBreakAfterMin = 0,
        )
        assertEquals(listOf("09:00", "10:10", "11:20"), plan.sessions.map { hm(it.startMin) })
    }

    @Test
    fun breaksCanBeSwitchedOffCompletely() {
        val plan = Scheduler.schedule(
            listOf(free("09:00", "14:00")),
            listOf(task("A", 45), task("B", 45)),
            breakMin = 0, longBreakAfterMin = 0,
        )
        assertEquals(listOf("09:00", "09:45"), plan.sessions.map { hm(it.startMin) })
        assertTrue(plan.breaks.isEmpty())
    }

    @Test
    fun breakIsOnlyShownWhenAnotherSessionFollows() {
        val plan = Scheduler.schedule(listOf(free("09:00", "12:00")), listOf(task("A", 60)))
        assertTrue(plan.breaks.isEmpty())
    }

    @Test
    fun aNewSlotStartsFresh_soNoLongBreakIsNeededAfterAClass() {
        // One hour of work before the class and 90 minutes after: the class is rest enough.
        val plan = Scheduler.schedule(
            listOf(free("08:00", "09:00"), free("11:00", "13:00")),
            listOf(task("A", 55), task("B", 90)),
            breakMin = 10, longBreakAfterMin = 120, longBreakMin = 30,
        )
        assertEquals(listOf("08:00", "11:00"), plan.sessions.map { hm(it.startMin) })
    }

    @Test
    fun randomScenarios_breaksNeverOverlapSessions() {
        val rnd = Random(11)
        repeat(300) {
            val blocks = mutableListOf<TimeBlock>()
            var cursor = 360
            repeat(rnd.nextInt(4) + 1) {
                cursor += rnd.nextInt(90)
                val len = 20 + rnd.nextInt(300)
                blocks += TimeBlock(cursor, cursor + len, "Free time", BlockKind.FREE)
                cursor += len
            }
            val items = (0 until rnd.nextInt(7)).map { i ->
                WorkItem(
                    WorkKind.TASK, "id$i", "t$i", 10 + rnd.nextInt(200), Priority.MEDIUM,
                    rnd.nextInt(3), null, true,
                )
            }
            val plan = Scheduler.schedule(
                blocks, items,
                breakMin = rnd.nextInt(30), longBreakAfterMin = rnd.nextInt(200), longBreakMin = rnd.nextInt(60),
            )
            for (b in plan.breaks) {
                assertTrue(b.endMin > b.startMin)
                assertFalse(plan.sessions.any { it.startMin < b.endMin && b.startMin < it.endMin })
            }
            val sorted = plan.sessions.sortedBy { it.startMin }
            for (i in 0 until sorted.size - 1) assertTrue(sorted[i].endMin <= sorted[i + 1].startMin)
            for (s in plan.sessions) assertTrue(blocks.any { s.startMin >= it.startMin && s.endMin <= it.endMin })
            for (item in items) {
                val placed = plan.sessions.filter { it.item.refId == item.refId }.sumOf { it.duration }
                val lost = plan.unscheduled.filter { it.item.refId == item.refId }.sumOf { it.minutes }
                assertEquals(item.minutes, placed + lost)
            }
        }
    }

    @Test
    fun timelineIncludesBreaksAndStaysSorted() {
        val view = DayBuilder.build(
            base, emptyList(),
            listOf(TaskInfo("a", "A", "High", 60, 0, mon), TaskInfo("b", "B", "High", 60, 0, mon)),
            emptyList(), emptyMap(), mon, null,
        )
        assertTrue(view.timeline.any { it is BreakItem })
        assertEquals(view.timeline.sortedWith(compareBy({ it.startMin }, { it.endMin })), view.timeline)
    }

    // ---------------------------------------------- travel around meals, and travel is not a slot

    private fun lunchRules(commuteTo: Int = 40, commuteFrom: Int = 40, early: Int = 10) = DayRules(
        wakeMin = t("06:30"), sleepMin = t("23:00"), morningRoutineMin = 45,
        meals = listOf(MealSlot("Breakfast", t("07:30"), 30), MealSlot("Lunch", t("13:00"), 40), MealSlot("Dinner", t("19:30"), 45)),
        commute = Commute(commuteTo, commuteFrom, early),
    )

    @Test
    fun classesAfterLunch_theTripToCollegeMovesBeforeLunch() {
        // class at 14:00: the trip would be 13:10-13:50 (through lunch at 13:00-13:40); it moves to end when lunch starts
        val plan = DayPlanner.plan(lunchRules(), listOf(FixedEntry("Physics", t("14:00"), t("15:00"))))
        val trip = plan.blocks.single { it.label == "Travel to college" }
        assertEquals("12:20", hm(trip.startMin))
        assertEquals("13:00", hm(trip.endMin))
        assertTrue("no overlaps: ${plan.conflicts}", plan.conflicts.isEmpty())
    }

    @Test
    fun classesBeforeLunch_theTripStaysRightBeforeTheClass() {
        val plan = DayPlanner.plan(lunchRules(), listOf(FixedEntry("Physics", t("10:00"), t("11:00"))))
        val trip = plan.blocks.single { it.label == "Travel to college" }
        assertEquals("09:10", hm(trip.startMin))
        assertEquals("09:50", hm(trip.endMin))
    }

    @Test
    fun aTripThatOnlyTouchesTheMealIsNotMoved() {
        // class 14:20, 40 minutes of travel: 13:40-14:20 starts exactly when lunch ends, so it is left alone
        val plan = DayPlanner.plan(lunchRules(early = 0), listOf(FixedEntry("Physics", t("14:20"), t("15:20"))))
        val trip = plan.blocks.single { it.label == "Travel to college" }
        assertEquals("13:40", hm(trip.startMin))
        assertEquals("14:20", hm(trip.endMin))
    }

    @Test
    fun theTripHomeStartsAfterAMealItWouldRunInto() {
        // class ends 19:00, the trip home would be 19:00-19:40, dinner is 19:30-20:15: the trip starts after dinner
        val plan = DayPlanner.plan(lunchRules(), listOf(FixedEntry("Lab", t("17:00"), t("19:00"))))
        val trip = plan.blocks.single { it.label == "Travel home" }
        assertEquals("20:15", hm(trip.startMin))
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun theTripHomeRightAfterClassIsUnchangedWhenNoMealIsInTheWay() {
        val plan = DayPlanner.plan(lunchRules(), listOf(FixedEntry("Physics", t("10:00"), t("11:00"))))
        assertEquals("11:00", hm(plan.blocks.single { it.label == "Travel home" }.startMin))
    }

    @Test
    fun aMovedTripStillBlocksTheTime_noWorkIsPlannedInIt() {
        val plan = DayPlanner.plan(lunchRules(), listOf(FixedEntry("Physics", t("14:00"), t("15:00"))))
        assertTrue(plan.freeBlocks.none { it.startMin < t("13:00") && it.endMin > t("12:20") })
    }

    @Test
    fun travelIsBlockedButNotShownAsASlotOnTheTimeline() {
        val rules = lunchRules()
        val entries = listOf(FixedEntry("Physics", t("14:00"), t("15:00")))
        val view = DayBuilder.build(rules, entries, emptyList(), emptyList(), emptyMap(), java.time.LocalDate.of(2026, 10, 26), null)
        assertTrue(view.plan.blocks.any { it.kind == BlockKind.TRAVEL })
        assertTrue(view.timeline.none { it is BusyItem && it.block.kind == BlockKind.TRAVEL })
        assertTrue(view.timeline.any { it is BusyItem && it.block.kind == BlockKind.CLASS })
    }

    @Test
    fun aRestThatWouldSitOnADinnerStartsWhenDinnerEnds() {
        val rules = DayRules(
            wakeMin = t("07:00"), sleepMin = t("23:00"), morningRoutineMin = 30,
            meals = listOf(MealSlot("Dinner", t("18:30"), 60)), rest = RestSlot(t("18:30"), 30),
        )
        val plan = DayPlanner.plan(rules, emptyList())
        assertEquals("19:30", hm(plan.blocks.single { it.kind == BlockKind.REST }.startMin))
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun aRestThatDoesNotTouchAMealStaysWhereItIs() {
        val rules = DayRules(
            wakeMin = t("07:00"), sleepMin = t("23:00"), morningRoutineMin = 30,
            meals = listOf(MealSlot("Dinner", t("19:30"), 60)), rest = RestSlot(t("16:00"), 30),
        )
        assertEquals("16:00", hm(DayPlanner.plan(rules, emptyList()).blocks.single { it.kind == BlockKind.REST }.startMin))
    }

    @Test
    fun aTripIsNotMovedIntoTheMorningRoutineToAvoidBreakfast_theOverlapIsReportedInstead() {
        // wake 07:00 + 60 min routine = 08:00; class 09:00; trip 08:05-09:00 overlaps a 08:30 breakfast.
        // Moving it before breakfast would put it at 07:35, inside the routine: so it stays, and the clash is shown.
        val rules = DayRules(
            wakeMin = t("07:00"), sleepMin = t("23:00"), morningRoutineMin = 60,
            meals = listOf(MealSlot("Breakfast", t("08:30"), 30)), commute = Commute(55, 0, 0),
        )
        val plan = DayPlanner.plan(rules, listOf(FixedEntry("Physics", t("09:00"), t("10:00"))))
        assertEquals("08:05", hm(plan.blocks.single { it.label == "Travel to college" }.startMin))
        assertTrue(plan.conflicts.any { "Breakfast" in listOf(it.first, it.second) })
    }
}
