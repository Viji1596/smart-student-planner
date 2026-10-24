package com.smartstudent.planner.data.repo

import com.smartstudent.planner.data.local.HabitCompletionEntity
import com.smartstudent.planner.data.local.HabitEntity
import com.smartstudent.planner.data.local.PlannedSessionEntity
import com.smartstudent.planner.data.local.SettingsEntity
import com.smartstudent.planner.data.local.TaskEntity
import com.smartstudent.planner.data.local.TimetableEntryEntity
import com.smartstudent.planner.data.remote.SettingsDto
import com.smartstudent.planner.domain.BreakRules
import com.smartstudent.planner.domain.Commute
import com.smartstudent.planner.domain.DayRules
import com.smartstudent.planner.domain.FixedEntry
import com.smartstudent.planner.domain.HabitInfo
import com.smartstudent.planner.domain.HabitKind
import com.smartstudent.planner.domain.MealSlot
import com.smartstudent.planner.domain.PlannedSession
import com.smartstudent.planner.domain.Priority
import com.smartstudent.planner.domain.SessionStatus
import com.smartstudent.planner.domain.WorkKind
import com.smartstudent.planner.domain.RestSlot
import com.smartstudent.planner.domain.TaskInfo
import com.smartstudent.planner.domain.TimeUtil
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Turns the stored settings into the plain rules the planner understands. */
fun SettingsEntity.toRules(): DayRules {
    fun t(value: String, fallback: Int) = TimeUtil.parse(value) ?: fallback
    return DayRules(
        wakeMin = t(wakeTime, 6 * 60 + 30),
        sleepMin = t(sleepTime, 23 * 60),
        morningRoutineMin = morningRoutineMinutes,
        meals = listOf(
            MealSlot("Breakfast", t(breakfastStart, 8 * 60), breakfastMinutes),
            MealSlot("Lunch", t(lunchStart, 13 * 60), lunchMinutes),
            MealSlot("Dinner", t(dinnerStart, 20 * 60), dinnerMinutes),
        ),
        rest = if (restMinutes > 0) RestSlot(t(restStart, 16 * 60), restMinutes) else null,
        commute = Commute(commuteToMinutes, commuteFromMinutes, arriveEarlyMinutes),
        breaks = BreakRules(breakMinutes, longBreakAfterMinutes, longBreakMinutes),
    )
}

fun TimetableEntryEntity.toFixedEntry(): FixedEntry? {
    val start = TimeUtil.parse(startTime) ?: return null
    val end = TimeUtil.parse(endTime) ?: return null
    return if (end > start) FixedEntry(title, start, end) else null
}

fun SettingsEntity.toDto() = SettingsDto(
    wakeTime = wakeTime,
    sleepTime = sleepTime,
    morningRoutineMinutes = morningRoutineMinutes,
    breakfastStart = breakfastStart,
    breakfastMinutes = breakfastMinutes,
    lunchStart = lunchStart,
    lunchMinutes = lunchMinutes,
    dinnerStart = dinnerStart,
    dinnerMinutes = dinnerMinutes,
    timezone = timezone,
    commuteToMinutes = commuteToMinutes,
    commuteFromMinutes = commuteFromMinutes,
    arriveEarlyMinutes = arriveEarlyMinutes,
    restStart = restStart,
    restMinutes = restMinutes,
    breakMinutes = breakMinutes,
    longBreakAfterMinutes = longBreakAfterMinutes,
    longBreakMinutes = longBreakMinutes,
)

fun SettingsDto.toEntity() = SettingsEntity(
    wakeTime = wakeTime,
    sleepTime = sleepTime,
    morningRoutineMinutes = morningRoutineMinutes,
    breakfastStart = breakfastStart,
    breakfastMinutes = breakfastMinutes,
    lunchStart = lunchStart,
    lunchMinutes = lunchMinutes,
    dinnerStart = dinnerStart,
    dinnerMinutes = dinnerMinutes,
    timezone = timezone,
    commuteToMinutes = commuteToMinutes,
    commuteFromMinutes = commuteFromMinutes,
    arriveEarlyMinutes = arriveEarlyMinutes,
    restStart = restStart,
    restMinutes = restMinutes,
    breakMinutes = breakMinutes,
    longBreakAfterMinutes = longBreakAfterMinutes,
    longBreakMinutes = longBreakMinutes,
    dirty = false,
    updatedAtLocal = 0L,
)

fun TaskEntity.toInfo(zone: ZoneId = ZoneId.systemDefault()) = TaskInfo(
    id = id,
    title = title,
    priority = priority,
    estimatedMinutes = estimatedMinutes,
    spentMinutes = spentMinutes,
    dueDay = dueDate?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() },
    notBefore = deferUntil?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
)

fun HabitEntity.toInfo() = HabitInfo(
    id = id,
    title = title,
    minutes = minutes,
    priority = priority,
    kind = runCatching { HabitKind.valueOf(kind) }.getOrDefault(HabitKind.DAILY),
    days = days.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet(),
    timesPerWeek = timesPerWeek,
)

/** habit id -> the dates it was done. Rows with an unreadable date are ignored. */
fun List<HabitCompletionEntity>.toDoneDates(): Map<String, Set<LocalDate>> =
    mapNotNull { c -> runCatching { LocalDate.parse(c.date) }.getOrNull()?.let { c.habitId to it } }
        .groupBy({ it.first }, { it.second })
        .mapValues { it.value.toSet() }

/** Rows that can't be read (e.g. from a future version) are skipped rather than crashing. */
fun PlannedSessionEntity.toDomainOrNull(): PlannedSession? = runCatching {
    PlannedSession(
        id = id,
        date = LocalDate.parse(date),
        startMin = startMin,
        endMin = endMin,
        kind = WorkKind.valueOf(kind),
        refId = refId,
        title = title,
        priority = Priority.parse(priority),
        status = SessionStatus.valueOf(status),
        pinned = pinned,
    )
}.getOrNull()

fun PlannedSession.toEntity() = PlannedSessionEntity(
    id = id,
    date = date.toString(),
    startMin = startMin,
    endMin = endMin,
    kind = kind.name,
    refId = refId,
    title = title,
    priority = priority.name,
    status = status.name,
    pinned = pinned,
)
