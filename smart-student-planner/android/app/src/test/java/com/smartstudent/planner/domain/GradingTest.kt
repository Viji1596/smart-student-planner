package com.smartstudent.planner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class GradingTest {
    private val today = LocalDate.of(2026, 10, 24) // Saturday

    private fun task(id: String, due: LocalDate?, completedAt: LocalDateTime?, title: String = "Task $id") =
        ReportTask(title, due, completedAt != null, completedAt?.toLocalDate(), completedAt, id)

    private fun row(date: LocalDate, ref: String, status: SessionStatus, startMin: Int = 600, minutes: Int = 30, title: String = "Task $ref") =
        PlannedSession("$ref@$date@$startMin@$status", date, startMin, startMin + minutes, WorkKind.TASK, ref, title, Priority.HIGH, status)

    private fun report(
        sessions: List<PlannedSession> = emptyList(),
        tasks: List<ReportTask> = emptyList(),
        days: Int = 7,
        now: LocalDateTime = today.atTime(23, 59),
    ) = PerformanceReport.build(days, today, sessions, tasks, emptyList(), emptyMap(), now)

    // ----------------------------------------------------------- the deadline, with 30 minutes' grace

    @Test
    fun theGraceIsThirtyMinutesAfterTheEndOfTheDueDay() {
        val due = LocalDate.of(2026, 10, 22)
        val midnight = due.plusDays(1).atStartOfDay()
        assertEquals(midnight.plusMinutes(30), Deadline.cutoff(due))
        assertEquals(true, Deadline.onTime(task("a", due, due.atTime(23, 59))))
        assertEquals("29 minutes after midnight still counts", true, Deadline.onTime(task("a", due, midnight.plusMinutes(29))))
        assertEquals("exactly 30 minutes counts", true, Deadline.onTime(task("a", due, midnight.plusMinutes(30))))
        assertEquals("31 minutes is late", false, Deadline.onTime(task("a", due, midnight.plusMinutes(31))))
        assertEquals(false, Deadline.onTime(task("a", due, due.plusDays(2).atTime(9, 0))))
    }

    @Test
    fun noDueDateMeansNothingToJudge() {
        assertNull(Deadline.onTime(task("a", null, today.atTime(9, 0))))
    }

    @Test
    fun anOlderTaskWithOnlyADayIsJudgedByTheDay() {
        val onlyDay = ReportTask("old", today.minusDays(1), true, today.minusDays(2), null, "x")
        assertEquals(true, Deadline.onTime(onlyDay))
        assertEquals(false, Deadline.onTime(ReportTask("old", today.minusDays(5), true, today.minusDays(2), null, "y")))
    }

    @Test
    fun onTimeLateAndMissedAreCounted() {
        val r = report(
            tasks = listOf(
                task("1", today, today.atTime(10, 0)),                                       // on time
                task("2", today.minusDays(1), today.atTime(0, 20)),                          // 20 minutes after midnight: on time
                task("3", today.minusDays(1), today.atTime(1, 0)),                           // an hour late
                task("4", today.minusDays(3), null),                                          // still open, past its deadline
                task("5", today.plusDays(2), null),                                           // open, not due yet
                task("6", null, today.atTime(8, 0)),                                          // no due date: not judged
            ),
        )
        assertEquals(2, r.finishedOnTime)
        assertEquals(1, r.finishedLate)
        assertEquals(1, r.missedInPeriod)
        assertEquals(1, r.overdue)
        assertEquals(67, r.onTimePercent)
    }

    @Test
    fun aTaskStillOpenButNotYetPastItsDeadlineIsNotMissed() {
        // due today, and it is 23:59 today: the 30-minute grace runs to 00:30
        val r = report(tasks = listOf(task("1", today, null)))
        assertEquals(0, r.missedInPeriod)
        assertEquals(0, r.overdue)
        // a minute after the grace ends, it is missed
        val later = report(tasks = listOf(task("1", today, null)), now = today.plusDays(1).atTime(0, 31))
        assertEquals(1, later.missedInPeriod)
    }

    @Test
    fun missesFromBeforeThePeriodDoNotSpoilTheScoreButStillShowAsOverdue() {
        val r = report(days = 1, tasks = listOf(task("old", today.minusDays(10), null)))
        assertEquals(0, r.missedInPeriod)
        assertEquals(1, r.overdue)
    }

    // ------------------------------------------------------------------ scheduled against finished

    @Test
    fun finishedOfScheduledTasksPerDay() {
        val r = report(
            days = 1,
            sessions = listOf(
                row(today, "a", SessionStatus.DONE),
                row(today, "b", SessionStatus.PLANNED),
                row(today, "c", SessionStatus.MISSED),
                row(today, "d", SessionStatus.PLANNED),
            ),
        )
        assertEquals(4, r.scheduledTasks)
        assertEquals(1, r.finishedScheduled)
        assertEquals(25, r.finishedPercent)
    }

    @Test
    fun aTaskWithSeveralSessionsIsOneTask() {
        val r = report(
            days = 1,
            sessions = listOf(
                row(today, "a", SessionStatus.MISSED, 600),
                row(today, "a", SessionStatus.MISSED, 700),
                row(today, "a", SessionStatus.DONE, 800),
                row(today, "b", SessionStatus.PLANNED, 900),
            ),
        )
        assertEquals(2, r.scheduledTasks)
        assertEquals(1, r.finishedScheduled)
    }

    @Test
    fun aScheduledTaskFinishedOnTheTasksTabCountsToo() {
        val r = report(
            days = 1,
            sessions = listOf(row(today, "a", SessionStatus.PLANNED), row(today, "b", SessionStatus.PLANNED)),
            tasks = listOf(task("a", today.plusDays(3), today.atTime(11, 0))),
        )
        assertEquals(2, r.scheduledTasks)
        assertEquals(1, r.finishedScheduled)
    }

    @Test
    fun aTaskFinishedButNeverScheduledDoesNotInflateTheNumbers() {
        val r = report(days = 1, sessions = listOf(row(today, "b", SessionStatus.PLANNED)), tasks = listOf(task("zzz", null, today.atTime(9, 0))))
        assertEquals(1, r.scheduledTasks)
        assertEquals(0, r.finishedScheduled)
    }

    @Test
    fun habitsAreNotTasks() {
        val habitRow = PlannedSession("h", today, 600, 630, WorkKind.HABIT, "h1", "Gym", Priority.MEDIUM, SessionStatus.DONE)
        assertEquals(0, report(days = 1, sessions = listOf(habitRow)).scheduledTasks)
    }

    @Test
    fun eachDayOfTheWeekHasItsOwnBar() {
        val r = report(
            sessions = listOf(
                row(today, "a", SessionStatus.DONE), row(today, "b", SessionStatus.PLANNED),
                row(today.minusDays(1), "c", SessionStatus.DONE),
            ),
        )
        assertEquals(7, r.dayStats.size)
        assertEquals(today, r.dayStats.last().date)
        assertEquals(DayStat(today, 2, 1), r.dayStats.last())
        assertEquals(DayStat(today.minusDays(1), 1, 1), r.dayStats[5])
        assertEquals(DayStat(today.minusDays(6), 0, 0), r.dayStats.first())
    }

    // ------------------------------------------------------------------------------ the grade

    @Test
    fun gradeBoundaries() {
        for ((score, grade) in listOf(100 to "A+", 90 to "A+", 89 to "A", 80 to "A", 79 to "B", 70 to "B", 69 to "C", 60 to "C", 59 to "D", 50 to "D", 49 to "E", 0 to "E")) {
            assertEquals("$score", grade, Grading.grade(score))
        }
        assertEquals("Outstanding", Grading.words(95))
        assertEquals("Time to reset", Grading.words(10))
    }

    @Test
    fun theScoreIsHalfFinishingScheduledTasksAndHalfMeetingDeadlines() {
        // 3 of 4 scheduled tasks finished = 75; deadlines: 1 on time, 1 late = 50  ->  (75 + 50) / 2 = 63 -> C
        val r = report(
            sessions = listOf(
                row(today, "a", SessionStatus.DONE), row(today, "b", SessionStatus.DONE),
                row(today, "c", SessionStatus.DONE), row(today, "d", SessionStatus.PLANNED),
            ),
            tasks = listOf(task("a", today, today.atTime(10, 0)), task("b", today.minusDays(1), today.atTime(5, 0))),
        )
        assertEquals(75, r.finishedPercent)
        assertEquals(1, r.finishedOnTime)
        assertEquals(1, r.finishedLate)
        assertEquals(63, r.score)
        assertEquals("C", r.grade)
    }

    @Test
    fun withOnlyOneOfTheTwoThatOneDecides() {
        assertEquals(50, report(days = 1, sessions = listOf(row(today, "a", SessionStatus.DONE), row(today, "b", SessionStatus.PLANNED))).score)
        assertEquals(100, report(tasks = listOf(task("a", today, today.atTime(9, 0)))).score)
        assertNull(report().score)
        assertEquals("", report().grade)
    }

    @Test
    fun aPerfectWeekIsAnAPlus() {
        val sessions = (0..4).flatMap { d -> listOf(row(today.minusDays(d.toLong()), "t$d", SessionStatus.DONE)) }
        val tasks = (0..4).map { d -> task("t$d", today.minusDays(d.toLong()), today.minusDays(d.toLong()).atTime(15, 0)) }
        val r = report(sessions = sessions, tasks = tasks)
        assertEquals(100, r.score)
        assertEquals("A+", r.grade)
    }

    // -------------------------------------------------------------------------------- the words

    @Test
    fun theReportCongratulatesWhenMostWasFinished() {
        val r = report(
            days = 1,
            sessions = (1..5).map { row(today, "t$it", if (it <= 4) SessionStatus.DONE else SessionStatus.PLANNED) },
        )
        val text = r.toText()
        assertTrue(text, text.contains("Congratulations! You finished 4 of 5 scheduled tasks (80%)."))
        assertTrue(text, text.contains("Grade A"))
    }

    @Test
    fun everyScheduledTaskFinishedGetsTheBiggestCheer() {
        val r = report(days = 1, sessions = listOf(row(today, "a", SessionStatus.DONE), row(today, "b", SessionStatus.DONE)))
        assertTrue(r.toText().contains("Congratulations! You finished every scheduled task: 2 of 2 scheduled tasks (100%)."))
    }

    @Test
    fun aHardDayIsSaidGently() {
        val r = report(days = 1, sessions = listOf(row(today, "a", SessionStatus.DONE), row(today, "b", SessionStatus.PLANNED), row(today, "c", SessionStatus.PLANNED), row(today, "d", SessionStatus.PLANNED)))
        val text = r.toText()
        assertTrue(text, text.contains("You finished 1 of 4 scheduled tasks (25%). There's room to catch up."))
        assertFalse(text, text.contains("Congratulations"))
    }

    @Test
    fun noTasksScheduledIsSaid() {
        assertTrue(report(days = 1).toText().contains("No tasks were scheduled in this period yet."))
    }

    @Test
    fun theDeadlineLineExplainsTheGrace() {
        val r = report(tasks = listOf(task("a", today, today.atTime(9, 0)), task("b", today.minusDays(5), null)))
        val text = r.toText()
        assertTrue(text, text.contains("Deadlines: 1 finished on time (up to 30 minutes after the due day counts), 0 finished late, 1 missed"))
    }

    // ------------------------------------------------------------------------------- the notification

    @Test
    fun theDailyNotificationSaysHowManyWereDone() {
        val r = report(
            days = 1,
            sessions = (1..8).map { row(today, "t$it", if (it <= 6) SessionStatus.DONE else SessionStatus.PLANNED) },
        )
        val (title, text) = r.notification()
        assertEquals("Today: 6 of 8 tasks done · Grade B", title)
        assertTrue(text, text.contains("Good progress."))
    }

    @Test
    fun theNotificationForAQuietDayIsStillUseful() {
        val (title, text) = report(days = 1).notification()
        assertEquals("Today: summary", title)
        assertTrue(text.isNotBlank())
    }

    @Test
    fun theWeeklyAndMonthlyNotificationsAreLabelled() {
        assertTrue(report(days = 7).notification().first.startsWith("Your week"))
        assertTrue(report(days = 30).notification().first.startsWith("Your month"))
    }

    // --------------------------------------------------------------------------------- the insights

    @Test
    fun aOneDayReportHasNoInsights() {
        assertTrue(report(days = 1, sessions = listOf(row(today, "a", SessionStatus.DONE))).insights.isEmpty())
    }

    @Test
    fun strongestAndWeakestWeekday() {
        // Saturdays go well, Fridays don't (2 scheduled tasks each, on two weeks)
        val sat1 = today // Sat 24
        val sat0 = today.minusDays(7)
        val fri1 = today.minusDays(1)
        val fri0 = today.minusDays(8)
        val sessions = listOf(sat1, sat0).flatMap { d -> listOf(row(d, "s${d.dayOfMonth}a", SessionStatus.DONE), row(d, "s${d.dayOfMonth}b", SessionStatus.DONE)) } +
            listOf(fri1, fri0).flatMap { d -> listOf(row(d, "f${d.dayOfMonth}a", SessionStatus.MISSED), row(d, "f${d.dayOfMonth}b", SessionStatus.MISSED)) }
        val r = PerformanceReport.build(14, today, sessions, emptyList(), emptyList(), emptyMap())
        val text = r.insights.joinToString("\n")
        assertTrue(text, text.contains("strongest on Saturdays (4 of 4"))
        assertTrue(text, text.contains("weakest on Fridays (0 of 4"))
    }

    @Test
    fun theTimeOfDayThatWorks() {
        val morning = (0..3).map { row(today.minusDays(it.toLong()), "m$it", SessionStatus.MISSED, startMin = 8 * 60) }
        val evening = (0..3).map { row(today.minusDays(it.toLong()), "e$it", SessionStatus.DONE, startMin = 19 * 60) }
        val r = report(sessions = morning + evening)
        assertTrue(r.insights.toString(), r.insights.any { it.contains("finish more in the evening (100%) than in the morning (0%)") })
    }

    @Test
    fun aHabitOfFinishingOnTheLastDay() {
        val r = report(
            tasks = (1..4).map { task("t$it", today.minusDays(it.toLong()), today.minusDays(it.toLong()).atTime(20, 0)) },
        )
        assertTrue(r.insights.toString(), r.insights.any { it.contains("finish close to deadlines (4 of 4 on the due day or later)") })
    }

    @Test
    fun aHabitOfFinishingEarly() {
        val r = report(
            tasks = (1..4).map { task("t$it", today.plusDays(3), today.minusDays(it.toLong()).atTime(20, 0)) },
        )
        assertTrue(r.insights.toString(), r.insights.any { it.contains("finish ahead of deadlines (4 of 4 at least a day early)") })
    }

    @Test
    fun aTaskThatKeepsSlipping() {
        val r = report(sessions = listOf(row(today, "x", SessionStatus.MISSED, title = "Integration 7.5"), row(today.minusDays(1), "x", SessionStatus.MISSED, title = "Integration 7.5")))
        assertTrue(r.insights.toString(), r.insights.any { it.contains("“Integration 7.5” keeps slipping (missed on 2 days)") })
    }

    @Test
    fun consistencyIsPraisedAndLowConsistencyIsAdvisedOn() {
        val steady = report(sessions = (0..4).map { row(today.minusDays(it.toLong()), "t$it", SessionStatus.DONE) })
        assertTrue(steady.insights.toString(), steady.insights.any { it.contains("worked on 5 of 5 tracked days") })
        val patchy = report(
            sessions = listOf(row(today, "a", SessionStatus.DONE)) + (1..4).map { row(today.minusDays(it.toLong()), "m$it", SessionStatus.MISSED) },
        )
        assertTrue(patchy.insights.toString(), patchy.insights.any { it.contains("worked on only 1 of 5 tracked days") })
    }

    @Test
    fun noInsightWithoutEnoughData() {
        assertTrue(report(sessions = listOf(row(today, "a", SessionStatus.DONE))).insights.isEmpty())
    }

    @Test
    fun atMostFourInsights() {
        assertTrue(report().insights.size <= 4)
    }

    // ----------------------------------------------------------------- planning and undoing

    @Test
    fun aTaskIsNeverPlannedForMoreThanItHasLeft() {
        val items = WorkPlanner.taskItems(listOf(TaskInfo("a", "Tiny", "High", 3, 0, today)), today)
        assertEquals(3, items.single().minutes)
    }

    @Test
    fun unFinishingATaskGoesBackToWhatItHadBefore_neverOneMinuteShort() {
        assertEquals(20, ProgressRules.afterUncomplete(20, 60))
        assertEquals(0, ProgressRules.afterUncomplete(null, 60))
        assertEquals(0, ProgressRules.afterUncomplete(0, 60))
        // can't come back "already finished": at most one minute short, and only if that is what it had
        assertEquals(59, ProgressRules.afterUncomplete(60, 60))
        assertEquals(59, ProgressRules.afterUncomplete(500, 60))
        assertEquals(0, ProgressRules.afterUncomplete(-5, 60))
    }

    @Test
    fun takingBackDoneWorkNeverGoesBelowZero() {
        assertEquals(0, ProgressRules.afterUndo(59, 60))
        assertEquals(15, ProgressRules.afterUndo(45, 30))
        assertEquals(0, ProgressRules.afterUndo(0, 30))
    }

    @Test
    fun undoingDoneRemovesTodaysFinishedSessionsOfThatTaskOnly() {
        val existing = listOf(
            row(today, "a", SessionStatus.DONE, 600, 30), row(today, "a", SessionStatus.DONE, 900, 45),
            row(today, "a", SessionStatus.PLANNED, 1100), row(today, "b", SessionStatus.DONE, 700),
            row(today, "a", SessionStatus.MISSED, 500),
        )
        val (after, minutes) = PlanReconciler.undoDone(existing, WorkKind.TASK, "a")
        assertEquals(75, minutes)
        assertEquals(3, after.size)
        assertTrue(after.none { it.refId == "a" && it.status == SessionStatus.DONE })
        assertTrue(after.any { it.refId == "b" && it.status == SessionStatus.DONE })
        assertTrue(after.any { it.refId == "a" && it.status == SessionStatus.PLANNED })
    }

    @Test
    fun undoingWhenNothingWasDoneChangesNothing() {
        val existing = listOf(row(today, "a", SessionStatus.PLANNED))
        val (after, minutes) = PlanReconciler.undoDone(existing, WorkKind.TASK, "a")
        assertEquals(existing, after)
        assertEquals(0, minutes)
    }

    // ------------------------------------------------------------------- the bedtime schedule

    @Test
    fun theNextBedtimeIsTodayOrTomorrow() {
        val sleep = 23 * 60
        assertEquals(LocalDateTime.of(2026, 10, 24, 23, 0), SummarySchedule.nextBedtime(LocalDateTime.of(2026, 10, 24, 15, 0), sleep))
        assertEquals(LocalDateTime.of(2026, 10, 25, 23, 0), SummarySchedule.nextBedtime(LocalDateTime.of(2026, 10, 24, 23, 5), sleep))
        assertEquals(LocalDateTime.of(2026, 10, 25, 23, 0), SummarySchedule.nextBedtime(LocalDateTime.of(2026, 10, 24, 23, 0, 10), sleep))
    }

    @Test
    fun afterMidnightTheSummaryIsStillAboutTheDayBefore() {
        assertEquals(LocalDate.of(2026, 10, 24), SummarySchedule.reportDay(LocalDateTime.of(2026, 10, 25, 0, 40)))
        assertEquals(LocalDate.of(2026, 10, 24), SummarySchedule.reportDay(LocalDateTime.of(2026, 10, 24, 23, 0)))
    }

    @Test
    fun theWeekIsPublishedOnSundaysAndTheMonthOnItsLastDay() {
        val all = ReminderSettings(enabled = true)
        assertEquals(listOf(SummarySchedule.Kind.DAY), SummarySchedule.due(LocalDate.of(2026, 10, 24), all)) // Saturday
        assertEquals(listOf(SummarySchedule.Kind.DAY, SummarySchedule.Kind.WEEK), SummarySchedule.due(LocalDate.of(2026, 10, 25), all)) // Sunday
        assertEquals(listOf(SummarySchedule.Kind.DAY, SummarySchedule.Kind.MONTH), SummarySchedule.due(LocalDate.of(2026, 10, 31), all)) // Saturday, last day
        assertEquals(listOf(SummarySchedule.Kind.DAY, SummarySchedule.Kind.WEEK, SummarySchedule.Kind.MONTH), SummarySchedule.due(LocalDate.of(2026, 5, 31), all)) // Sunday and last day
    }

    @Test
    fun eachSummaryCanBeSwitchedOff() {
        val none = ReminderSettings(enabled = true, daySummary = false, weekSummary = false, monthSummary = false)
        assertTrue(SummarySchedule.due(LocalDate.of(2026, 5, 31), none).isEmpty())
        val onlyWeek = ReminderSettings(enabled = true, daySummary = false, monthSummary = false)
        assertEquals(listOf(SummarySchedule.Kind.WEEK), SummarySchedule.due(LocalDate.of(2026, 10, 25), onlyWeek))
    }

    @Test
    fun howManyDaysEachSummaryCovers() {
        assertEquals(1, SummarySchedule.days(SummarySchedule.Kind.DAY, LocalDate.of(2026, 10, 31)))
        assertEquals(7, SummarySchedule.days(SummarySchedule.Kind.WEEK, LocalDate.of(2026, 10, 25)))
        assertEquals(31, SummarySchedule.days(SummarySchedule.Kind.MONTH, LocalDate.of(2026, 10, 31)))
        assertEquals(28, SummarySchedule.days(SummarySchedule.Kind.MONTH, LocalDate.of(2027, 2, 28)))
    }
}
