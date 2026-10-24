package com.smartstudent.planner.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query(
        """SELECT * FROM tasks WHERE deleted = 0
           ORDER BY completed ASC, CASE WHEN dueDate IS NULL THEN 1 ELSE 0 END, dueDate ASC, title ASC"""
    )
    fun observeActive(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE dirty = 1")
    suspend fun getDirty(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): TaskEntity?

    @Upsert
    suspend fun upsert(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun remove(id: String)

    // Only mark clean if the user did not edit the row while we were pushing it.
    @Query("UPDATE tasks SET dirty = 0 WHERE id = :id AND updatedAtLocal = :stamp")
    suspend fun markCleanIfUnchanged(id: String, stamp: Long)

    @Query("DELETE FROM tasks WHERE id = :id AND deleted = 1 AND updatedAtLocal = :stamp")
    suspend fun removeDeletedIfUnchanged(id: String, stamp: Long)

    @Query("DELETE FROM tasks")
    suspend fun clear()
}
