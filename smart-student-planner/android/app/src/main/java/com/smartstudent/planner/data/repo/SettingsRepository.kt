package com.smartstudent.planner.data.repo

import android.content.Context
import android.util.Log
import com.smartstudent.planner.data.local.SettingsDao
import com.smartstudent.planner.data.local.SettingsEntity
import com.smartstudent.planner.data.remote.PlannerApi
import com.smartstudent.planner.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import retrofit2.HttpException

class SettingsRepository(
    private val api: PlannerApi,
    private val dao: SettingsDao,
    private val appContext: Context,
) {
    /** Falls back to defaults until the user saves something. */
    val settings: Flow<SettingsEntity> = dao.observe().map { it ?: SettingsEntity.defaults() }

    suspend fun save(settings: SettingsEntity) {
        dao.upsert(settings.copy(id = 1, dirty = true, updatedAtLocal = System.currentTimeMillis()))
        SyncScheduler.syncSoon(appContext)
    }

    suspend fun push() {
        val local = dao.get() ?: return
        if (!local.dirty) return
        try {
            api.putSettings(local.toDto())
            dao.markCleanIfUnchanged(local.updatedAtLocal)
        } catch (e: HttpException) {
            if (isRetryable(e)) throw e
            Log.w(TAG, "Server rejected settings (${e.code()}); skipping")
        }
    }

    suspend fun pull() {
        val remote = api.getSettings().settings ?: return
        if (dao.get()?.dirty == true) return // local edit wins; it was/will be pushed
        dao.upsert(remote.toEntity())
    }

    private companion object {
        const val TAG = "SettingsRepository"
    }
}
