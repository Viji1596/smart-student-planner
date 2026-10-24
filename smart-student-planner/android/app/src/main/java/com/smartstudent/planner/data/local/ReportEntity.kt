package com.smartstudent.planner.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A published summary: the daily, weekly or monthly analysis. They are kept on this phone so they can be read
 * again in the app, not only seen once as a notification.
 */
@Entity(tableName = "reports")
data class ReportEntity(
    /** "DAY-2026-10-24", "WEEK-2026-10-25", "MONTH-2026-10-31": one report per period, so re-publishing replaces it. */
    @PrimaryKey val id: String,
    val kind: String,        // DAY, WEEK or MONTH
    val periodStart: String, // yyyy-MM-dd
    val periodEnd: String,   // yyyy-MM-dd
    val title: String,
    val body: String,
    val createdAt: Long,
)

@Dao
interface ReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(report: ReportEntity)

    @Query("SELECT * FROM reports ORDER BY periodEnd DESC, createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ReportEntity>>

    @Query("DELETE FROM reports WHERE periodEnd < :before")
    suspend fun deleteBefore(before: String)
}
