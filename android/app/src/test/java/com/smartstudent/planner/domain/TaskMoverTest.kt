package com.smartstudent.planner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

class TaskMoverTest {
    private val day = LocalDate.of(2026, 10, 24)
    private fun t(hm: String) = TimeUtil.parse(hm)!!
    private fun hm(min: Int) = TimeUtil.format(min)

    private fun session(ref: String, start: String, end: String, title: String = "Task $ref") =
        PlannedSession("$day|TASK|$ref|${t(start)}", day, t(start), t(end), WorkKind.TASK, ref, title, Priority.HIGH, SessionStatus.PLANNED)

    private fun spans(list: List<PlannedSession>) =
        list.sortedBy { it.startMin }.map { "${it.refId} ${hm(it.startMin)}-${hm(it.endMin)}" }

    // ------------------------------------------------------------------------------------------ swapping

    @Test
    fun twoSingleSessionsOfTheSameLengthTradePlaces() {
        val r = TaskMover.swap(day, listOf(session("A", "10:00", "11:00")), listOf(session("B", "15:00", "16:00")))
        assertEquals(listOf("B 10:00-11:00", "A 15:00-16:00"), spans(r))
    }

    @Test
    fun aTaskInTwoSessionsIsSwappedAsAWhole_theScreenshotCase() {
        // Deep learning project: 19:20-19:50 and 20:30-22:00 (2 h in all); MLP project: 22:10-23:10 (1 h)
        val dl = listOf(session("DL", "19:20", "19:50"), session("DL", "20:30", "22:00"))
        val mlp = listOf(session("MLP", "22:10", "23:10"))
        val r = TaskMover.swap(day, dl, mlp)
        // MLP was later, so it goes first and takes the first hour of the time they had together; DL gets the rest
        assertEquals(
            listOf("MLP 19:20-19:50", "MLP 20:30-21:00", "DL 21:00-22:00", "DL 22:10-23:10"),
            spans(r),
        )
        assertEquals(60, r.filter { it.refId == "MLP" }.sumOf { it.duration })
        assertEquals(120, r.filter { it.refId == "DL" }.sumOf { it.duration })
    }

    @Test
    fun swappingIsTheSameWhicheverOrderTheyAreNamedIn() {
        val a = listOf(session("A", "10:00", "10:30"), session("A", "12:00", "13:30"))
        val b = listOf(session("B", "14:00", "15:00"))
        assertEquals(spans(TaskMover.swap(day, a, b)), spans(TaskMover.swap(day, b, a)))
    }

    @Test
    fun theResultIsPinnedAndPlanned() {
        val r = TaskMover.swap(day, listOf(session("A", "10:00", "11:00")), listOf(session("B", "15:00", "16:00")))
        assertTrue(r.all { it.pinned && it.status == SessionStatus.PLANNED })
    }

    @Test
    fun slotsThatTouchBecomeOneSession() {
        // A has 10:00-10:30 and 10:30-11:00 (two sessions back to back); B is 14:00-14:30
        val r = TaskMover.swap(
            day,
            listOf(session("A", "10:00", "10:30"), session("A", "10:30", "11:00")),
            listOf(session("B", "14:00", "14:30")),
        )
        assertEquals(listOf("B 10:00-10:30", "A 10:30-11:00", "A 14:00-14:30"), spans(r))
    }

    @Test
    fun interleavedSessionsAreShareOutAgain() {
        // A 10:00-10:30, B 10:30-11:00, A 11:00-12:00 (A = 90 min, B = 30 min): B goes first
        val r = TaskMover.swap(
            day,
            listOf(session("A", "10:00", "10:30"), session("A", "11:00", "12:00")),
            listOf(session("B", "10:30", "11:00")),
        )
        assertEquals(listOf("B 10:00-10:30", "A 10:30-12:00"), spans(r))
    }

    @Test
    fun everyTaskKeepsItsMinutes_nothingOverlaps_andNothingLeavesTheTimeTheyHad() {
        val rnd = Random(7)
        repeat(400) {
            // a handful of sessions at distinct times, shared at random between two tasks
            var cursor = t("08:00")
            val a = mutableListOf<PlannedSession>()
            val b = mutableListOf<PlannedSession>()
            repeat(rnd.nextInt(2, 7)) {
                val len = rnd.nextInt(1, 7) * 15
                val s = session(if (rnd.nextBoolean() && a.size < 4 || b.size >= 4) "A" else "B", hm(cursor), hm(cursor + len))
                if (s.refId == "A") a += s else b += s
                cursor += len + rnd.nextInt(0, 4) * 10
            }
            if (a.isEmpty() || b.isEmpty()) return@repeat
            val out = TaskMover.swap(day, a, b)
            assertEquals(a.sumOf { it.duration }, out.filter { it.refId == "A" }.sumOf { it.duration })
            assertEquals(b.sumOf { it.duration }, out.filter { it.refId == "B" }.sumOf { it.duration })
            val sorted = out.sortedBy { it.startMin }
            for (i in 1 until sorted.size) assertTrue("overlap in $out", sorted[i].startMin >= sorted[i - 1].endMin)
            // every minute used was a minute one of the two tasks had
            val had = (a + b).flatMap { (it.startMin until it.endMin).toList() }.toSet()
            out.forEach { s -> (s.startMin until s.endMin).forEach { m -> assertTrue("$m was not theirs", m in had) } }
            assertEquals(out.map { it.id }.toSet().size, out.size)
        }
    }

    // ------------------------------------------------------------------------------------------ moving

    private val rules = DayRules(wakeMin = t("07:00"), sleepMin = t("23:00"), morningRoutineMin = 30, meals = emptyList())
    private fun planWith(vararg classes: FixedEntry) = DayPlanner.plan(rules, classes.toList())

    @Test
    fun allSessionsAreMovedBackToBack() {
        val r = TaskMover.placeAll(planWith(), emptyList(), listOf(30, 90), t("20:30"), t("19:00"), false)
        assertEquals(PlaceAllResult.Placed(listOf(t("20:30"), t("21:00"))), r)
    }

    @Test
    fun theLaterOnesStepAroundWhatIsInTheWay() {
        val blocker = TimeBlock(t("21:00"), t("21:30"), "pinned", BlockKind.CLASS)
        val r = TaskMover.placeAll(planWith(), listOf(blocker), listOf(30, 30), t("20:30"), t("19:00"), false)
        assertEquals(PlaceAllResult.Placed(listOf(t("20:30"), t("21:30"))), r)
    }

    @Test
    fun ifTheFirstCannotGoThereNothingMoves() {
        val r = TaskMover.placeAll(planWith(), emptyList(), listOf(60, 60), t("22:30"), t("19:00"), false)
        assertTrue(r is PlaceAllResult.Refused)
        assertTrue((r as PlaceAllResult.Refused).reason, r.reason.contains("outside your day"))
    }

    @Test
    fun ifTheRestDoesNotFitItIsSaidAndNothingMoves() {
        // free only until 20:30: 90 + 90 minutes from 18:00 cannot fit
        val plan = planWith(FixedEntry("Lab", t("20:30"), t("23:00")))
        val r = TaskMover.placeAll(plan, emptyList(), listOf(90, 90), t("18:00"), t("17:00"), false)
        assertTrue(r is PlaceAllResult.Refused)
        assertTrue((r as PlaceAllResult.Refused).reason, r.reason.contains("doesn't fit from then"))
        assertTrue(r.reason, r.reason.contains("1 h 30 min"))
    }

    @Test
    fun flexibleMeansTheNearestFreeTime() {
        val plan = planWith(FixedEntry("Class", t("19:00"), t("20:00")))
        val r = TaskMover.placeAll(plan, emptyList(), listOf(30, 30), t("19:30"), t("17:00"), true)
        assertEquals(PlaceAllResult.Placed(listOf(t("20:00"), t("20:30"))), r)
    }
}
