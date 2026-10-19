package com.smartstudent.planner.data.repo

import android.content.Context
import android.util.Log
import com.smartstudent.planner.data.local.HabitCompletionDao
import com.smartstudent.planner.data.local.HabitCompletionEntity
import com.smartstudent.planner.data.local.HabitDao
import com.smartstudent.planner.data.local.HabitEntity
import com.smartstudent.planner.data.remote.CompletionDto
import com.smartstudent.planner.data.remote.CompletionUpsertRequest
import com.smartstudent.planner.data.remote.HabitDto
import com.smartstudent.planner.data.remote.HabitUpsertRequest
import com.smartstudent.planner.data.remote.PlannerApi
import com.smartstudent.planner.data.remote.TokenStore
import com.smartstudent.planner.domain.HabitKind
import com.smartstudent.planner.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException
import java.time.LocalDate
import java.util.UUID

/** Recurring tasks and the record of which days they were done. */
class HabitRepository(
    private val api: PlannerApi,
    private val habitDao: HabitDao,
    private val completionDao: HabitCompletionDao,
    private val tokenStore: TokenStore,
    private val appContext: Context,
) {
    val habits: Flow<List<HabitEntity>> = habitDao.observeActive()

    /** Completions from [fromDate] on (ISO text, e.g. "2026-10-12"). */
    fun completionsSince(fromDate: LocalDate): Flow<List<HabitCompletionEntity>> =
        completionDao.observeSince(fromDate.toString())

    suspend fun add(
        title: String,
        minutes: Int,
        priority: String,
        kind: HabitKind,
        days: Set<Int>,
        timesPerWeek: Int,
    ) {
        habitDao.upsert(
            HabitEntity(
                id = UUID.randomUUID().toString(),
                title = title.trim(),
                minutes = minutes,
                priority = priority,
                kind = kind.name,
                days = if (kind == HabitKind.DAYS) days.sorted().joinToString(",") else "",
                timesPerWeek = if (kind == HabitKind.WEEKLY) timesPerWeek else 0,
                deleted = false,
                dirty = true,
                updatedAtLocal = System.currentTimeMillis(),
            )
        )
        SyncScheduler.syncSoon(appContext)
    }

    suspend fun update(
        habit: HabitEntity,
        title: String,
        minutes: Int,
        priority: String,
        kind: HabitKind,
        days: Set<Int>,
        timesPerWeek: Int,
    ) {
        habitDao.upsert(
            habit.copy(
                title = title.trim(),
                minutes = minutes,
                priority = priority,
                kind = kind.name,
                days = if (kind == HabitKind.DAYS) days.sorted().joinToString(",") else "",
                timesPerWeek = if (kind == HabitKind.WEEKLY) timesPerWeek else 0,
                dirty = true,
                updatedAtLocal = System.currentTimeMillis(),
            )
        )
        SyncScheduler.syncSoon(appContext)
    }

    suspend fun delete(habit: HabitEntity) {
        habitDao.upsert(habit.copy(deleted = true, dirty = true, updatedAtLocal = System.currentTimeMillis()))
        SyncScheduler.syncSoon(appContext)
    }

    /** Mark a habit done (or not done) on [date]. The id is deterministic, so devices agree. */
    suspend fun setDone(habitId: String, date: LocalDate, done: Boolean) {
        val id = "${habitId}_$date"
        val existing = completionDao.get(id)
        if (existing == null && !done) return // nothing was ever recorded, nothing to undo
        completionDao.upsert(
            HabitCompletionEntity(
                id = id,
                habitId = habitId,
                date = date.toString(),
                deleted = !done,
                dirty = true,
                updatedAtLocal = System.currentTimeMillis(),
            )
        )
        SyncScheduler.syncSoon(appContext)
    }

    /** Habits first: the server rejects a completion for a habit it doesn't know yet. */
    suspend fun push() {
        for (habit in habitDao.getDirty()) {
            try {
                if (habit.deleted) {
                    api.deleteHabit(habit.id)
                    habitDao.removeDeletedIfUnchanged(habit.id, habit.updatedAtLocal)
                } else {
                    api.putHabit(
                        habit.id,
                        HabitUpsertRequest(
                            title = habit.title,
                            minutes = habit.minutes,
                            priority = habit.priority,
                            kind = habit.kind,
                            days = habit.days,
                            timesPerWeek = habit.timesPerWeek,
                        ),
                    )
                    habitDao.markCleanIfUnchanged(habit.id, habit.updatedAtLocal)
                }
            } catch (e: HttpException) {
                if (isRetryable(e)) throw e
                Log.w(TAG, "Server rejected habit ${habit.id} (${e.code()}); skipping")
            }
        }
        for (c in completionDao.getDirty()) {
            try {
                if (c.deleted) {
                    api.deleteCompletion(c.id)
                    completionDao.removeDeletedIfUnchanged(c.id, c.updatedAtLocal)
                } else {
                    api.putCompletion(c.id, CompletionUpsertRequest(c.habitId, c.date))
                    completionDao.markCleanIfUnchanged(c.id, c.updatedAtLocal)
                }
            } catch (e: HttpException) {
                if (isRetryable(e)) throw e
                Log.w(TAG, "Server rejected completion ${c.id} (${e.code()}); skipping")
            }
        }
    }

    suspend fun pull() {
        val habits = api.getHabits(tokenStore.lastSyncHabits())
        for (remote in habits.habits) {
            val local = habitDao.get(remote.id)
            if (local?.dirty == true) continue
            if (remote.deleted) habitDao.remove(remote.id) else habitDao.upsert(remote.toEntity())
        }
        tokenStore.setLastSyncHabits(habits.serverTime)

        val completions = api.getCompletions(tokenStore.lastSyncCompletions())
        for (remote in completions.completions) {
            val local = completionDao.get(remote.id)
            if (local?.dirty == true) continue
            if (remote.deleted) completionDao.remove(remote.id) else completionDao.upsert(remote.toEntity())
        }
        tokenStore.setLastSyncCompletions(completions.serverTime)
    }

    private fun HabitDto.toEntity() = HabitEntity(
        id = id,
        title = title,
        minutes = minutes,
        priority = priority,
        kind = kind,
        days = days,
        timesPerWeek = timesPerWeek,
        deleted = false,
        dirty = false,
        updatedAtLocal = 0L,
    )

    private fun CompletionDto.toEntity() = HabitCompletionEntity(
        id = id,
        habitId = habitId,
        date = date,
        deleted = false,
        dirty = false,
        updatedAtLocal = 0L,
    )

    private companion object {
        const val TAG = "HabitRepository"
    }
}
