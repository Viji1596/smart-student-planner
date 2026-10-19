package com.smartstudent.planner.data.repo

import android.content.Context
import android.util.Log
import com.smartstudent.planner.data.local.TimetableDao
import com.smartstudent.planner.data.local.TimetableEntryEntity
import com.smartstudent.planner.data.remote.PlannerApi
import com.smartstudent.planner.data.remote.TimetableEntryDto
import com.smartstudent.planner.data.remote.TimetableUpsertRequest
import com.smartstudent.planner.data.remote.TokenStore
import com.smartstudent.planner.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException
import java.util.UUID

class TimetableRepository(
    private val api: PlannerApi,
    private val dao: TimetableDao,
    private val tokenStore: TokenStore,
    private val appContext: Context,
) {
    val entries: Flow<List<TimetableEntryEntity>> = dao.observeActive()

    /** One entry is created per selected weekday (1 = Monday ... 7 = Sunday). */
    suspend fun add(days: Set<Int>, title: String, startTime: String, endTime: String) {
        val now = System.currentTimeMillis()
        dao.upsertAll(
            days.sorted().map { day ->
                TimetableEntryEntity(
                    id = UUID.randomUUID().toString(),
                    dayOfWeek = day,
                    title = title.trim(),
                    startTime = startTime,
                    endTime = endTime,
                    deleted = false,
                    dirty = true,
                    updatedAtLocal = now,
                )
            }
        )
        SyncScheduler.syncSoon(appContext)
    }

    suspend fun update(entry: TimetableEntryEntity, title: String, startTime: String, endTime: String) {
        dao.upsert(
            entry.copy(
                title = title.trim(),
                startTime = startTime,
                endTime = endTime,
                dirty = true,
                updatedAtLocal = System.currentTimeMillis(),
            )
        )
        SyncScheduler.syncSoon(appContext)
    }

    suspend fun delete(entry: TimetableEntryEntity) {
        dao.upsert(entry.copy(deleted = true, dirty = true, updatedAtLocal = System.currentTimeMillis()))
        SyncScheduler.syncSoon(appContext)
    }

    suspend fun push() {
        for (entry in dao.getDirty()) {
            try {
                if (entry.deleted) {
                    api.deleteTimetableEntry(entry.id)
                    dao.removeDeletedIfUnchanged(entry.id, entry.updatedAtLocal)
                } else {
                    api.putTimetableEntry(
                        entry.id,
                        TimetableUpsertRequest(entry.dayOfWeek, entry.title, entry.startTime, entry.endTime),
                    )
                    dao.markCleanIfUnchanged(entry.id, entry.updatedAtLocal)
                }
            } catch (e: HttpException) {
                if (isRetryable(e)) throw e
                Log.w(TAG, "Server rejected timetable entry ${entry.id} (${e.code()}); skipping")
            }
        }
    }

    suspend fun pull() {
        val response = api.getTimetable(tokenStore.lastSyncTimetable())
        for (remote in response.entries) {
            val local = dao.get(remote.id)
            if (local?.dirty == true) continue
            if (remote.deleted) dao.remove(remote.id) else dao.upsert(remote.toEntity())
        }
        tokenStore.setLastSyncTimetable(response.serverTime)
    }

    private fun TimetableEntryDto.toEntity() = TimetableEntryEntity(
        id = id,
        dayOfWeek = dayOfWeek,
        title = title,
        startTime = startTime,
        endTime = endTime,
        deleted = false,
        dirty = false,
        updatedAtLocal = 0L,
    )

    private companion object {
        const val TAG = "TimetableRepository"
    }
}
