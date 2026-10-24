package com.smartstudent.planner.data.report

import android.content.Context
import com.smartstudent.planner.data.local.ReportDao
import com.smartstudent.planner.data.local.ReportEntity
import com.smartstudent.planner.domain.SummarySchedule
import com.smartstudent.planner.reminders.Notifier
import com.smartstudent.planner.reminders.ReminderStore
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Publishes the bedtime summaries: every day how many tasks were finished, on Sundays the week's analysis, and on
 * the last day of the month the month's analysis. Each is shown as a notification and kept in the app, so it can be
 * read again later under Progress.
 */
class SummaryService(
    private val appContext: Context,
    private val builder: ReportBuilder,
    private val reportDao: ReportDao,
    private val store: ReminderStore,
) {
    /** Called by the bedtime alarm: publishes whatever is due for today (as chosen in the reminder settings). */
    suspend fun publishDue(now: LocalDateTime = LocalDateTime.now()) {
        val prefs = store.settingsNow()
        if (!prefs.enabled) return
        val day = SummarySchedule.reportDay(now)
        for (kind in SummarySchedule.due(day, prefs)) publish(kind, day, now, notify = true)
        reportDao.deleteBefore(day.minusDays(400).toString()) // keep about a year
    }

    /** Builds one summary, stores it, and (when [notify]) shows it as a notification. */
    suspend fun publish(kind: SummarySchedule.Kind, day: LocalDate, now: LocalDateTime = LocalDateTime.now(), notify: Boolean = false) {
        val days = SummarySchedule.days(kind, day)
        val report = builder.build(days, day, now)
        val (title, short) = report.notification()
        reportDao.upsert(
            ReportEntity(
                id = "$kind-$day",
                kind = kind.name,
                periodStart = day.minusDays((days - 1).toLong()).toString(),
                periodEnd = day.toString(),
                title = title,
                body = report.toText(),
                createdAt = System.currentTimeMillis(),
            )
        )
        if (notify) {
            val id = when (kind) {
                SummarySchedule.Kind.DAY -> NOTIFICATION_DAY
                SummarySchedule.Kind.WEEK -> NOTIFICATION_WEEK
                SummarySchedule.Kind.MONTH -> NOTIFICATION_MONTH
            }
            val extra = if (kind == SummarySchedule.Kind.DAY) "" else "\nOpen Progress to read the full analysis."
            Notifier.show(appContext, id, title, short + extra, channel = Notifier.CHANNEL_SUMMARY)
        }
    }

    private companion object {
        const val NOTIFICATION_DAY = 7001
        const val NOTIFICATION_WEEK = 7002
        const val NOTIFICATION_MONTH = 7003
    }
}
