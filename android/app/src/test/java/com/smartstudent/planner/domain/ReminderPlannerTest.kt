package com.smartstudent.planner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReminderPlannerTest {
    private fun t(hm: String) = TimeUtil.parse(hm)!!
    private fun hm(min: Int) = TimeUtil.format(min)
    private val today = LocalDate.of(2026, 10, 19)

    private val rules = DayRules(
        wakeMin = t("06:30"), sleepMin = t("23:00"), morningRoutineMin = 45,
        meals = listOf(MealSlot("Lunch", t("13:00"), 40)),
        rest = RestSlot(t("16:00"), 30),
        commute = Commute(30, 30, 10),
    )
    private val classes = listOf(FixedEntry("Maths", t("09:00"), t("11:00")))
    private val plan = DayPlanner.plan(rules, classes)

    private fun session(id: String, start: String, end: String, status: SessionStatus = SessionStatus.PLANNED) =
        PlannedSession("$today|TASK|$id|${t(start)}", today, t(start), t(end), WorkKind.TASK, id, "Task $id", Priority.HIGH, status)

    private val on = ReminderSettings(enabled = true, leadMin = 5, travel = true, mealsAndRest = false, morningSummary = false)

    private fun build(
        settings: ReminderSettings = on, now: String = "07:00",
        sessions: List<PlannedSession> = emptyList(), summaries: List<DaySummary> = emptyList(),
    ) = ReminderPlanner.build(settings, today, t(now), plan, sessions, summaries)

    @Test
    fun nothingWhenRemindersAreOff() {
        assertTrue(build(on.copy(enabled = false), sessions = listOf(session("a", "14:00", "15:00"))).isEmpty())
    }

    @Test
    fun sessionReminderComesTheChosenMinutesBefore() {
        val r = build(sessions = listOf(session("a", "14:00", "15:00"))).single { it.kind == ReminderKind.SESSION }
        assertEquals("13:55", hm(r.minute))
        assertTrue(r.title.contains("Task a"))
        assertTrue(r.text.contains("14:00–15:00"))
    }

    @Test
    fun zeroLeadMeansAtTheStart() {
        val r = build(on.copy(leadMin = 0), sessions = listOf(session("a", "14:00", "15:00"))).single { it.kind == ReminderKind.SESSION }
        assertEquals("14:00", hm(r.minute))
        assertTrue(r.title.startsWith("Time to start"))
    }

    @Test
    fun ifTheLeadTimeHasPassedButTheSessionHasNotStarted_remindAtTheStart() {
        // now 13:58, session at 14:00, lead 5 -> the "5 minutes before" moment is gone: remind at 14:00
        val r = build(now = "13:58", sessions = listOf(session("a", "14:00", "15:00"), session("b", "16:30", "17:00")))
            .filter { it.kind == ReminderKind.SESSION }
        assertEquals(listOf("14:00", "16:25"), r.map { hm(it.minute) })
        assertTrue(r[0].title.startsWith("Time to start"))
        assertTrue(r[1].title.startsWith("Starting soon"))
    }

    @Test
    fun aTinyTaskPlannedAFewMinutesAheadStillGetsAReminder() {
        val r = build(now = "17:31", sessions = listOf(session("tiny", "17:35", "17:38")))
            .single { it.kind == ReminderKind.SESSION }
        assertEquals("17:35", hm(r.minute))
    }

    @Test
    fun aSessionThatAlreadyStartedGetsNoReminder() {
        val r = build(now = "14:10", sessions = listOf(session("a", "14:00", "15:00")))
        assertTrue(r.none { it.kind == ReminderKind.SESSION })
    }

    @Test
    fun doneAndMissedSessionsGetNoReminder() {
        val r = build(sessions = listOf(
            session("a", "14:00", "15:00", SessionStatus.DONE),
            session("b", "15:10", "16:00", SessionStatus.MISSED),
        ))
        assertTrue(r.none { it.kind == ReminderKind.SESSION })
    }

    @Test
    fun leaveForCollegeReminder() {
        // class 09:00-11:00, arrive 10 min early, 30 min travel -> leave 08:20, reminder 5 minutes before;
        // the way home starts when the class ends (11:00), reminder at 10:55
        val r = build().filter { it.kind == ReminderKind.TRAVEL }
        assertEquals(listOf("08:15", "10:55"), r.map { hm(it.minute) })
        assertTrue(r[0].title.startsWith("Time to leave"))
    }

    @Test
    fun travelRemindersCanBeSwitchedOff() {
        assertTrue(build(on.copy(travel = false)).none { it.kind == ReminderKind.TRAVEL })
    }

    @Test
    fun mealAndRestRemindersAreOptional() {
        assertTrue(build().none { it.kind == ReminderKind.MEAL_REST })
        val r = build(on.copy(mealsAndRest = true)).filter { it.kind == ReminderKind.MEAL_REST }
        assertEquals(listOf("12:55" to "Lunch in 5 min"), r.filter { it.title.startsWith("Lunch") }.map { hm(it.minute) to it.title })
        assertTrue(r.any { it.title.startsWith("Rest") })
    }

    @Test
    fun morningSummaryForTomorrow_andTodayOnlyIfStillAhead() {
        val summaries = listOf(
            DaySummary(today, t("06:30"), 3, 150),
            DaySummary(today.plusDays(1), t("06:30"), 1, 60),
        )
        val before = build(on.copy(morningSummary = true), now = "05:00", summaries = summaries).filter { it.kind == ReminderKind.MORNING }
        assertEquals(2, before.size)
        assertTrue(before[0].text.startsWith("3 sessions planned"))
        assertTrue(before[1].text.startsWith("1 session planned"))

        val after = build(on.copy(morningSummary = true), now = "07:00", summaries = summaries).filter { it.kind == ReminderKind.MORNING }
        assertEquals(listOf(today.plusDays(1)), after.map { it.date })
    }

    @Test
    fun remindersAreSortedAndKeysAreUnique() {
        val r = build(
            on.copy(mealsAndRest = true, morningSummary = true),
            sessions = listOf(session("b", "17:00", "18:00"), session("a", "14:00", "15:00")),
            summaries = listOf(DaySummary(today.plusDays(1), t("06:30"), 0, 0)),
        )
        assertEquals(r.sortedWith(compareBy({ it.date }, { it.minute })), r)
        assertEquals(r.size, r.map { it.key }.toSet().size)
    }

    @Test
    fun missedChecksHappenJustAfterEachSessionEnds() {
        val checks = ReminderPlanner.missedChecks(
            listOf(
                session("a", "09:00", "10:00"), session("b", "10:10", "11:00"),
                session("c", "12:00", "13:00", SessionStatus.DONE),
            ),
            nowMin = t("09:30"),
        )
        assertEquals(listOf("10:05", "11:05"), checks.map { hm(it) })
    }

    // ------------------------------------------------ before the end / at the end

    private fun kinds(r: List<Reminder>, kind: ReminderKind) = r.filter { it.kind == kind }

    @Test
    fun reminderBeforeTheSessionEnds() {
        val r = kinds(build(sessions = listOf(session("a", "14:00", "15:00"))), ReminderKind.SESSION_ENDING).single()
        assertEquals("14:55", hm(r.minute))
        assertTrue(r.title.contains("ends in 5 min"))
    }

    @Test
    fun endingSoonLeadIsFlexible_andZeroTurnsItOff() {
        val s = listOf(session("a", "14:00", "15:00"))
        assertEquals("14:50", hm(kinds(build(on.copy(endLeadMin = 10), sessions = s), ReminderKind.SESSION_ENDING).single().minute))
        assertEquals("14:30", hm(kinds(build(on.copy(endLeadMin = 30), sessions = s), ReminderKind.SESSION_ENDING).single().minute))
        assertTrue(kinds(build(on.copy(endLeadMin = 0), sessions = s), ReminderKind.SESSION_ENDING).isEmpty())
        assertTrue(kinds(build(on.copy(endingSoon = false), sessions = s), ReminderKind.SESSION_ENDING).isEmpty())
    }

    @Test
    fun noEndingSoonReminderForASessionTooShortForIt() {
        // a 3-minute task with a 5-minute lead: that moment would be before it starts
        val r = build(now = "17:31", sessions = listOf(session("tiny", "17:35", "17:38")))
        assertTrue(kinds(r, ReminderKind.SESSION_ENDING).isEmpty())
        // ...but it still gets the start reminder and the "time's up" one
        assertEquals(1, kinds(r, ReminderKind.SESSION).size)
        assertEquals("17:38", hm(kinds(r, ReminderKind.SESSION_END).single().minute))
    }

    @Test
    fun reminderWhenTheSessionEnds_withTheSessionIdForTheButtons() {
        val s = session("a", "14:00", "15:00")
        val r = kinds(build(sessions = listOf(s)), ReminderKind.SESSION_END).single()
        assertEquals("15:00", hm(r.minute))
        assertTrue(r.title.startsWith("Time's up"))
        assertEquals(s.id, r.sessionId)
    }

    @Test
    fun theThreeSessionRemindersCanBeTurnedOffSeparately() {
        val s = listOf(session("a", "14:00", "15:00"))
        val all = build(sessions = s)
        assertEquals(1, kinds(all, ReminderKind.SESSION).size)
        assertEquals(1, kinds(all, ReminderKind.SESSION_ENDING).size)
        assertEquals(1, kinds(all, ReminderKind.SESSION_END).size)

        assertTrue(kinds(build(on.copy(startReminder = false), sessions = s), ReminderKind.SESSION).isEmpty())
        assertTrue(kinds(build(on.copy(atEnd = false), sessions = s), ReminderKind.SESSION_END).isEmpty())
        // turning one off leaves the others alone
        assertEquals(1, kinds(build(on.copy(startReminder = false), sessions = s), ReminderKind.SESSION_END).size)
    }

    @Test
    fun aSessionUnderWayStillGetsItsEndReminders() {
        val r = build(now = "14:20", sessions = listOf(session("a", "14:00", "15:00")))
        assertTrue(kinds(r, ReminderKind.SESSION).isEmpty())
        assertEquals("14:55", hm(kinds(r, ReminderKind.SESSION_ENDING).single().minute))
        assertEquals("15:00", hm(kinds(r, ReminderKind.SESSION_END).single().minute))
    }

    @Test
    fun noEndRemindersOnceTheSessionIsOverOrAlreadyDone() {
        val over = build(now = "15:10", sessions = listOf(session("a", "14:00", "15:00")))
        assertTrue(kinds(over, ReminderKind.SESSION_ENDING).isEmpty())
        assertTrue(kinds(over, ReminderKind.SESSION_END).isEmpty())
        val done = build(sessions = listOf(session("a", "14:00", "15:00", SessionStatus.DONE)))
        assertTrue(kinds(done, ReminderKind.SESSION_END).isEmpty())
    }

    @Test
    fun theThreeRemindersForOneSessionAreInOrderWithUniqueKeys() {
        val r = build(sessions = listOf(session("a", "14:00", "15:00"))).filter {
            it.kind in setOf(ReminderKind.SESSION, ReminderKind.SESSION_ENDING, ReminderKind.SESSION_END)
        }
        assertEquals(listOf("13:55", "14:55", "15:00"), r.map { hm(it.minute) })
        assertEquals(3, r.map { it.key }.toSet().size)
    }
}
