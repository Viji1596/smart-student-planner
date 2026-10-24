package com.smartstudent.planner.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.smartstudent.planner.domain.Reminder

object ReminderIntents {
    const val ACTION_REMINDER = "com.smartstudent.planner.REMINDER"
    const val ACTION_CHECK = "com.smartstudent.planner.MISSED_CHECK"
    const val ACTION_DONE = "com.smartstudent.planner.SESSION_DONE"
    const val ACTION_LATER = "com.smartstudent.planner.SESSION_LATER"
    const val ACTION_SUMMARY = "com.smartstudent.planner.SUMMARY"
    const val EXTRA_SESSION_ID = "session_id"
    const val EXTRA_ID = "id"
    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"
}

/**
 * Owns every alarm the app sets. [replaceAll] cancels the alarms it set last time and sets the new
 * ones, so the alarms always match the current plan.
 */
class ReminderScheduler(private val context: Context, private val store: ReminderStore) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private companion object {
        val SUMMARY_CODE = "SUMMARY_ALARM".hashCode()
    }

    /** [reminders] pair a time (epoch millis) with what to show; [checkTimes] are "was it missed?" moments. */
    suspend fun replaceAll(reminders: List<Pair<Long, Reminder>>, checkTimes: List<Long>) {
        cancelStored()
        val now = System.currentTimeMillis()
        val codes = mutableSetOf<String>()

        for ((at, reminder) in reminders) {
            if (at <= now) continue
            val code = ("R|" + reminder.key).hashCode()
            val intent = reminderIntent()
                .putExtra(ReminderIntents.EXTRA_ID, code)
                .putExtra(ReminderIntents.EXTRA_TITLE, reminder.title)
                .putExtra(ReminderIntents.EXTRA_TEXT, reminder.text)
                .putExtra(ReminderIntents.EXTRA_SESSION_ID, reminder.sessionId)
            set(at, code, intent)
            codes += "R:$code"
        }
        for (at in checkTimes) {
            if (at <= now) continue
            val code = ("C|$at").hashCode()
            set(at, code, checkIntent())
            codes += "C:$code"
        }
        store.setAlarmCodes(codes)
    }

    /** True when alarms can be set for an exact minute (otherwise Android may delay them by minutes). */
    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /**
     * Sets one real alarm for [delayMillis] from now that shows a notification through the same path
     * as every reminder. It checks that alarms get through while the app is closed.
     */
    fun scheduleTest(delayMillis: Long = 60_000L): Boolean {
        val intent = reminderIntent()
            .putExtra(ReminderIntents.EXTRA_ID, 4243)
            .putExtra(ReminderIntents.EXTRA_TITLE, "Test alarm worked")
            .putExtra(ReminderIntents.EXTRA_TEXT, "This came through the alarm system, like your real reminders.")
        set(System.currentTimeMillis() + delayMillis, "TEST_ALARM".hashCode(), intent)
        return canScheduleExact()
    }

    suspend fun cancelAll() {
        cancelStored()
        cancelSummary()
        store.setAlarmCodes(emptySet())
    }

    /** One alarm at bedtime for the daily / weekly / monthly summary. [atMillis] null cancels it. */
    fun scheduleSummary(atMillis: Long?) {
        if (atMillis == null) {
            cancelSummary()
            return
        }
        set(atMillis, SUMMARY_CODE, summaryIntent())
    }

    private fun cancelSummary() {
        PendingIntent.getBroadcast(context, SUMMARY_CODE, summaryIntent(), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            ?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
    }

    private fun summaryIntent() = Intent(context, SummaryReceiver::class.java).setAction(ReminderIntents.ACTION_SUMMARY)

    private suspend fun cancelStored() {
        for (entry in store.alarmCodes()) {
            val type = entry.substringBefore(':')
            val code = entry.substringAfter(':').toIntOrNull() ?: continue
            val intent = if (type == "R") reminderIntent() else checkIntent()
            PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                ?.let {
                    alarmManager.cancel(it)
                    it.cancel()
                }
        }
    }

    private fun reminderIntent() =
        Intent(context, ReminderReceiver::class.java).setAction(ReminderIntents.ACTION_REMINDER)

    private fun checkIntent() =
        Intent(context, MissedCheckReceiver::class.java).setAction(ReminderIntents.ACTION_CHECK)

    private fun set(atMillis: Long, code: Int, intent: Intent) {
        val pending = PendingIntent.getBroadcast(
            context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Exact timing needs a permission the user must grant on newer Android versions.
        // Without it the alarm may be a few minutes late, but it still fires.
        val canBeExact = canScheduleExact()
        try {
            if (canBeExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            }
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }
    }
}
