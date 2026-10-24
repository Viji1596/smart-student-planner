package com.smartstudent.planner.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One task as stored on the phone.
 *  - dirty:   changed locally and not yet pushed to the server
 *  - deleted: deleted locally; kept until the server confirms
 *  - updatedAtLocal: time of the last local edit; lets sync detect edits made mid-push
 */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val priority: String,
    val estimatedMinutes: Int,
    /** Minutes already worked on. The planner only schedules estimatedMinutes - spentMinutes. */
    val spentMinutes: Int,
    val dueDate: Long?,
    val completed: Boolean,
    val deleted: Boolean,
    val dirty: Boolean,
    val updatedAtLocal: Long,
)
