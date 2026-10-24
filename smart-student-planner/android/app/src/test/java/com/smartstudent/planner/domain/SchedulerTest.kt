package com.smartstudent.planner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Random

class SchedulerTest {

    private fun t(hm: String) = TimeUtil.parse(hm)!!
    private fun hm(min: Int) = TimeUtil.format(min)

    private fun free(start: String, end: String) =
        TimeBlock(t(start), t(end), "Free time", BlockKind.FREE)

    private fun task(
        title: String, minutes: Int, tier: Int = 1, priority: Priority = Priority.MEDIUM,
        divisible: Boolean = true, kind: WorkKind = WorkKind.TASK, dueDay: LocalDate? = null,
    ) = WorkItem(kind, title, title, minutes, priority, tier, dueDay, divisible)

    private fun spans(plan: SchedulePlan) = plan.sessions.map { Triple(it.item.title, hm(it.startMin), hm(it.endMin)) }

    // ------------------------------------------------------------ Scheduler

    @Test
    fun placesSessionsEarliestFirstWithBreaks() {
        val plan = Scheduler.schedule(
            listOf(free("09:00", "12:00")),
            listOf(task("A", 60, priority = Priority.HIGH), task("B", 45)),
        )
        assertEquals(listOf(Triple("A", "09:00", "10:00"), Triple("B", "10:10", "10:55")), spans(plan))
        assertEquals(listOf("11:05" to "12:00"), plan.leftoverFree.map { hm(it.startMin) to hm(it.endMin) })
        assertTrue(plan.unscheduled.isEmpty())
    }

    @Test
    fun higherPriorityGoesFirstWithinATier() {
        val plan = Scheduler.schedule(
            listOf(free("09:00", "12:00")),
            listOf(task("Low", 30, priority = Priority.LOW), task("High", 30, priority = Priority.HIGH)),
        )
        assertEquals(listOf("High", "Low"), plan.sessions.map { it.item.title })
    }

    @Test
    fun urgentWorkBeatsRelaxedWorkWhenTimeIsShort() {
        val plan = Scheduler.schedule(
            listOf(free("09:00", "10:00")),
            listOf(task("Later", 60, tier = 4, priority = Priority.HIGH), task("Overdue", 60, tier = 0, priority = Priority.LOW)),
        )
        assertEquals(listOf("Overdue"), plan.sessions.map { it.item.title })
        assertEquals(listOf("Later"), plan.unscheduled.map { it.item.title })
        assertEquals(UnscheduledReason.NO_ROOM, plan.unscheduled[0].reason)
    }

    @Test
    fun longTasksAreSplitIntoSessions() {
        val plan = Scheduler.schedule(listOf(free("09:00", "13:00")), listOf(task("Project", 150)))
        assertEquals(listOf(Triple("Project", "09:00", "10:30"), Triple("Project", "10:40", "11:40")), spans(plan))
    }

    @Test
    fun taskUsesSeveralGapsWhenNoneIsBigEnough() {
        val plan = Scheduler.schedule(
            listOf(free("09:00", "09:40"), free("11:00", "11:50")),
            listOf(task("Reading", 80)),
        )
        // first gap 40 min, second 50 min: 40 + 40 fit the 80 minutes
        assertEquals(80, plan.plannedMinutes)
        assertTrue(plan.unscheduled.isEmpty())
    }

    @Test
    fun habitIsAllOrNothing() {
        val habit = task("Aptitude", 120, divisible = false, kind = WorkKind.HABIT)
        val plan = Scheduler.schedule(listOf(free("09:00", "10:30")), listOf(habit))
        assertTrue(plan.sessions.isEmpty())
        assertEquals(120, plan.unscheduled.single().minutes)
    }

    @Test
    fun earliestStartTrimsThePast() {
        val plan = Scheduler.schedule(
            listOf(free("09:00", "12:00")),
            listOf(task("A", 30)),
            earliestStart = t("10:30"),
        )
        assertEquals(listOf(Triple("A", "10:30", "11:00")), spans(plan))
    }

    @Test
    fun gapsTooSmallAfterTrimmingAreIgnored() {
        val plan = Scheduler.schedule(
            listOf(free("09:00", "10:00")),
            listOf(task("A", 30)),
            earliestStart = t("09:50"),
        )
        assertTrue(plan.sessions.isEmpty())
        assertEquals(30, plan.unscheduled.single().minutes)
    }

    @Test
    fun nonUrgentWorkIsCapped() {
        val items = (1..10).map { task("Filler $it", 60, tier = 5) }
        val plan = Scheduler.schedule(listOf(free("06:00", "23:00")), items)
        assertEquals(480, plan.plannedMinutes)
        assertEquals(2, plan.unscheduled.count { it.reason == UnscheduledReason.DAILY_LIMIT })
    }

    @Test
    fun urgentWorkIgnoresTheCap() {
        val items = (1..10).map { task("Urgent $it", 60, tier = 1) }
        val plan = Scheduler.schedule(listOf(free("00:00", "23:59")), items)
        assertEquals(600, plan.plannedMinutes)
    }

    @Test
    fun randomScenarios_neverOverlap_andNeverLoseMinutes() {
        val rnd = Random(42)
        repeat(300) {
            val freeBlocks = mutableListOf<TimeBlock>()
            var cursor = 360
            repeat(rnd.nextInt(5) + 1) {
                cursor += rnd.nextInt(120)
                val len = 15 + rnd.nextInt(180)
                freeBlocks += TimeBlock(cursor, cursor + len, "Free time", BlockKind.FREE)
                cursor += len
            }
            val items = (0 until rnd.nextInt(8)).map { i ->
                WorkItem(
                    kind = if (rnd.nextBoolean()) WorkKind.TASK else WorkKind.HABIT,
                    refId = "id$i", title = "item$i",
                    minutes = 5 + rnd.nextInt(200),
                    priority = Priority.values()[rnd.nextInt(3)],
                    tier = rnd.nextInt(6), dueDay = null,
                    divisible = rnd.nextBoolean(),
                )
            }
            val earliest = if (rnd.nextBoolean()) 360 + rnd.nextInt(600) else null
            val plan = Scheduler.schedule(freeBlocks, items, earliest)

            // 1. every session lies inside a free block (and after the earliest start)
            for (s in plan.sessions) {
                assertTrue(freeBlocks.any { s.startMin >= it.startMin && s.endMin <= it.endMin })
                if (earliest != null) assertTrue(s.startMin >= earliest)
                assertTrue(s.duration > 0)
            }
            // 2. sessions never overlap each other, and keep a break between them
            val sorted = plan.sessions.sortedBy { it.startMin }
            for (i in 0 until sorted.size - 1) {
                assertTrue(sorted[i].endMin <= sorted[i + 1].startMin)
            }
            // 3. leftover free time never overlaps a session
            for (f in plan.leftoverFree) {
                assertFalse(plan.sessions.any { it.startMin < f.endMin && f.startMin < it.endMin })
            }
            // 4. minutes are conserved: scheduled + unscheduled == requested
            for (item in items) {
                val placed = plan.sessions.filter { it.item.refId == item.refId }.sumOf { it.duration }
                val lost = plan.unscheduled.filter { it.item.refId == item.refId }.sumOf { it.minutes }
                assertEquals(item.title, item.minutes, placed + lost)
            }
            // 5. habits are never split
            for (item in items.filter { !it.divisible }) {
                assertTrue(plan.sessions.count { it.item.refId == item.refId } <= 1)
            }
        }
    }

    // ---------------------------------------------------------- HabitRules

    private fun habit(kind: HabitKind, days: Set<Int> = emptySet(), perWeek: Int = 0) =
        HabitInfo("h1", "Habit", 30, "Medium", kind, days, perWeek)

    // 2026-10-19 is a Monday
    private val mon = LocalDate.of(2026, 10, 19)
    private fun day(offset: Int) = mon.plusDays(offset.toLong())

    @Test
    fun weeklyTargetsAreSpreadEvenly() {
        assertEquals(listOf(0), HabitRules.weeklyTargets(1))
        assertEquals(listOf(0, 3), HabitRules.weeklyTargets(2))
        assertEquals(listOf(0, 2, 4), HabitRules.weeklyTargets(3))
        assertEquals((0..6).toList(), HabitRules.weeklyTargets(7))
    }

    @Test
    fun dailyHabitIsDueUntilDone() {
        val h = habit(HabitKind.DAILY)
        assertTrue(HabitRules.isDue(h, mon, emptySet()))
        assertFalse(HabitRules.isDue(h, mon, setOf(mon)))
        assertTrue(HabitRules.isDue(h, day(1), setOf(mon)))
    }

    @Test
    fun specificDaysHabit() {
        val h = habit(HabitKind.DAYS, days = setOf(1, 3, 5))
        assertTrue(HabitRules.isDue(h, day(0), emptySet()))
        assertFalse(HabitRules.isDue(h, day(1), emptySet()))
        assertTrue(HabitRules.isDue(h, day(2), emptySet()))
        assertFalse(HabitRules.isDue(h, day(5), emptySet()))
    }

    @Test
    fun twiceAWeekHabitFollowsTheEvenScheduleAndCatchesUp() {
        val h = habit(HabitKind.WEEKLY, perWeek = 2)
        // nothing done: due Monday (target) and every day after, until caught up
        assertTrue(HabitRules.isDue(h, day(0), emptySet()))
        assertTrue(HabitRules.isDue(h, day(1), emptySet()))
        // done Monday: Tue and Wed are free, Thursday is due again
        val doneMon = setOf(day(0))
        assertFalse(HabitRules.isDue(h, day(1), doneMon))
        assertFalse(HabitRules.isDue(h, day(2), doneMon))
        assertTrue(HabitRules.isDue(h, day(3), doneMon))
        // both done: the rest of the week is free
        val both = setOf(day(0), day(3))
        assertFalse(HabitRules.isDue(h, day(4), both))
        assertFalse(HabitRules.isDue(h, day(6), both))
    }

    @Test
    fun weeklyHabitBecomesDueWhenItIsNowOrNever() {
        val h = habit(HabitKind.WEEKLY, perWeek = 2)
        // Saturday, one done: expected-by-today = 2 (targets Mon, Thu) so it is due anyway.
        assertTrue(HabitRules.isDue(h, day(5), setOf(day(0))))
        // Sunday, none done, 2 needed, 1 day left: must do today
        assertTrue(HabitRules.isDue(h, day(6), emptySet()))
    }

    @Test
    fun weeklyCountIgnoresLastWeek() {
        val h = habit(HabitKind.WEEKLY, perWeek = 1)
        val lastWeek = setOf(mon.minusDays(2), mon.minusDays(3))
        assertTrue(HabitRules.isDue(h, mon, lastWeek))
        assertEquals(0, HabitRules.doneThisWeek(lastWeek, mon))
    }

    @Test
    fun habitDescriptions() {
        assertEquals("Every day", HabitRules.describe(habit(HabitKind.DAILY)))
        assertEquals("Mon, Wed, Fri", HabitRules.describe(habit(HabitKind.DAYS, days = setOf(5, 1, 3))))
        assertEquals("2× per week", HabitRules.describe(habit(HabitKind.WEEKLY, perWeek = 2)))
    }

    // --------------------------------------------------------- WorkPlanner

    private fun taskInfo(est: Int, spent: Int = 0, due: LocalDate?, priority: String = "Medium") =
        TaskInfo("t", "Task", priority, est, spent, due)

    @Test
    fun tiersFollowTheDeadline() {
        assertEquals(0, WorkPlanner.tierFor(mon.minusDays(1), mon))
        assertEquals(1, WorkPlanner.tierFor(mon, mon))
        assertEquals(2, WorkPlanner.tierFor(mon.plusDays(2), mon))
        assertEquals(3, WorkPlanner.tierFor(mon.plusDays(7), mon))
        assertEquals(4, WorkPlanner.tierFor(mon.plusDays(8), mon))
        assertEquals(5, WorkPlanner.tierFor(null, mon))
    }

    @Test
    fun dueTodayTaskNeedsAllRemainingMinutes() {
        val item = WorkPlanner.taskItems(listOf(taskInfo(120, spent = 30, due = mon)), mon).single()
        assertEquals(90, item.minutes)
    }

    @Test
    fun bigTaskIsSpreadOverTheDaysLeft() {
        // 300 minutes, due in 4 days (5 days counting today) -> 60 a day
        val item = WorkPlanner.taskItems(listOf(taskInfo(300, due = mon.plusDays(4))), mon).single()
        assertEquals(60, item.minutes)
        assertEquals(3, item.tier)
    }

    @Test
    fun smallTaskIsDoneInOneGoEvenIfDueLater() {
        val item = WorkPlanner.taskItems(listOf(taskInfo(45, due = mon.plusDays(5))), mon).single()
        assertEquals(45, item.minutes)
    }

    @Test
    fun taskWithoutDeadlineIsFillerOfAtMostAnHour() {
        val item = WorkPlanner.taskItems(listOf(taskInfo(200, due = null)), mon).single()
        assertEquals(60, item.minutes)
        assertEquals(5, item.tier)
    }

    @Test
    fun finishedTasksProduceNoWork() {
        assertTrue(WorkPlanner.taskItems(listOf(taskInfo(60, spent = 60, due = mon)), mon).isEmpty())
    }

    // ----------------------------------------------------------- DayBuilder

    private val rules = DayRules(
        wakeMin = t("06:30"), sleepMin = t("23:00"), morningRoutineMin = 45,
        meals = listOf(
            MealSlot("Breakfast", t("08:00"), 20),
            MealSlot("Lunch", t("13:00"), 40),
            MealSlot("Dinner", t("20:00"), 40),
        ),
    )

    @Test
    fun fullDayPlanKeepsEverythingApartAndInsideFreeTime() {
        val view = DayBuilder.build(
            rules = rules,
            entries = listOf(FixedEntry("Maths", t("09:00"), t("11:00")), FixedEntry("Physics", t("14:00"), t("16:00"))),
            tasks = listOf(
                TaskInfo("t1", "Assignment", "High", 120, 0, mon),
                TaskInfo("t2", "Reading", "Low", 60, 0, null),
            ),
            habits = listOf(
                HabitInfo("h1", "Coding practice", 45, "Medium", HabitKind.DAILY, emptySet(), 0),
                HabitInfo("h2", "Aptitude", 60, "High", HabitKind.WEEKLY, emptySet(), 2),
            ),
            completions = emptyMap(),
            date = mon,
            nowMin = null,
        )
        val busy = view.plan.blocks.filter { it.kind != BlockKind.FREE }
        for (s in view.schedule.sessions) {
            assertFalse("${s.item.title} overlaps a fixed block", busy.any { it.startMin < s.endMin && s.startMin < it.endMin })
            assertTrue(s.startMin >= view.plan.windowStart && s.endMin <= view.plan.windowEnd)
        }
        // everything fits on an ordinary day
        assertTrue(view.schedule.unscheduled.isEmpty())
        assertEquals(setOf("Assignment", "Reading", "Coding practice", "Aptitude"), view.schedule.sessions.map { it.item.title }.toSet())
        // timeline is sorted
        assertEquals(view.timeline.sortedWith(compareBy({ it.startMin }, { it.endMin })), view.timeline)
    }

    @Test
    fun planningTodayOnlyUsesTheFuture() {
        val view = DayBuilder.build(
            rules, emptyList(),
            listOf(TaskInfo("t1", "Assignment", "High", 60, 0, mon)),
            emptyList(), emptyMap(), mon, nowMin = t("15:02"),
        )
        // 15:02 rounds up to 15:05
        assertEquals("15:05", hm(view.schedule.sessions.single().startMin))
        // finished morning items are not shown
        assertTrue(view.timeline.filterIsInstance<BusyItem>().all { it.endMin > t("15:02") })
    }

    @Test
    fun completedHabitIsNotPlannedAndShowsAsDone() {
        val habit = HabitInfo("h1", "Coding practice", 45, "Medium", HabitKind.DAILY, emptySet(), 0)
        val view = DayBuilder.build(
            rules, emptyList(), emptyList(), listOf(habit), mapOf("h1" to setOf(mon)), mon, null,
        )
        assertTrue(view.schedule.sessions.isEmpty())
        assertEquals(listOf("Coding practice"), view.doneHabits.map { it.title })
    }

    @Test
    fun overloadedDayReportsWhatDidNotFit() {
        val view = DayBuilder.build(
            rules,
            listOf(FixedEntry("Exam", t("08:30"), t("22:00"))),
            listOf(TaskInfo("t1", "Assignment", "High", 240, 0, mon)),
            emptyList(), emptyMap(), mon, null,
        )
        assertTrue(view.schedule.unscheduled.isNotEmpty())
        assertEquals(240, view.schedule.plannedMinutes + view.schedule.unscheduled.sumOf { it.minutes })
    }
}

class MultiDayPlanTest {
    private fun t(hm: String) = TimeUtil.parse(hm)!!
    private val mon = LocalDate.of(2026, 10, 19) // a Monday

    private val rules = DayRules(
        wakeMin = t("06:30"), sleepMin = t("23:00"), morningRoutineMin = 45,
        meals = listOf(
            MealSlot("Breakfast", t("08:00"), 20),
            MealSlot("Lunch", t("13:00"), 40),
            MealSlot("Dinner", t("20:00"), 40),
        ),
    )

    private fun plan(
        tasks: List<TaskInfo> = emptyList(),
        habits: List<HabitInfo> = emptyList(),
        completions: Map<String, Set<LocalDate>> = emptyMap(),
        entries: Map<Int, List<FixedEntry>> = emptyMap(),
        days: Int = 7,
        nowMin: Int? = null,
    ) = DayBuilder.buildRange(rules, entries, tasks, habits, completions, mon, days, nowMin)

    private fun titlesOn(view: DayView) = view.schedule.sessions.map { it.item.title }

    @Test
    fun taskDueTomorrowAppearsOnlyOnce() {
        // The reported bug: an assignment due tomorrow showed on both today and tomorrow.
        val views = plan(listOf(TaskInfo("a", "Assignment", "High", 60, 0, mon.plusDays(1))))
        val daysWithIt = views.indices.filter { "Assignment" in titlesOn(views[it]) }
        assertEquals(listOf(0), daysWithIt)
        assertEquals(60, views.sumOf { v -> v.schedule.sessions.filter { it.item.title == "Assignment" }.sumOf { it.duration } })
    }

    @Test
    fun ifTodayIsFullTheTaskMovesToTomorrowInsteadOfBeingLost() {
        val exam = mapOf(1 to listOf(FixedEntry("Exam", t("07:00"), t("23:00"))))
        val views = plan(listOf(TaskInfo("a", "Assignment", "High", 60, 0, mon.plusDays(1))), entries = exam)
        assertFalse("Assignment" in titlesOn(views[0]))
        assertTrue("Assignment" in titlesOn(views[1]))
        assertEquals(60, views[1].schedule.plannedMinutes)
    }

    @Test
    fun lateInTheEveningWorkMovesToTomorrow() {
        val views = plan(
            listOf(TaskInfo("a", "Assignment", "High", 90, 0, mon.plusDays(1))),
            nowMin = t("22:50"), days = 3, // under 15 minutes left before bedtime
        )
        assertTrue(views[0].schedule.sessions.isEmpty())
        assertTrue("Assignment" in titlesOn(views[1]))
    }

    @Test
    fun aShortWindowBeforeBedtimeCanStillBeUsedAndTheRestMovesOn() {
        val views = plan(
            listOf(TaskInfo("a", "Assignment", "High", 90, 0, mon.plusDays(1))),
            nowMin = t("22:30"), days = 3,
        )
        val today = views[0].schedule.plannedMinutes
        val tomorrow = views[1].schedule.plannedMinutes
        assertTrue(today in 1..30)
        assertEquals(90, today + tomorrow)
        assertEquals(0, views[2].schedule.plannedMinutes)
    }

    @Test
    fun bigTaskIsSpreadAndNeverOverbooked() {
        val views = plan(listOf(TaskInfo("a", "Thesis", "High", 300, 0, mon.plusDays(4))), days = 7)
        val perDay = views.map { v -> v.schedule.sessions.filter { it.item.title == "Thesis" }.sumOf { it.duration } }
        assertEquals(300, perDay.sum())
        assertEquals(listOf(60, 60, 60, 60, 60, 0, 0), perDay)
    }

    @Test
    fun twiceAWeekHabitIsPlannedOnMondayAndThursdayOnly() {
        val habit = HabitInfo("h", "Aptitude", 60, "High", HabitKind.WEEKLY, emptySet(), 2)
        val views = plan(habits = listOf(habit), days = 7)
        val days = views.indices.filter { "Aptitude" in titlesOn(views[it]) }
        assertEquals(listOf(0, 3), days)
    }

    @Test
    fun habitAlreadyDoneThisWeekIsNotPlannedAgain() {
        val habit = HabitInfo("h", "Aptitude", 60, "High", HabitKind.WEEKLY, emptySet(), 2)
        val views = plan(habits = listOf(habit), completions = mapOf("h" to setOf(mon)), days = 7)
        // done Monday -> only Thursday remains
        assertEquals(listOf(3), views.indices.filter { "Aptitude" in titlesOn(views[it]) })
    }

    @Test
    fun dailyHabitIsPlannedEveryDay() {
        val habit = HabitInfo("h", "Coding", 45, "Medium", HabitKind.DAILY, emptySet(), 0)
        val views = plan(habits = listOf(habit), days = 7)
        assertTrue(views.all { "Coding" in titlesOn(it) })
    }

    @Test
    fun randomScenarios_noTaskIsPlannedForMoreThanItsRemainingMinutes() {
        val rnd = Random(7)
        repeat(100) {
            val tasks = (0 until rnd.nextInt(6) + 1).map { i ->
                val est = 20 + rnd.nextInt(400)
                TaskInfo(
                    "t$i", "task$i", listOf("Low", "Medium", "High")[rnd.nextInt(3)],
                    est, rnd.nextInt(est / 2 + 1),
                    if (rnd.nextInt(4) == 0) null else mon.plusDays(rnd.nextInt(10).toLong() - 2),
                )
            }
            val views = plan(tasks, days = 7, nowMin = if (rnd.nextBoolean()) 600 else null)
            for (task in tasks) {
                val planned = views.sumOf { v -> v.schedule.sessions.filter { it.item.refId == task.id }.sumOf { it.duration } }
                assertTrue("${task.title} planned $planned of ${task.estimatedMinutes - task.spentMinutes}",
                    planned <= task.estimatedMinutes - task.spentMinutes)
            }
        }
    }
}
