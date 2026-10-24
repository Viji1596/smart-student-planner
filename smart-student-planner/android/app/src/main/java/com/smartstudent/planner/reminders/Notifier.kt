package com.smartstudent.planner.reminders

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.smartstudent.planner.MainActivity
import com.smartstudent.planner.R

object Notifier {
    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_PLAN = "plan_changes"
    const val CHANNEL_SUMMARY = "summaries"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "Study reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders before study sessions, trips, meals and rest"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PLAN, "Plan changes", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a session was missed and your plan was updated"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SUMMARY, "Daily, weekly and monthly summaries", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "At bedtime: how many tasks you finished, and your weekly and monthly analysis"
            }
        )
    }

    /** Returns false if notifications are blocked (permission denied or turned off for the app). */
    @SuppressLint("MissingPermission")
    fun show(
        context: Context,
        id: Int,
        title: String,
        text: String,
        channel: String = CHANNEL_REMINDERS,
        /** When set, the notification gets Done and Later buttons for that study session. */
        sessionId: String? = null,
    ): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)

        if (sessionId != null) {
            builder.addAction(0, "Done", sessionAction(context, id, sessionId, ReminderIntents.ACTION_DONE))
            builder.addAction(0, "Later", sessionAction(context, id, sessionId, ReminderIntents.ACTION_LATER))
        }
        manager.notify(id, builder.build())
        return true
    }

    private fun sessionAction(context: Context, notificationId: Int, sessionId: String, action: String): PendingIntent {
        val intent = Intent(context, SessionActionReceiver::class.java)
            .setAction(action)
            .putExtra(ReminderIntents.EXTRA_SESSION_ID, sessionId)
            .putExtra(ReminderIntents.EXTRA_ID, notificationId)
        return PendingIntent.getBroadcast(
            context,
            ("$action|$sessionId").hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
