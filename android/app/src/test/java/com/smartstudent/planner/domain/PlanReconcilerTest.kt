package com.smartstudent.planner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Random

class PlanReconcilerTest {

    private fun t(hm: String) = TimeUtil.parse(hm)!!
    private fun hm(min: Int) = TimeUtil.format(min)
    private val today = LocalDate.of(2026, 10, 19) // a Monday

    private val rules = DayRules(
        wakeMin = t("06:30"), sleepMin = t("23:00"), morningRoutineMin = 45,
        meals = listOf(
            MealSlot("Breakfast", t("08:00"), 20),
            MealSlot("Lunch", t("13:00"), 40),
            MealSlot("Dinner", t("20:00"), 40),
        ),
    )

    private fun task(id: String, title: String, est: Int, spent: Int = 0, due: LocalDate? = today, prio: String = "High") =
        TaskInfo(id, title, prio, est, spent, due)

    private fun planned(
        kind: WorkKind, ref: String, title: String, start: String, end: String, status: SessionStatus = SessionStatus.PLANNED,
    ) = PlannedSession(
        PlanReconciler.sessionId(today, kind, ref, t(start)), today, t(start), t(end), kind, ref, title, Priority.HIGH, status,
    )

    private fun replan(
        existing: List<PlannedSession> = emptyList(),
        tasks: List<TaskInfo> = emptyList(),
        habits: List<HabitInfo> = emptyList(),
        completions: Map<String, Set<LocalDate>> = emptyMap(),
        entries: List<FixedEntry> = emptyList(),
        now: String,
        skip: Set<String> = emptySet(),
    ) = PlanReconciler.replanToday(rules, entries, tasks, habits, completions, existing, today, t(now), skip)

    // ----------------------------------------------------------- markMissed

    @Test
    fun onlyEndedSessionsBecomeMissed_afterTheGracePeriod() {
        val sessions = listOf(
            planned(WorkKind.TASK, "a", "A", "09:00", "10:00"),
            planned(WorkKind.TASK, "b", "B", "10:10", "11:00"),
            planned(WorkKind.TASK, "c", "C", "12:00", "13:00"),
        )
        val result = PlanReconciler.markMissed(sessions, t("11:10")) // A ended 70 min ago, B 10 min ago, C not started
        assertEquals(listOf(SessionStatus.MISSED, SessionStatus.MISSED, SessionStatus.PLANNED), result.map { it.status })
        // within the grace period (4 minutes after the end) it is still PLANNED
        assertEquals(SessionStatus.PLANNED, PlanReconciler.markMissed(sessions.take(1), t("10:04"))[0].status)
        assertEquals(SessionStatus.MISSED, PlanReconciler.markMissed(sessions.take(1), t("10:05"))[0].status)
    }

    @Test
    fun aSessionThatJustEndedIsNotLostDuringTheGracePeriod() {
        val sessions = listOf(
            planned(WorkKind.TASK, "a", "Tiny task", "17:35", "17:38"),
            planned(WorkKind.TASK, "b", "Later", "19:00", "19:30"),
            planned(WorkKind.TASK, "c", "Done", "17:00", "17:20", SessionStatus.DONE),
        )
        // 17:40: the tiny task ended 2 minutes ago, not missed yet, but it must still be visible
        assertEquals(listOf("Tiny task"), PlanReconciler.justEnded(sessions, t("17:40")).map { it.title })
        assertFalse(PlanReconciler.needsMissedHandling(sessions, t("17:40")))
        // 17:43: grace period over -> a re-plan is due
        assertTrue(PlanReconciler.needsMissedHandling(sessions, t("17:43")))
    }

    @Test
    fun aTinyThreeMinuteTaskIsPlannedAgainAfterItIsMissed() {
        val existing = listOf(planned(WorkKind.TASK, "tiny", "Tiny task", "17:35", "17:38"))
        val result = replan(existing, tasks = listOf(task("tiny", "Tiny task", 3)), now = "17:43")
        assertEquals(SessionStatus.MISSED, result.sessions.first().status)
        val again = result.sessions.single { it.status == SessionStatus.PLANNED }
        assertEquals(3, again.duration)
        assertTrue(again.startMin >= t("17:43"))
        assertEquals(again.startMin, result.newlyMissed.single().movedToMin)
    }

    @Test
    fun doneSessionsAreNeverMissed() {
        val done = planned(WorkKind.TASK, "a", "A", "09:00", "10:00", SessionStatus.DONE)
        assertEquals(SessionStatus.DONE, PlanReconciler.markMissed(listOf(done), t("20:00"))[0].status)
    }

    // --------------------------------------------------------------- replan

    @Test
    fun firstPlanOfTheDayPlansEverything() {
        val result = replan(tasks = listOf(task("a", "Assignment", 60)), now = "07:00")
        assertEquals(listOf("Assignment"), result.sessions.map { it.title })
        assertTrue(result.sessions.all { it.status == SessionStatus.PLANNED })
        assertTrue(result.newlyMissed.isEmpty())
    }

    @Test
    fun missedSessionIsRescheduledLaterToday() {
        val existing = listOf(planned(WorkKind.TASK, "a", "Assignment", "09:00", "10:00"))
        val result = replan(existing, tasks = listOf(task("a", "Assignment", 60)), now = "11:00")

        val missed = result.newlyMissed.single()
        assertEquals("Assignment", missed.session.title)
        assertEquals(t("11:00"), missed.movedToMin)
        assertEquals(
            listOf(SessionStatus.MISSED, SessionStatus.PLANNED),
            result.sessions.map { it.status },
        )
        // the new session starts after "now"
        assertTrue(result.sessions.last().startMin >= t("11:00"))
    }

    @Test
    fun whenTodayHasNoRoomLeftTheMissedWorkIsReportedAsNotMoved() {
        val existing = listOf(planned(WorkKind.TASK, "a", "Assignment", "19:00", "20:00"))
        val result = replan(existing, tasks = listOf(task("a", "Assignment", 60)), now = "22:50")
        assertNull(result.newlyMissed.single().movedToMin)
        assertTrue(result.sessions.none { it.status == SessionStatus.PLANNED })
    }

    @Test
    fun finishedSessionsStayAsHistoryAndAreNotPlannedAgain() {
        val existing = listOf(planned(WorkKind.TASK, "a", "Assignment", "09:00", "10:00", SessionStatus.DONE))
        // the task has 60 minutes spent already, 60 left; its other half is planned later
        val result = replan(existing, tasks = listOf(task("a", "Assignment", 120, spent = 60)), now = "10:30")
        assertEquals(SessionStatus.DONE, result.sessions.first().status)
        val fresh = result.sessions.filter { it.status == SessionStatus.PLANNED }
        assertEquals(60, fresh.sumOf { it.duration })
    }

    @Test
    fun aSessionUnderWayIsKeptAndItsMinutesAreNotPlannedTwice() {
        val existing = listOf(planned(WorkKind.TASK, "a", "Assignment", "10:00", "11:00"))
        val result = replan(existing, tasks = listOf(task("a", "Assignment", 60)), now = "10:30")
        // exactly one session for the assignment: the one under way
        assertEquals(1, result.sessions.count { it.refId == "a" })
        assertEquals(SessionStatus.PLANNED, result.sessions.single { it.refId == "a" }.status)
        assertTrue(result.newlyMissed.isEmpty())
    }

    @Test
    fun otherWorkStartsAfterTheSessionUnderWay() {
        val existing = listOf(planned(WorkKind.TASK, "a", "Assignment", "10:00", "11:00"))
        val result = replan(
            existing,
            tasks = listOf(task("a", "Assignment", 60), task("b", "Reading", 45)),
            now = "10:30",
        )
        val reading = result.sessions.single { it.refId == "b" }
        assertTrue("reading must not overlap the session under way", reading.startMin >= t("11:00"))
    }

    @Test
    fun skippingMovesASessionToLaterWithoutAMissedNotificationFlag() {
        val first = planned(WorkKind.TASK, "a", "Assignment", "09:00", "10:00")
        val second = planned(WorkKind.TASK, "b", "Reading", "10:10", "10:55")
        val result = replan(
            listOf(first, second),
            tasks = listOf(task("a", "Assignment", 60), task("b", "Reading", 45)),
            now = "08:50", skip = setOf(first.id),
        )
        assertEquals(SessionStatus.MISSED, result.sessions.first { it.id == first.id }.status)
        // the skipped work is planned again later today
        assertTrue(result.sessions.any { it.refId == "a" && it.status == SessionStatus.PLANNED })
        assertEquals(listOf(first.id), result.newlyMissed.map { it.session.id })
    }

    @Test
    fun missedHabitIsPlannedAgain_butADoneHabitIsNot() {
        val habit = HabitInfo("h", "Coding", 45, "Medium", HabitKind.DAILY, emptySet(), 0)
        val missed = listOf(planned(WorkKind.HABIT, "h", "Coding", "09:00", "09:45"))
        val again = replan(missed, habits = listOf(habit), now = "11:00")
        assertTrue(again.sessions.any { it.refId == "h" && it.status == SessionStatus.PLANNED })

        val done = listOf(planned(WorkKind.HABIT, "h", "Coding", "09:00", "09:45", SessionStatus.DONE))
        val notAgain = replan(done, habits = listOf(habit), completions = mapOf("h" to setOf(today)), now = "11:00")
        assertTrue(notAgain.sessions.none { it.status == SessionStatus.PLANNED })
    }

    @Test
    fun aHabitUnderWayIsNotPlannedASecondTime() {
        val habit = HabitInfo("h", "Coding", 60, "Medium", HabitKind.DAILY, emptySet(), 0)
        val existing = listOf(planned(WorkKind.HABIT, "h", "Coding", "10:00", "11:00"))
        val result = replan(existing, habits = listOf(habit), now = "10:20")
        assertEquals(1, result.sessions.count { it.refId == "h" })
    }

    @Test
    fun finishingTodaysShareOfABigTaskDoesNotMakeAnotherShareAppear() {
        // 300 minutes due in 4 days: today's share is 60. After doing that hour, no more is planned today.
        val big = TaskInfo("p", "Thesis", "High", 300, 60, today.plusDays(4)) // 60 already spent (today's hour)
        val done = listOf(planned(WorkKind.TASK, "p", "Thesis", "09:00", "10:00", SessionStatus.DONE))
        val result = replan(done, tasks = listOf(big), now = "10:30")
        assertTrue(result.sessions.none { it.status == SessionStatus.PLANNED })
    }

    @Test
    fun aFillerTaskDoneTodayIsNotRepeatedToday() {
        val filler = TaskInfo("f", "Reading", "Low", 400, 60, null)
        val done = listOf(planned(WorkKind.TASK, "f", "Reading", "09:00", "10:00", SessionStatus.DONE))
        val result = replan(done, tasks = listOf(filler), now = "10:30")
        assertTrue(result.sessions.none { it.status == SessionStatus.PLANNED })
    }

    @Test
    fun replanningTwiceGivesTheSamePlan() {
        val tasks = listOf(task("a", "Assignment", 90), task("b", "Reading", 45, due = null, prio = "Low"))
        val first = replan(tasks = tasks, now = "09:00")
        val second = replan(first.sessions, tasks = tasks, now = "09:00")
        assertEquals(first.sessions, second.sessions)
    }

    @Test
    fun plannedSessionsNeverOverlapClassesOrEachOther() {
        val classes = listOf(FixedEntry("Maths", t("09:00"), t("11:00")), FixedEntry("Physics", t("14:00"), t("16:00")))
        val result = replan(
            tasks = listOf(task("a", "Assignment", 150), task("b", "Reading", 60, due = null)),
            entries = classes, now = "07:00",
        )
        val sessions = result.sessions.sortedBy { it.startMin }
        for (s in sessions) {
            assertFalse(classes.any { it.startMin < s.endMin && s.startMin < it.endMin })
        }
        for (i in 0 until sessions.size - 1) assertTrue(sessions[i].endMin <= sessions[i + 1].startMin)
    }

    @Test
    fun randomScenarios_neverDoubleBookWork() {
        val rnd = Random(5)
        repeat(150) {
            val tasks = (0 until rnd.nextInt(5) + 1).map { i ->
                val est = 20 + rnd.nextInt(200)
                TaskInfo("t$i", "task$i", "High", est, 0, if (rnd.nextBoolean()) today else today.plusDays(rnd.nextInt(5).toLong()))
            }
            // first plan in the morning, then replan at a random later time after some sessions "happen"
            val first = replan(tasks = tasks, now = "07:00")
            val later = 420 + rnd.nextInt(600)
            val marked = first.sessions.map { s ->
                if (s.endMin <= later && rnd.nextBoolean()) s.copy(status = SessionStatus.DONE) else s
            }
            val spentByTask = marked.filter { it.status == SessionStatus.DONE }.groupBy { it.refId }
                .mapValues { e -> e.value.sumOf { it.duration } }
            val updatedTasks = tasks.map { it.copy(spentMinutes = minOf(it.estimatedMinutes, spentByTask[it.id] ?: 0)) }
            val second = PlanReconciler.replanToday(rules, emptyList(), updatedTasks, emptyList(), emptyMap(), marked, today, later)

            for (task in updatedTasks) {
                val remaining = task.estimatedMinutes - task.spentMinutes
                val planned = second.sessions.filter { it.refId == task.id && it.status == SessionStatus.PLANNED }.sumOf { it.duration }
                assertTrue("${task.title} planned $planned but only $remaining left", planned <= remaining)
            }
            val future = second.sessions.filter { it.status == SessionStatus.PLANNED }.sortedBy { it.startMin }
            for (i in 0 until future.size - 1) assertTrue(future[i].endMin <= future[i + 1].startMin)
        }
    }

    // ------------------------------------------------------------ DayLayout

    private fun freeBlock(a: String, b: String) = TimeBlock(t(a), t(b), "Free time", BlockKind.FREE)

    private fun plan(vararg free: TimeBlock) =
        DayPlan(t("06:30"), t("23:00"), free.toList(), emptyList())

    private fun session(a: String, b: String) = Session(
        t(a), t(b),
        WorkItem(WorkKind.TASK, "x", "X", t(b) - t(a), Priority.MEDIUM, 1, null, true),
    )

    @Test
    fun aShortGapBetweenSessionsIsABreak_aLongOneIsFreeTime() {
        val layout = DayLayout.build(
            plan(freeBlock("09:00", "16:00")),
            listOf(session("09:00", "10:00"), session("10:10", "11:00"), session("14:00", "15:00")),
            nowMin = null, maxBreakMin = 30,
        )
        assertEquals(listOf("10:00" to "10:10"), layout.breaks.map { hm(it.startMin) to hm(it.endMin) })
        assertEquals(
            listOf("11:00" to "14:00", "15:00" to "16:00"),
            layout.leftoverFree.map { hm(it.startMin) to hm(it.endMin) },
        )
    }

    @Test
    fun layoutIgnoresTheWholePastWhenPlanningToday() {
        val layout = DayLayout.build(plan(freeBlock("09:00", "16:00")), listOf(session("11:00", "12:00")), t("10:00"), 30)
        assertEquals(listOf("10:00" to "11:00", "12:00" to "16:00"), layout.leftoverFree.map { hm(it.startMin) to hm(it.endMin) })
    }

    // ------------------------------------------------- buildWithCommitted

    @Test
    fun committedPlanIsShownExactlyAsCommitted_notSlidToNow() {
        val committed = listOf(planned(WorkKind.TASK, "a", "Assignment", "15:00", "16:00"))
        val view = DayBuilder.buildWithCommitted(
            rules, emptyList(), listOf(task("a", "Assignment", 60)), emptyList(), emptyMap(), today, t("10:30"), committed,
        )
        val s = view.schedule.sessions.single()
        // a live plan would have put it at 10:30; the committed plan keeps it at 15:00
        assertEquals("15:00", hm(s.startMin))
        assertEquals(committed[0].id, s.plannedId)
    }

    @Test
    fun finishedAndMissedSessionsAreNotShownAsUpcoming() {
        val committed = listOf(
            planned(WorkKind.TASK, "a", "A", "09:00", "10:00", SessionStatus.DONE),
            planned(WorkKind.TASK, "b", "B", "10:10", "11:00", SessionStatus.MISSED),
            planned(WorkKind.TASK, "c", "C", "16:00", "17:00"),
        )
        val view = DayBuilder.buildWithCommitted(
            rules, emptyList(), listOf(task("c", "C", 60)), emptyList(), emptyMap(), today, t("12:00"), committed,
        )
        assertEquals(listOf("C"), view.schedule.sessions.map { it.item.title })
    }

    // ------------------------------------------------------- one line per task

    @Test
    fun aTaskMissedThreeTimesIsOneLine() {
        val sessions = listOf(
            planned(WorkKind.TASK, "a", "Assignment", "10:00", "10:30", SessionStatus.MISSED),
            planned(WorkKind.TASK, "a", "Assignment", "10:35", "11:05", SessionStatus.MISSED),
            planned(WorkKind.TASK, "a", "Assignment", "11:10", "11:40", SessionStatus.MISSED),
        )
        val entry = PlanReconciler.summarizeHistory(sessions, emptySet()).single()
        assertEquals(SessionStatus.MISSED, entry.status)
        assertEquals(3, entry.count)
        assertEquals(30, entry.minutes) // "I did it" records one session's worth, not three
        assertEquals("11:10", hm(entry.startMin)) // shows the latest attempt
    }

    @Test
    fun differentTasksStaySeparate_andAreInTimeOrder() {
        val sessions = listOf(
            planned(WorkKind.TASK, "b", "Reading", "11:00", "11:30", SessionStatus.MISSED),
            planned(WorkKind.TASK, "a", "Assignment", "09:00", "09:30", SessionStatus.MISSED),
            planned(WorkKind.TASK, "a", "Assignment", "09:35", "10:05", SessionStatus.MISSED),
        )
        val entries = PlanReconciler.summarizeHistory(sessions, emptySet())
        assertEquals(listOf("Assignment", "Reading"), entries.map { it.title })
        assertEquals(listOf(2, 1), entries.map { it.count })
    }

    @Test
    fun missedLinesForFinishedWorkDisappear() {
        val sessions = listOf(
            planned(WorkKind.TASK, "a", "Assignment", "09:00", "09:30", SessionStatus.MISSED),
            planned(WorkKind.TASK, "a", "Assignment", "09:35", "10:05", SessionStatus.MISSED),
            planned(WorkKind.TASK, "b", "Reading", "11:00", "11:30", SessionStatus.MISSED),
        )
        val entries = PlanReconciler.summarizeHistory(sessions, setOf("a"))
        assertEquals(listOf("Reading"), entries.map { it.title })
    }

    @Test
    fun doneSessionsOfOneTaskAreOneLineWithTheTotal() {
        val sessions = listOf(
            planned(WorkKind.TASK, "a", "Thesis", "09:00", "10:00", SessionStatus.DONE),
            planned(WorkKind.TASK, "a", "Thesis", "14:00", "14:45", SessionStatus.DONE),
        )
        val entry = PlanReconciler.summarizeHistory(sessions, setOf("a")).single()
        assertEquals(SessionStatus.DONE, entry.status)
        assertEquals(2, entry.count)
        assertEquals(105, entry.minutes)
    }

    @Test
    fun doneAndMissedOfTheSameTaskAreTwoLines() {
        val sessions = listOf(
            planned(WorkKind.TASK, "a", "Thesis", "09:00", "10:00", SessionStatus.DONE),
            planned(WorkKind.TASK, "a", "Thesis", "14:00", "14:45", SessionStatus.MISSED),
        )
        val entries = PlanReconciler.summarizeHistory(sessions, emptySet())
        assertEquals(listOf(SessionStatus.DONE, SessionStatus.MISSED), entries.map { it.status })
    }

    @Test
    fun plannedSessionsAreNotHistory() {
        val sessions = listOf(planned(WorkKind.TASK, "a", "Assignment", "17:00", "17:30"))
        assertTrue(PlanReconciler.summarizeHistory(sessions, emptySet()).isEmpty())
    }
}
