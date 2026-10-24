package com.smartstudent.planner.reminders

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.smartstudent.planner.domain.ReminderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.reminderDataStore by preferencesDataStore(name = "reminder_prefs")

/** What the planner last did, so the Reminders screen can show why a reminder is (not) coming. */
data class ReminderDiagnostics(
    val lastRunMillis: Long? = null,
    val lastError: String? = null,
    /** The next reminders that are set, like "14:55 · Assignment ends in 5 min". */
    val upcoming: List<String> = emptyList(),
    /** How many alarms are set in total (reminders plus the "was it missed?" checks). */
    val alarmCount: Int = 0,
)

/**
 * Reminder choices and the planner's bookkeeping. These belong to this device, so they are not
 * synced: you may want reminders on your phone but not on a second device.
 */
class ReminderStore(private val context: Context) {

    private object K {
        val ENABLED = booleanPreferencesKey("enabled")
        val START = booleanPreferencesKey("start_reminder")
        val LEAD = intPreferencesKey("lead_min")
        val ENDING = booleanPreferencesKey("ending_soon")
        val END_LEAD = intPreferencesKey("end_lead_min")
        val AT_END = booleanPreferencesKey("at_end")
        val TRAVEL = booleanPreferencesKey("travel")
        val MEALS = booleanPreferencesKey("meals_and_rest")
        val MORNING = booleanPreferencesKey("morning_summary")
        val DAY_SUMMARY = booleanPreferencesKey("day_summary")
        val WEEK_SUMMARY = booleanPreferencesKey("week_summary")
        val MONTH_SUMMARY = booleanPreferencesKey("month_summary")
        val PLAN_DATE = stringPreferencesKey("plan_date")
        val PLAN_FINGERPRINT = stringPreferencesKey("plan_fingerprint")
        val ALARM_CODES = stringSetPreferencesKey("alarm_codes")
        val LAST_RUN = longPreferencesKey("last_run_millis")
        val LAST_ERROR = stringPreferencesKey("last_error")
        val UPCOMING = stringPreferencesKey("upcoming")
        val ALARM_COUNT = intPreferencesKey("alarm_count")
    }

    private fun Preferences.toSettings(): ReminderSettings {
        val d = ReminderSettings()
        return ReminderSettings(
            enabled = this[K.ENABLED] ?: d.enabled,
            startReminder = this[K.START] ?: d.startReminder,
            leadMin = this[K.LEAD] ?: d.leadMin,
            endingSoon = this[K.ENDING] ?: d.endingSoon,
            endLeadMin = this[K.END_LEAD] ?: d.endLeadMin,
            atEnd = this[K.AT_END] ?: d.atEnd,
            travel = this[K.TRAVEL] ?: d.travel,
            mealsAndRest = this[K.MEALS] ?: d.mealsAndRest,
            morningSummary = this[K.MORNING] ?: d.morningSummary,
            daySummary = this[K.DAY_SUMMARY] ?: d.daySummary,
            weekSummary = this[K.WEEK_SUMMARY] ?: d.weekSummary,
            monthSummary = this[K.MONTH_SUMMARY] ?: d.monthSummary,
        )
    }

    val settings: Flow<ReminderSettings> = context.reminderDataStore.data.map { it.toSettings() }

    suspend fun settingsNow(): ReminderSettings = settings.first()

    suspend fun update(transform: (ReminderSettings) -> ReminderSettings) {
        context.reminderDataStore.edit { prefs ->
            val s = transform(prefs.toSettings())
            prefs[K.ENABLED] = s.enabled
            prefs[K.START] = s.startReminder
            prefs[K.LEAD] = s.leadMin
            prefs[K.ENDING] = s.endingSoon
            prefs[K.END_LEAD] = s.endLeadMin
            prefs[K.AT_END] = s.atEnd
            prefs[K.TRAVEL] = s.travel
            prefs[K.MEALS] = s.mealsAndRest
            prefs[K.MORNING] = s.morningSummary
            prefs[K.DAY_SUMMARY] = s.daySummary
            prefs[K.WEEK_SUMMARY] = s.weekSummary
            prefs[K.MONTH_SUMMARY] = s.monthSummary
        }
    }

    // ---- planning bookkeeping

    /** The date today's committed plan was made for (yyyy-MM-dd), or null. */
    val planDate: Flow<String?> = context.reminderDataStore.data.map { it[K.PLAN_DATE] }

    suspend fun planState(): Pair<String?, String?> {
        val prefs = context.reminderDataStore.data.first()
        return prefs[K.PLAN_DATE] to prefs[K.PLAN_FINGERPRINT]
    }

    suspend fun setPlanState(date: String, fingerprint: String) {
        context.reminderDataStore.edit {
            it[K.PLAN_DATE] = date
            it[K.PLAN_FINGERPRINT] = fingerprint
        }
    }

    suspend fun alarmCodes(): Set<String> = context.reminderDataStore.data.first()[K.ALARM_CODES] ?: emptySet()

    suspend fun setAlarmCodes(codes: Set<String>) {
        context.reminderDataStore.edit { it[K.ALARM_CODES] = codes }
    }

    // ---- diagnostics

    val diagnostics: Flow<ReminderDiagnostics> = context.reminderDataStore.data.map {
        ReminderDiagnostics(
            lastRunMillis = it[K.LAST_RUN],
            lastError = it[K.LAST_ERROR],
            upcoming = (it[K.UPCOMING] ?: "").split("\n").filter { line -> line.isNotBlank() },
            alarmCount = it[K.ALARM_COUNT] ?: 0,
        )
    }

    suspend fun setDiagnostics(runMillis: Long, upcoming: List<String>, alarmCount: Int) {
        context.reminderDataStore.edit {
            it[K.LAST_RUN] = runMillis
            it[K.UPCOMING] = upcoming.joinToString("\n")
            it[K.ALARM_COUNT] = alarmCount
            it.remove(K.LAST_ERROR)
        }
    }

    suspend fun setError(runMillis: Long, message: String) {
        context.reminderDataStore.edit {
            it[K.LAST_RUN] = runMillis
            it[K.LAST_ERROR] = message.take(300)
        }
    }
}
