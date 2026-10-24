package com.smartstudent.planner.data.report

import com.smartstudent.planner.data.local.PlannedSessionDao
import com.smartstudent.planner.data.repo.HabitRepository
import com.smartstudent.planner.data.repo.TaskRepository
import com.smartstudent.planner.data.repo.toDomainOrNull
import com.smartstudent.planner.data.repo.toDoneDates
import com.smartstudent.planner.data.repo.toInfo
import com.smartstudent.planner.domain.PerformanceReport
import com.smartstudent.planner.domain.ReportTask
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Builds the performance report from what is kept on this phone: the session history, the tasks and the habits.
 * One place, used by the chat, the Progress screen and the bedtime notifications, so they always agree.
 */
class ReportBuilder(
    private val sessionDao: PlannedSessionDao,
    private val tasks: TaskRepository,
    private val habits: HabitRepository,
) {
    suspend fun build(days: Int, today: LocalDate = LocalDate.now(), now: LocalDateTime = LocalDateTime.now()): PerformanceReport {
        val zone = ZoneId.systemDefault()
        val sessions = sessionDao.getBetween(today.minusDays((days * 2).toLong()).toString(), today.toString())
            .mapNotNull { it.toDomainOrNull() }
        val reportTasks = tasks.tasks.first().map { t ->
            val finishedAt = t.completedAt?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
            ReportTask(
                title = t.title,
                due = t.dueDate?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() },
                completed = t.completed,
                completedOn = finishedAt?.toLocalDate(),
                completedAt = finishedAt,
                id = t.id,
            )
        }
        val habitInfos = habits.habits.first().map { it.toInfo() }
        val doneDates = habits.completionsSince(today.minusDays(120)).first().toDoneDates()
        return PerformanceReport.build(days, today, sessions, reportTasks, habitInfos, doneDates, now)
    }
}
