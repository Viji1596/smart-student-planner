package com.smartstudent.planner.data.repo

import android.content.Context
import android.util.Log
import com.smartstudent.planner.data.local.TaskDao
import com.smartstudent.planner.data.local.TaskEntity
import com.smartstudent.planner.data.remote.PlannerApi
import com.smartstudent.planner.data.remote.TaskDto
import com.smartstudent.planner.data.remote.TaskUpsertRequest
import com.smartstudent.planner.data.remote.TokenStore
import com.smartstudent.planner.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException
import java.time.Instant
import java.util.UUID

/**
 * Offline-first: the UI only ever talks to the local database.
 * Every change is saved locally (dirty = true) and pushed to the server by sync().
 */
class TaskRepository(
    private val api: PlannerApi,
    private val dao: TaskDao,
    private val tokenStore: TokenStore,
    private val appContext: Context,
) {
    val tasks: Flow<List<TaskEntity>> = dao.observeActive()

    suspend fun get(id: String): TaskEntity? = dao.get(id)?.takeIf { !it.deleted }

    /** Adds a task and returns its id (so the assistant can remember "the task I just added"). */
    suspend fun add(title: String, priority: String, minutes: Int, dueDate: Long?): String {
        val id = UUID.randomUUID().toString()
        dao.upsert(
            TaskEntity(
                id = id,
                title = title.trim(),
                priority = priority,
                estimatedMinutes = minutes,
                spentMinutes = 0,
                dueDate = dueDate,
                completed = false,
                deleted = false,
                dirty = true,
                updatedAtLocal = System.currentTimeMillis(),
            )
        )
        SyncScheduler.syncSoon(appContext)
        return id
    }

    /**
     * Change a task after it was added. Progress already made is kept; if the new duration is no
     * bigger than what was already done, the task counts as finished.
     */
    suspend fun update(task: TaskEntity, title: String, priority: String, minutes: Int, dueDate: Long?) {
        val spent = minOf(task.spentMinutes, minutes)
        dao.upsert(
            task.copy(
                title = title.trim(),
                priority = priority,
                estimatedMinutes = minutes,
                dueDate = dueDate,
                spentMinutes = spent,
                completed = task.completed || spent >= minutes,
                dirty = true,
                updatedAtLocal = System.currentTimeMillis(),
                completedAt = if (task.completed || spent >= minutes) (task.completedAt ?: System.currentTimeMillis()) else null,
            )
        )
        SyncScheduler.syncSoon(appContext)
    }

    suspend fun setCompleted(task: TaskEntity, completed: Boolean) {
        // Finishing a task means all of it is done; un-finishing keeps the progress made so far.
        val spent = if (completed) task.estimatedMinutes else minOf(task.spentMinutes, task.estimatedMinutes - 1)
        dao.upsert(
            task.copy(
                completed = completed,
                spentMinutes = spent.coerceAtLeast(0),
                dirty = true,
                updatedAtLocal = System.currentTimeMillis(),
                completedAt = if (completed) System.currentTimeMillis() else null,
            )
        )
        SyncScheduler.syncSoon(appContext)
    }

    /**
     * Don't plan this task before [until] (null brings it back). This stays on this phone: it is not synced,
     * and it doesn't touch the task itself, so nothing needs pushing.
     */
    suspend fun defer(taskId: String, until: java.time.LocalDate?) {
        val task = dao.get(taskId) ?: return
        dao.upsert(task.copy(deferUntil = until?.toString()))
    }

    /** "I worked on this for [minutes]". Completes the task once all estimated minutes are done. */
    suspend fun logProgress(taskId: String, minutes: Int) {
        val task = dao.get(taskId) ?: return
        val spent = minOf(task.estimatedMinutes, task.spentMinutes + minutes)
        dao.upsert(
            task.copy(
                spentMinutes = spent,
                completed = spent >= task.estimatedMinutes,
                dirty = true,
                updatedAtLocal = System.currentTimeMillis(),
                completedAt = if (spent >= task.estimatedMinutes) System.currentTimeMillis() else task.completedAt,
            )
        )
        SyncScheduler.syncSoon(appContext)
    }

    suspend fun delete(task: TaskEntity) {
        dao.upsert(task.copy(deleted = true, dirty = true, updatedAtLocal = System.currentTimeMillis()))
        SyncScheduler.syncSoon(appContext)
    }

    /** Send local changes to the server. Called by SyncManager. */
    suspend fun push() {
        for (task in dao.getDirty()) {
            try {
                if (task.deleted) {
                    api.deleteTask(task.id)
                    dao.removeDeletedIfUnchanged(task.id, task.updatedAtLocal)
                } else {
                    api.putTask(task.id, task.toRequest())
                    dao.markCleanIfUnchanged(task.id, task.updatedAtLocal)
                }
            } catch (e: HttpException) {
                // A task the server permanently rejects must not block all the others.
                if (isRetryable(e)) throw e
                Log.w(TAG, "Server rejected task ${task.id} (${e.code()}); skipping")
            }
        }
    }

    /** Fetch changes made on other devices. Called by SyncManager. */
    suspend fun pull() {
        val response = api.getTasks(tokenStore.lastSync())
        for (remote in response.tasks) {
            val local = dao.get(remote.id)
            if (local?.dirty == true) continue // local edit wins; it will be pushed next
            if (remote.deleted) {
                dao.remove(remote.id)
            } else {
                // What only this phone knows (put off until a day, when it was finished here) survives a sync.
                val finishedElsewhere = local != null && !local.completed && remote.completed
                dao.upsert(
                    remote.toEntity().copy(
                        deferUntil = local?.deferUntil,
                        completedAt = local?.completedAt ?: if (finishedElsewhere) System.currentTimeMillis() else null,
                    )
                )
            }
        }
        tokenStore.setLastSync(response.serverTime)
    }

    private fun TaskDto.toEntity() = TaskEntity(
        id = id,
        title = title,
        priority = priority,
        estimatedMinutes = estimatedMinutes,
        spentMinutes = spentMinutes,
        dueDate = dueDate?.let { Instant.parse(it).toEpochMilli() },
        completed = completed,
        deleted = false,
        dirty = false,
        updatedAtLocal = 0L,
    )

    private fun TaskEntity.toRequest() = TaskUpsertRequest(
        title = title,
        priority = priority,
        estimatedMinutes = estimatedMinutes,
        spentMinutes = spentMinutes,
        dueDate = dueDate?.let { Instant.ofEpochMilli(it).toString() },
        completed = completed,
    )

    private companion object {
        const val TAG = "TaskRepository"
    }
}
