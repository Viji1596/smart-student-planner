package com.smartstudent.planner.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** One study session in today's committed plan. Lives on this device only (not synced). */
@Entity(tableName = "planned_sessions")
data class PlannedSessionEntity(
    @PrimaryKey val id: String,
    val date: String, // yyyy-MM-dd
    val startMin: Int,
    val endMin: Int,
    val kind: String,     // TASK or HABIT
    val refId: String,
    val title: String,
    val priority: String,
    val status: String,   // PLANNED, DONE or MISSED
    /** Placed by hand (e.g. via the assistant): re-planning keeps it where it is. */
    val pinned: Boolean = false,
)

@Dao
abstract class PlannedSessionDao {
    @Query("SELECT * FROM planned_sessions WHERE date = :date ORDER BY startMin ASC")
    abstract fun observeForDate(date: String): Flow<List<PlannedSessionEntity>>

    @Query("SELECT * FROM planned_sessions WHERE date = :date ORDER BY startMin ASC")
    abstract suspend fun getForDate(date: String): List<PlannedSessionEntity>

    @Query("DELETE FROM planned_sessions WHERE date = :date")
    abstract suspend fun deleteForDate(date: String)

    @Query("DELETE FROM planned_sessions WHERE date < :date")
    abstract suspend fun deleteBefore(date: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(items: List<PlannedSessionEntity>)

    @Query("SELECT * FROM planned_sessions WHERE id = :id")
    abstract suspend fun getById(id: String): PlannedSessionEntity?

    @Query("UPDATE planned_sessions SET status = :status WHERE id = :id")
    abstract suspend fun updateStatus(id: String, status: String)

    /** Forget the "missed" records of one task or habit today (it was done, so they no longer matter). */
    @Query("DELETE FROM planned_sessions WHERE date = :date AND kind = :kind AND refId = :refId AND status = 'MISSED'")
    abstract suspend fun deleteMissedFor(date: String, kind: String, refId: String)

    @Query("DELETE FROM planned_sessions WHERE date = :date AND kind = :kind AND refId = :refId AND status = 'MISSED' AND id != :keepId")
    abstract suspend fun deleteOtherMissed(date: String, kind: String, refId: String, keepId: String)

    /** "I did it" on a missed line: one record becomes DONE, the repeats of it are removed. */
    @Transaction
    open suspend fun resolveMissed(date: String, kind: String, refId: String, keepId: String) {
        deleteOtherMissed(date, kind, refId, keepId)
        updateStatus(keepId, "DONE")
    }

    @Query("DELETE FROM planned_sessions WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    /** Moving a session changes its id (the id contains the start time): swap the old row for the new one. */
    @Transaction
    open suspend fun replaceOne(oldId: String, item: PlannedSessionEntity) {
        deleteById(oldId)
        insertAll(listOf(item))
    }

    @Transaction
    open suspend fun replaceForDate(date: String, items: List<PlannedSessionEntity>) {
        deleteForDate(date)
        insertAll(items)
    }
}
