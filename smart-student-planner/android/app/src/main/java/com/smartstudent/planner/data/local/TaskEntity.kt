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
    /** Local only (not synced): don't plan this task before this day (yyyy-MM-dd). Set by "do it tomorrow". */
    val deferUntil: String? = null,
    /** Local only (not synced): when it was marked done on this phone. Used for the performance report. */
    val completedAt: Long? = null,
    /** Local only: the progress it had just before it was finished, so un-finishing can put it back. */
    val spentBeforeDone: Int? = null,
)
