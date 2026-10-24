package com.smartstudent.planner.domain

import java.time.LocalDate
import java.time.LocalDateTime

/** When the bedtime summaries are published, and which ones are due on a given day. */
object SummarySchedule {
    enum class Kind { DAY, WEEK, MONTH }

    /** Today's bedtime as a moment, or tomorrow's if it has already passed. [sleepMin] is minutes since midnight. */
    fun nextBedtime(now: LocalDateTime, sleepMin: Int): LocalDateTime {
        val today = now.toLocalDate().atStartOfDay().plusMinutes(sleepMin.toLong())
        return if (today.isAfter(now.plusSeconds(30))) today else today.plusDays(1)
    }

    /** A summary that fires around bedtime is about that day, even if the phone delays it past midnight. */
    fun reportDay(now: LocalDateTime): LocalDate =
        if (now.hour < 6) now.toLocalDate().minusDays(1) else now.toLocalDate()

    /** What is published at the end of [day]: the day, plus the week on Sundays, plus the month on its last day. */
    fun due(day: LocalDate, prefs: ReminderSettings): List<Kind> = buildList {
        if (prefs.daySummary) add(Kind.DAY)
        if (prefs.weekSummary && day.dayOfWeek == java.time.DayOfWeek.SUNDAY) add(Kind.WEEK)
        if (prefs.monthSummary && day.plusDays(1).dayOfMonth == 1) add(Kind.MONTH)
    }

    /** How many days a summary covers: today, the 7 days to Sunday, or the month so far. */
    fun days(kind: Kind, day: LocalDate): Int = when (kind) {
        Kind.DAY -> 1
        Kind.WEEK -> 7
        Kind.MONTH -> day.dayOfMonth
    }
}
