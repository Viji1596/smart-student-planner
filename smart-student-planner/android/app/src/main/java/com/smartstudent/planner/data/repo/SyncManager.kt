package com.smartstudent.planner.data.repo

import android.util.Log
import com.smartstudent.planner.data.remote.TokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Runs one full sync: push everything local, then pull everything remote. */
class SyncManager(
    private val tokenStore: TokenStore,
    private val tasks: TaskRepository,
    private val timetable: TimetableRepository,
    private val settings: SettingsRepository,
    private val habits: HabitRepository,
) {
    private val mutex = Mutex()

    suspend fun sync(): Result<Unit> = mutex.withLock {
        if (tokenStore.accessTokenNow() == null) return@withLock Result.success(Unit)
        try {
            tasks.push()
            timetable.push()
            settings.push()
            habits.push()
            tasks.pull()
            timetable.pull()
            settings.pull()
            habits.pull()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("SyncManager", "Sync failed: ${e.message}")
            Result.failure(e)
        }
    }
}
