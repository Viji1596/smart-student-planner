package com.smartstudent.planner.data.remote

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "planner_prefs")

/**
 * Holds the login session and sync cursor.
 * NOTE: tokens are stored in app-private storage, unencrypted. Before a public
 * release, wrap them with Android Keystore (e.g. Tink).
 */
class TokenStore(private val context: Context) {

    private object Keys {
        val ACCESS = stringPreferencesKey("access_token")
        val REFRESH = stringPreferencesKey("refresh_token")
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_EMAIL = stringPreferencesKey("user_email")
        val DB_OWNER = stringPreferencesKey("db_owner_id")
        val LAST_SYNC = stringPreferencesKey("last_sync")
        val LAST_SYNC_TIMETABLE = stringPreferencesKey("last_sync_timetable")
        val LAST_SYNC_HABITS = stringPreferencesKey("last_sync_habits")
        val LAST_SYNC_COMPLETIONS = stringPreferencesKey("last_sync_completions")
    }

    private val data: Flow<Preferences> get() = context.dataStore.data

    val accessToken: Flow<String?> = data.map { it[Keys.ACCESS] }
    val userName: Flow<String?> = data.map { it[Keys.USER_NAME] }

    suspend fun accessTokenNow(): String? = accessToken.first()
    suspend fun refreshTokenNow(): String? = data.map { it[Keys.REFRESH] }.first()

    suspend fun saveSession(auth: AuthResponse) {
        context.dataStore.edit {
            it[Keys.ACCESS] = auth.accessToken
            it[Keys.REFRESH] = auth.refreshToken
            it[Keys.USER_NAME] = auth.user.name
            it[Keys.USER_EMAIL] = auth.user.email
        }
    }

    /** Forget the login but keep the local data cursor/owner (see AuthRepository). */
    suspend fun clearSession() {
        context.dataStore.edit {
            it.remove(Keys.ACCESS)
            it.remove(Keys.REFRESH)
            it.remove(Keys.USER_NAME)
            it.remove(Keys.USER_EMAIL)
        }
    }

    suspend fun dbOwner(): String? = data.map { it[Keys.DB_OWNER] }.first()
    suspend fun setDbOwner(id: String?) {
        context.dataStore.edit { if (id == null) it.remove(Keys.DB_OWNER) else it[Keys.DB_OWNER] = id }
    }

    suspend fun lastSync(): String? = data.map { it[Keys.LAST_SYNC] }.first()
    suspend fun setLastSync(value: String?) {
        context.dataStore.edit { if (value == null) it.remove(Keys.LAST_SYNC) else it[Keys.LAST_SYNC] = value }
    }

    suspend fun lastSyncTimetable(): String? = data.map { it[Keys.LAST_SYNC_TIMETABLE] }.first()
    suspend fun setLastSyncTimetable(value: String?) {
        context.dataStore.edit {
            if (value == null) it.remove(Keys.LAST_SYNC_TIMETABLE) else it[Keys.LAST_SYNC_TIMETABLE] = value
        }
    }

    suspend fun lastSyncHabits(): String? = data.map { it[Keys.LAST_SYNC_HABITS] }.first()
    suspend fun setLastSyncHabits(value: String?) {
        context.dataStore.edit {
            if (value == null) it.remove(Keys.LAST_SYNC_HABITS) else it[Keys.LAST_SYNC_HABITS] = value
        }
    }

    suspend fun lastSyncCompletions(): String? = data.map { it[Keys.LAST_SYNC_COMPLETIONS] }.first()
    suspend fun setLastSyncCompletions(value: String?) {
        context.dataStore.edit {
            if (value == null) it.remove(Keys.LAST_SYNC_COMPLETIONS) else it[Keys.LAST_SYNC_COMPLETIONS] = value
        }
    }

    /** Forget every sync position, so the next sync downloads everything again. */
    suspend fun clearSyncCursors() {
        context.dataStore.edit {
            it.remove(Keys.LAST_SYNC)
            it.remove(Keys.LAST_SYNC_TIMETABLE)
            it.remove(Keys.LAST_SYNC_HABITS)
            it.remove(Keys.LAST_SYNC_COMPLETIONS)
        }
    }
}
