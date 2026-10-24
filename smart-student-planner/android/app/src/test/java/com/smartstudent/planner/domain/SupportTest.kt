package com.smartstudent.planner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SupportTest {
    private val today = LocalDate.of(2026, 10, 21) // a Wednesday

    // ------------------------------------------------------------------ putting a task off

    private fun task(id: String, notBefore: LocalDate? = null, due: LocalDate? = today.plusDays(5)) =
        TaskInfo(id, "Task $id", "High", 60, 0, due, notBefore)

    @Test
    fun aTaskPutOffUntilTomorrowIsNotPlannedToday_butIsPlannedTomorrow() {
        val t = task("a", notBefore = today.plusDays(1))
        assertTrue(WorkPlanner.taskItems(listOf(t), today).isEmpty())
        assertEquals(1, WorkPlanner.taskItems(listOf(t), today.plusDays(1)).size)
        assertEquals(1, WorkPlanner.taskItems(listOf(t), today.plusDays(3)).size)
    }

    @Test
    fun otherTasksAreNotAffected() {
        val items = WorkPlanner.taskItems(listOf(task("a", notBefore = today.plusDays(1)), task("b")), today)
        assertEquals(listOf("b"), items.map { it.refId })
    }

    // ---------------------------------------------------------------------------- triage

    private fun tt(id: String, minutes: Int, priority: Priority, due: LocalDate?) = TriageTask(id, "Task $id", minutes, priority, due)

    @Test
    fun mostPressingFirst() {
        val ranked = Triage.rank(
            listOf(
                tt("later", 30, Priority.HIGH, today.plusDays(10)),
                tt("overdue", 30, Priority.LOW, today.minusDays(1)),
                tt("nodate", 30, Priority.HIGH, null),
                tt("today", 30, Priority.LOW, today),
                tt("week", 30, Priority.MEDIUM, today.plusDays(5)),
            ),
            today,
        )
        assertEquals(listOf("overdue", "today", "week", "later", "nodate"), ranked.map { it.id })
    }

    @Test
    fun withTheSameDueDayThePriorityDecides() {
        val ranked = Triage.rank(listOf(tt("low", 30, Priority.LOW, today.plusDays(1)), tt("high", 30, Priority.HIGH, today.plusDays(1))), today)
        assertEquals("high", ranked.first().id)
    }

    @Test
    fun lightenKeepsTheMostPressingWorkAndPutsTheRestOff() {
        val plan = Triage.lighten(
            listOf(
                tt("a", 60, Priority.HIGH, today.plusDays(1)),
                tt("b", 60, Priority.MEDIUM, today.plusDays(3)),
                tt("c", 60, Priority.LOW, today.plusDays(6)),
                tt("d", 60, Priority.LOW, null),
            ),
            today, keepMinutes = 120,
        )
        assertEquals(listOf("a", "b"), plan.keep.map { it.id })
        assertEquals(listOf("c", "d"), plan.defer.map { it.id })
        assertEquals(240, plan.totalMinutes)
        assertEquals(120, plan.keptMinutes)
    }

    @Test
    fun lightenNeverPutsOffWhatIsOverdueOrDueToday() {
        val plan = Triage.lighten(
            listOf(tt("overdue", 90, Priority.LOW, today.minusDays(2)), tt("today", 90, Priority.LOW, today), tt("later", 30, Priority.HIGH, today.plusDays(4))),
            today, keepMinutes = 60,
        )
        assertEquals(listOf("overdue", "today"), plan.keep.map { it.id })
        assertEquals(listOf("later"), plan.defer.map { it.id })
    }

    @Test
    fun lightenAlwaysKeepsAtLeastOneTask() {
        val plan = Triage.lighten(listOf(tt("a", 300, Priority.LOW, null), tt("b", 30, Priority.LOW, null)), today, keepMinutes = 10)
        assertEquals(1, plan.keep.size)
        assertEquals(1, plan.defer.size)
    }

    @Test
    fun aLightDayIsLeftAlone() {
        val plan = Triage.lighten(listOf(tt("a", 30, Priority.LOW, null), tt("b", 30, Priority.LOW, null)), today, keepMinutes = 120)
        assertTrue(plan.defer.isEmpty())
    }

    // ----------------------------------------------------------------------------- report

    private fun session(date: LocalDate, ref: String, title: String, minutes: Int, status: SessionStatus, kind: WorkKind = WorkKind.TASK) =
        PlannedSession(PlanReconciler.sessionId(date, kind, ref, 600), date, 600, 600 + minutes, kind, ref, title, Priority.HIGH, status)

    private fun report(
        sessions: List<PlannedSession>,
        tasks: List<ReportTask> = emptyList(),
        habits: List<HabitInfo> = emptyList(),
        completions: Map<String, Set<LocalDate>> = emptyMap(),
        days: Int = 7,
    ) = PerformanceReport.build(days, today, sessions, tasks, habits, completions)

    @Test
    fun minutesSessionsAndTheBestDay() {
        val r = report(
            listOf(
                session(today.minusDays(1), "a", "Maths", 60, SessionStatus.DONE),
                session(today.minusDays(1), "b", "Physics", 30, SessionStatus.DONE),
                session(today.minusDays(3), "a", "Maths", 45, SessionStatus.DONE),
            )
        )
        assertEquals(135, r.minutesDone)
        assertEquals(3, r.sessionsDone)
        assertEquals(today.minusDays(1), r.bestDay)
        assertEquals(90, r.bestDayMinutes)
        assertEquals(2, r.trackedDays)
    }

    @Test
    fun aTaskMissedThreeTimesIsOneMiss_andNoMissIfDoneLater() {
        val d = today.minusDays(1)
        val r = report(
            listOf(
                session(d, "a", "Maths", 30, SessionStatus.MISSED),
                PlannedSession("x2", d, 700, 730, WorkKind.TASK, "a", "Maths", Priority.HIGH, SessionStatus.MISSED),
                PlannedSession("x3", d, 800, 830, WorkKind.TASK, "a", "Maths", Priority.HIGH, SessionStatus.MISSED),
                session(d, "b", "Physics", 30, SessionStatus.MISSED),
                PlannedSession("x4", d, 900, 930, WorkKind.TASK, "b", "Physics", Priority.HIGH, SessionStatus.DONE),
            )
        )
        assertEquals(1, r.sessionsMissed) // Maths; Physics was done later
        assertEquals(1, r.sessionsDone)
        assertEquals(50, r.completionPercent)
        assertEquals("Maths", r.mostMissed)
    }

    @Test
    fun theTrendComparesWithThePeriodBefore() {
        val r = report(
            listOf(
                session(today.minusDays(2), "a", "Maths", 120, SessionStatus.DONE),
                session(today.minusDays(9), "a", "Maths", 60, SessionStatus.DONE), // the week before
            )
        )
        assertEquals(60, r.minutesDonePrevious)
        assertTrue(r.toText().contains("1 h more than the period before"))
    }

    @Test
    fun noHistoryYetIsSaidHonestly() {
        val r = report(emptyList())
        assertEquals(0, r.trackedDays)
        assertNull(r.completionPercent)
        assertNull(r.minutesDonePrevious)
        assertTrue(r.toText().contains("only just started keeping a history"))
    }

    @Test
    fun tasksFinishedOpenOverdueAndOnTime() {
        val r = report(
            emptyList(),
            tasks = listOf(
                ReportTask("done on time", today.minusDays(1), true, today.minusDays(2)),
                ReportTask("done late", today.minusDays(5), true, today.minusDays(2)),
                ReportTask("done long ago", today.minusDays(40), true, today.minusDays(30)),
                ReportTask("overdue", today.minusDays(1), false, null),
                ReportTask("soon", today.plusDays(3), false, null),
                ReportTask("later", today.plusDays(30), false, null),
            ),
        )
        assertEquals(2, r.tasksCompleted)
        assertEquals(3, r.tasksOpen)
        assertEquals(1, r.overdue)
        assertEquals(1, r.dueSoon)
        assertEquals(50, r.onTimePercent)
    }

    @Test
    fun aDailyHabitsCountAndStreak() {
        val habit = HabitInfo("h", "Coding", 30, "Medium", HabitKind.DAILY, emptySet(), 0)
        val done = setOf(today, today.minusDays(1), today.minusDays(2), today.minusDays(4))
        val h = report(emptyList(), habits = listOf(habit), completions = mapOf("h" to done)).habits.single()
        assertEquals(4, h.done)
        assertEquals(7, h.expected)
        assertEquals(3, h.streak)
    }

    @Test
    fun aStreakSurvivesTodayNotBeingOverYet() {
        val habit = HabitInfo("h", "Coding", 30, "Medium", HabitKind.DAILY, emptySet(), 0)
        val done = setOf(today.minusDays(1), today.minusDays(2))
        assertEquals(2, report(emptyList(), habits = listOf(habit), completions = mapOf("h" to done)).habits.single().streak)
    }

    @Test
    fun fixedDayHabitsOnlyCountTheirOwnDays() {
        // Monday, Wednesday and Friday; the last 7 days up to Wednesday 21 Oct contain Mon 19, Wed 21 and Fri 16
        val habit = HabitInfo("h", "Gym", 60, "Medium", HabitKind.DAYS, setOf(1, 3, 5), 0)
        val done = setOf(today, LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 16))
        val h = report(emptyList(), habits = listOf(habit), completions = mapOf("h" to done)).habits.single()
        assertEquals(3, h.expected)
        assertEquals(3, h.done)
        assertEquals(3, h.streak) // Wed, Mon, Fri in a row on their own days
    }

    @Test
    fun timesAWeekHabitsExpectThatManyPerWeek() {
        val habit = HabitInfo("h", "Aptitude", 45, "Medium", HabitKind.WEEKLY, emptySet(), 3)
        val r7 = report(emptyList(), habits = listOf(habit), completions = mapOf("h" to setOf(today, today.minusDays(2))))
        assertEquals(3, r7.habits.single().expected)
        assertEquals(2, r7.habits.single().done)
        val r14 = report(emptyList(), habits = listOf(habit), days = 14)
        assertEquals(6, r14.habits.single().expected)
    }

    @Test
    fun theTextIsFriendlyAndMentionsTheNumbers() {
        val r = report(
            listOf(session(today.minusDays(1), "a", "Maths", 90, SessionStatus.DONE), session(today.minusDays(1), "b", "Physics", 30, SessionStatus.MISSED)),
            tasks = listOf(ReportTask("t", today.minusDays(1), false, null)),
        )
        val text = r.toText()
        assertTrue(text, text.startsWith("Here's how the last 7 days went:"))
        assertTrue(text, text.contains("1 h 30 min"))
        assertTrue(text, text.contains("Physics"))
        assertTrue(text, text.contains("1 overdue"))
        assertFalse(text.isBlank())
        assertNotNull(r.bestDay)
    }

    // ------------------------------------------------------------------------------- wording

    @Test
    fun focusNamesTheStartingPointAndTheNextTwo() {
        val text = SupportText.focus(
            listOf(
                tt("a", 45, Priority.HIGH, today),
                tt("b", 60, Priority.MEDIUM, today.plusDays(1)),
                tt("c", 30, Priority.LOW, today.plusDays(4)),
                tt("d", 30, Priority.LOW, null),
            ),
            today,
        )
        assertTrue(text, text.startsWith("Start with “Task a”."))
        assertTrue(text, text.contains("1. Task a (due today, 45 min left)"))
        assertTrue(text, text.contains("2. Task b (due tomorrow, 1 h left)"))
        assertTrue(text, text.contains("3. Task c (due Sun 25 Oct, 30 min left)"))
        assertFalse(text, text.contains("Task d"))
        assertTrue(text, text.contains("10 minutes"))
    }

    @Test
    fun focusWithNothingToDoIsKind() {
        assertTrue(SupportText.focus(emptyList(), today).startsWith("You have nothing open"))
        assertTrue(SupportText.focus(listOf(tt("a", 0, Priority.HIGH, today)), today).startsWith("You have nothing open"))
    }

    @Test
    fun overdueIsSaidPlainly() {
        assertEquals("overdue", SupportText.dueWords(today.minusDays(1), today))
        assertEquals("no due date", SupportText.dueWords(null, today))
    }

    @Test
    fun theLightenQuestionNamesWhatWouldMove() {
        val plan = Triage.lighten(
            listOf(tt("a", 60, Priority.HIGH, today.plusDays(1)), tt("b", 60, Priority.LOW, today.plusDays(6)), tt("c", 60, Priority.LOW, null)),
            today, keepMinutes = 60,
        )
        val q = SupportText.lightenQuestion(plan)
        assertTrue(q, q.contains("about 3 h of work planned"))
        assertTrue(q, q.contains("put off 2 tasks until tomorrow: “Task b”, “Task c”"))
        assertTrue(q, q.contains("Nothing is deleted"))
        assertTrue(SupportText.lightenDone(plan).contains("✓ I moved 2 tasks to tomorrow"))
    }

    // ------------------------------------------------------- work done outside the planned sessions

    private fun planned(ref: String, title: String, start: Int, end: Int, status: SessionStatus = SessionStatus.PLANNED, id: String = "$ref@$start") =
        PlannedSession(id, today, start, end, WorkKind.TASK, ref, title, Priority.HIGH, status)

    private fun record(existing: List<PlannedSession>, ref: String, minutes: Int = 45, nowMin: Int = 600) =
        PlanReconciler.recordDone(existing, today, WorkKind.TASK, ref, "Maths", Priority.MEDIUM, minutes, nowMin)

    @Test
    fun aPlannedSessionTheStudentFinishedByTalkingBecomesDone_shownAsJustFinished() {
        val result = record(listOf(planned("a", "Maths homework", 16 * 60, 16 * 60 + 45), planned("b", "Reading", 17 * 60, 17 * 60 + 30)), "a", nowMin = 11 * 60)
        val done = result.single { it.refId == "a" }
        assertEquals(SessionStatus.DONE, done.status)
        assertEquals(45, done.duration)
        assertEquals(11 * 60, done.endMin) // finished now, not at 16:45
        assertEquals("Maths homework", done.title) // the planned session's own name and priority are kept
        assertEquals(SessionStatus.PLANNED, result.single { it.refId == "b" }.status) // other work untouched
    }

    @Test
    fun withNoPlannedSessionAFinishedOneIsAdded() {
        val result = record(emptyList(), "a", minutes = 30, nowMin = 11 * 60)
        val done = result.single()
        assertEquals(SessionStatus.DONE, done.status)
        assertEquals(30, done.duration)
        assertEquals(11 * 60, done.endMin)
        assertEquals("Maths", done.title)
    }

    @Test
    fun onlyTheEarliestPlannedSessionOfThatTaskIsUsed() {
        val result = record(listOf(planned("a", "Maths", 14 * 60, 14 * 60 + 30), planned("a", "Maths", 18 * 60, 18 * 60 + 30)), "a", nowMin = 12 * 60)
        assertEquals(listOf(SessionStatus.DONE, SessionStatus.PLANNED), result.map { it.status })
        assertEquals(18 * 60, result.last().startMin) // the later one stays planned
    }

    @Test
    fun olderMissedRecordsOfThatTaskDisappear_butOtherTasksMissedRecordsStay() {
        val existing = listOf(
            planned("a", "Maths", 9 * 60, 9 * 60 + 30, SessionStatus.MISSED),
            planned("a", "Maths", 10 * 60, 10 * 60 + 30, SessionStatus.MISSED),
            planned("b", "Physics", 9 * 60, 9 * 60 + 30, SessionStatus.MISSED),
        )
        val result = record(existing, "a", nowMin = 12 * 60)
        assertEquals(1, result.count { it.refId == "a" })
        assertEquals(SessionStatus.DONE, result.single { it.refId == "a" }.status)
        assertEquals(SessionStatus.MISSED, result.single { it.refId == "b" }.status)
    }

    @Test
    fun recordedWorkShowsUnderEarlierToday_andCountsInTheReport() {
        val result = record(listOf(planned("a", "Maths homework", 16 * 60, 16 * 60 + 45)), "a", nowMin = 11 * 60)
        val earlier = PlanReconciler.summarizeHistory(result, setOf("a"))
        assertEquals(1, earlier.size)
        assertEquals(SessionStatus.DONE, earlier.single().status)
        assertEquals("Maths homework", earlier.single().title)
        val report = PerformanceReport.build(7, today, result, emptyList(), emptyList(), emptyMap())
        assertEquals(45, report.minutesDone)
        assertEquals(1, report.sessionsDone)
    }

    @Test
    fun anEarlyMorningNowDoesNotGoBelowMidnight() {
        val done = record(emptyList(), "a", minutes = 30, nowMin = 10).single()
        assertEquals(0, done.startMin)
        assertEquals(30, done.endMin)
    }

    @Test
    fun aVeryShortOrZeroLengthIsRaisedToFiveMinutes() {
        assertEquals(5, record(emptyList(), "a", minutes = 0, nowMin = 600).single().duration)
    }

    @Test
    fun recordingTwiceNeverDuplicates() {
        val once = record(listOf(planned("a", "Maths", 16 * 60, 16 * 60 + 45)), "a", nowMin = 11 * 60)
        val twice = record(once, "a", nowMin = 11 * 60)
        assertEquals(1, twice.count { it.refId == "a" })
    }

    // ----------------------------------------------------- the report is useful from the first day

    @Test
    fun aPlanAloneDoesNotCountAsKnownHistory() {
        val r = report(listOf(session(today, "a", "Maths", 60, SessionStatus.PLANNED)))
        assertEquals(0, r.trackedDays)
        assertEquals(60, r.todayPlannedLeftMinutes)
        assertTrue(r.toText().contains("only just started keeping a history"))
    }

    @Test
    fun todaySoFarIsShown() {
        val r = report(
            listOf(
                session(today, "a", "Maths", 90, SessionStatus.DONE),
                session(today, "b", "Physics", 60, SessionStatus.PLANNED),
                session(today, "c", "Reading", 30, SessionStatus.PLANNED),
            )
        )
        assertEquals(90, r.todayDoneMinutes)
        assertEquals(90, r.todayPlannedLeftMinutes)
        assertTrue(r.toText().contains("Today so far: 1 h 30 min done, 1 h 30 min still planned."))
    }

    @Test
    fun noTodayLineWhenNothingIsDoneOrPlannedToday() {
        assertFalse(report(emptyList()).toText().contains("Today so far"))
    }

    @Test
    fun aOneDayReportSaysTodayNotOneDays() {
        val text = report(emptyList(), days = 1).toText()
        assertTrue(text, text.startsWith("Here's how today is going:"))
        assertFalse(text.contains("1 days"))
    }

    @Test
    fun yesterdaysFinishedWorkDoesNotLeakIntoTodaySoFar() {
        val r = report(listOf(session(today.minusDays(1), "a", "Maths", 60, SessionStatus.DONE)))
        assertEquals(0, r.todayDoneMinutes)
        assertEquals(1, r.trackedDays)
    }

    @Test
    fun aSingleDayReportDoesNotTalkAboutWeeks() {
        val text = report(listOf(session(today, "a", "Maths", 45, SessionStatus.DONE)), days = 1).toText()
        assertFalse(text, text.contains("strongest day"))
        assertFalse(text, text.contains("steady"))
        assertTrue(text, text.contains("made a start today"))
    }

    @Test
    fun praiseNeedsAFewDaysBehindIt() {
        val oneDay = report(listOf(session(today.minusDays(1), "a", "Maths", 60, SessionStatus.DONE)))
        assertFalse(oneDay.toText().contains("steady stretch"))
        val fewDays = report(
            listOf(1L, 2L, 3L).map { session(today.minusDays(it), "a$it", "Maths", 60, SessionStatus.DONE) }
        )
        assertTrue(fewDays.toText().contains("steady stretch"))
        assertTrue(fewDays.toText().contains("strongest day"))
    }

    // ------------------------------------------------------------ "how am I doing", recognised on the phone

    @Test
    fun reportQuestionsAreRecognisedInManyWords() {
        for (t in listOf(
            "how am i doing", "How am I doing?", "how i am doing", "how am i performing", "progress", "my progress",
            "am i on track", "how was this week", "am i doing well", "how much have i done", "how much did i study this week",
            "give me my report", "report", "my stats", "summary", "weekly report", "how is my week going", "how did i do today",
            "how productive was i", "am i falling behind", "how are my streaks", "performance",
        )) assertTrue(t, ReportQuestion.matches(t))
    }

    @Test
    fun otherSentencesAreNotReportQuestions() {
        for (t in listOf(
            "add task progress report", "mark physics done", "move maths to 6pm", "what's my plan today", "hello", "thanks",
            "delete the summary task", "i finished my homework", "how long is maths", "",
        )) assertFalse(t, ReportQuestion.matches(t))
    }

    @Test
    fun theNumberOfDaysIsRead() {
        assertEquals(7, ReportQuestion.days("how am i doing"))
        assertEquals(1, ReportQuestion.days("how did i do today"))
        assertEquals(14, ReportQuestion.days("progress over the last two weeks"))
        assertEquals(30, ReportQuestion.days("how was my month"))
        assertEquals(30, ReportQuestion.days("monthly report"))
        assertEquals(1, ReportQuestion.days("daily report"))
        assertTrue(ReportQuestion.matches("what's my grade"))
        assertTrue(ReportQuestion.matches("show my score"))
        assertTrue(ReportQuestion.matches("my weekly analysis"))
        assertEquals(30, ReportQuestion.days("my monthly analysis"))
        assertEquals(21, ReportQuestion.days("report for the last 21 days"))
        assertEquals(60, ReportQuestion.days("report for 300 days"))
    }

    // ------------------------------------------------------- questions the phone answers by itself

    @Test
    fun theSameQuestionsTheServerUnderstandsAreUnderstoodOnThePhone() {
        assertEquals(LocalAction("show_report", days = 7), LocalQuestions.detect("how am i doing"))
        assertEquals(LocalAction("show_report", days = 1), LocalQuestions.detect("how is my day going"))
        assertEquals(LocalAction("show_focus"), LocalQuestions.detect("what should I focus on?"))
        assertEquals(LocalAction("show_focus"), LocalQuestions.detect("where do i start"))
        assertEquals(LocalAction("show_focus"), LocalQuestions.detect("help me prioritise"))
        assertEquals(LocalAction("show_plan", day = "today"), LocalQuestions.detect("what's my plan today"))
        assertEquals(LocalAction("show_plan", day = "tomorrow"), LocalQuestions.detect("show my schedule for tomorrow"))
        assertEquals(LocalAction("show_free_time", day = "tomorrow"), LocalQuestions.detect("how much free time do i have tomorrow"))
        assertEquals(LocalAction("show_tasks", filter = "overdue"), LocalQuestions.detect("what's overdue"))
        assertEquals(LocalAction("show_tasks", filter = "due_soon"), LocalQuestions.detect("what is due this week"))
        assertEquals(LocalAction("show_tasks", filter = "open"), LocalQuestions.detect("show my tasks"))
        assertEquals(LocalAction("show_missed"), LocalQuestions.detect("what did i miss today"))
    }

    @Test
    fun commandsAreNeverTreatedAsQuestions() {
        for (t in listOf("add task what should i focus on", "move maths to 6pm", "delete the plan", "mark overdue tasks done", "start maths today", "hello", "")) {
            assertNull(t, LocalQuestions.detect(t))
        }
    }

    @Test
    fun versionsAreCompared() {
        assertTrue(ServerVersion.isOlder("", "6.5"))
        assertTrue(ServerVersion.isOlder("6.4", "6.5"))
        assertTrue(ServerVersion.isOlder("5.9", "6.5"))
        assertFalse(ServerVersion.isOlder("6.5", "6.5"))
        assertFalse(ServerVersion.isOlder("6.10", "6.5"))
        assertFalse(ServerVersion.isOlder("7", "6.5"))
        assertTrue(ServerVersion.isOlder("6", "6.5"))
    }

    @Test
    fun whatToFocusOnIsUnderstoodInManyWords() {
        val phrases = listOf(
            "what to focus on", "What to focus on?", "what do i focus on", "what should i be focusing on", "focus", "what to do",
            "what to do now", "what should i do today", "what do i do now", "what can i do now", "what do i work on",
            "where to start", "where do i begin", "which task first", "which one should i start with", "what's my priority",
            "what are my priorities", "priorities", "most urgent", "what's urgent", "tell me what to do", "suggest what to do",
            "what do i do next", "i don't know what to do", "i dont know where to start", "what's first", "start with what",
            "give me something to do", "what's on my plate", "what needs my attention", "what to start with", "what to study",
            "what do i need to do first", "help me focus", "help me prioritise", "what first", "what next", "what now",
        )
        for (t in phrases) assertEquals(t, LocalAction("show_focus"), LocalQuestions.detect(t))
    }

    @Test
    fun focusIsNotTakenForCommandsStatementsOrOtherDays() {
        for (t in listOf(
            "add task focus on dsa due friday", "can you please add a low priority task clean desk", "mark priorities done",
            "delete the priorities task", "i can't focus", "my priority is physics", "what should i do tomorrow", "what to do on friday",
        )) assertTrue(t, LocalQuestions.detect(t)?.type != "show_focus")
    }
}
