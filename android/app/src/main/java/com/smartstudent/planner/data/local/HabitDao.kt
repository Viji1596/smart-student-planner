package com.smartstudent.planner.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits WHERE deleted = 0 ORDER BY title ASC")
    fun observeActive(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits WHERE dirty = 1")
    suspend fun getDirty(): List<HabitEntity>

    @Query("SELECT * FROM habits WHERE id = :id")
    suspend fun get(id: String): HabitEntity?

    @Upsert
    suspend fun upsert(habit: HabitEntity)

    @Query("DELETE FROM habits WHERE id = :id")
    suspend fun remove(id: String)

    @Query("UPDATE habits SET dirty = 0 WHERE id = :id AND updatedAtLocal = :stamp")
    suspend fun markCleanIfUnchanged(id: String, stamp: Long)

    @Query("DELETE FROM habits WHERE id = :id AND deleted = 1 AND updatedAtLocal = :stamp")
    suspend fun removeDeletedIfUnchanged(id: String, stamp: Long)
}

@Dao
interface HabitCompletionDao {
    /** Dates are ISO strings, so comparing them as text sorts them correctly. */
    @Query("SELECT * FROM habit_completions WHERE deleted = 0 AND date >= :fromDate")
    fun observeSince(fromDate: String): Flow<List<HabitCompletionEntity>>

    @Query("SELECT * FROM habit_completions WHERE dirty = 1")
    suspend fun getDirty(): List<HabitCompletionEntity>

    @Query("SELECT * FROM habit_completions WHERE id = :id")
    suspend fun get(id: String): HabitCompletionEntity?

    @Upsert
    suspend fun upsert(completion: HabitCompletionEntity)

    @Query("DELETE FROM habit_completions WHERE id = :id")
    suspend fun remove(id: String)

    @Query("UPDATE habit_completions SET dirty = 0 WHERE id = :id AND updatedAtLocal = :stamp")
    suspend fun markCleanIfUnchanged(id: String, stamp: Long)

    @Query("DELETE FROM habit_completions WHERE id = :id AND deleted = 1 AND updatedAtLocal = :stamp")
    suspend fun removeDeletedIfUnchanged(id: String, stamp: Long)
}
