package com.smartstudent.planner.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A recurring weekly commitment (a class, a lab, ...). dayOfWeek: 1 = Monday ... 7 = Sunday. */
@Entity(tableName = "timetable_entries")
data class TimetableEntryEntity(
    @PrimaryKey val id: String,
    val dayOfWeek: Int,
    val title: String,
    val startTime: String, // "HH:mm"
    val endTime: String,   // "HH:mm"
    val deleted: Boolean,
    val dirty: Boolean,
    val updatedAtLocal: Long,
)
