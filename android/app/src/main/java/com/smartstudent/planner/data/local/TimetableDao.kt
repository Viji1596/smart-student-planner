package com.smartstudent.planner.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TimetableDao {
    @Query("SELECT * FROM timetable_entries WHERE deleted = 0 ORDER BY dayOfWeek ASC, startTime ASC")
    fun observeActive(): Flow<List<TimetableEntryEntity>>

    @Query("SELECT * FROM timetable_entries WHERE dirty = 1")
    suspend fun getDirty(): List<TimetableEntryEntity>

    @Query("SELECT * FROM timetable_entries WHERE id = :id")
    suspend fun get(id: String): TimetableEntryEntity?

    @Upsert
    suspend fun upsert(entry: TimetableEntryEntity)

    @Upsert
    suspend fun upsertAll(entries: List<TimetableEntryEntity>)

    @Query("DELETE FROM timetable_entries WHERE id = :id")
    suspend fun remove(id: String)

    @Query("UPDATE timetable_entries SET dirty = 0 WHERE id = :id AND updatedAtLocal = :stamp")
    suspend fun markCleanIfUnchanged(id: String, stamp: Long)

    @Query("DELETE FROM timetable_entries WHERE id = :id AND deleted = 1 AND updatedAtLocal = :stamp")
    suspend fun removeDeletedIfUnchanged(id: String, stamp: Long)
}
