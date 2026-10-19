package com.smartstudent.planner.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A recurring task. kind is "DAILY", "DAYS" or "WEEKLY" (see domain.HabitKind). */
@Entity(tableName = "habits")
data class HabitEntity(
    @PrimaryKey val id: String,
    val title: String,
    val minutes: Int,
    val priority: String,
    val kind: String,
    /** Comma separated ISO weekdays (1 = Monday ... 7 = Sunday). Only used when kind = DAYS. */
    val days: String,
    val timesPerWeek: Int,
    val deleted: Boolean,
    val dirty: Boolean,
    val updatedAtLocal: Long,
)

/** "I did this habit on this date". id is always "<habitId>_<yyyy-MM-dd>". */
@Entity(tableName = "habit_completions")
data class HabitCompletionEntity(
    @PrimaryKey val id: String,
    val habitId: String,
    val date: String,
    val deleted: Boolean,
    val dirty: Boolean,
    val updatedAtLocal: Long,
)
