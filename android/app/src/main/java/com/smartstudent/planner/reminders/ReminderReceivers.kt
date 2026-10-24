package com.smartstudent.planner.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.smartstudent.planner.PlannerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** An alarm went off: show the reminder. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(ReminderIntents.EXTRA_TITLE) ?: return
        val text = intent.getStringExtra(ReminderIntents.EXTRA_TEXT) ?: ""
        Notifier.show(
            context,
            intent.getIntExtra(ReminderIntents.EXTRA_ID, 0),
            title,
            text,
            sessionId = intent.getStringExtra(ReminderIntents.EXTRA_SESSION_ID),
        )
    }
}

/** The Done / Later buttons on a "Time's up" notification: handled without opening the app. */
class SessionActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(ReminderIntents.EXTRA_SESSION_ID) ?: return
        val notificationId = intent.getIntExtra(ReminderIntents.EXTRA_ID, 0)
        val action = intent.action
        val pending = goAsync()
        val container = (context.applicationContext as PlannerApp).container
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (action) {
                    ReminderIntents.ACTION_DONE -> container.planManager.completeFromNotification(sessionId)
                    ReminderIntents.ACTION_LATER -> container.planManager.skip(sessionId)
                }
            } finally {
                NotificationManagerCompat.from(context).cancel(notificationId)
                pending.finish()
            }
        }
    }
}

/**
 * A planned session should just have ended: find out whether it was missed and re-plan if so.
 * This runs right here, not in a background job: while the phone is idle Android postpones background
 * jobs, which would delay the "missed" notice by a long time. An alarm may run code immediately.
 */
class MissedCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val container = (context.applicationContext as PlannerApp).container
        CoroutineScope(Dispatchers.Default).launch {
            try {
                container.planManager.reconcile()
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * The bedtime alarm: publishes today's summary (and the week's on Sundays, the month's on its last day), then plans
 * again, which sets the alarm for the next bedtime.
 */
class SummaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val container = (context.applicationContext as PlannerApp).container
        CoroutineScope(Dispatchers.Default).launch {
            try {
                container.summaryService.publishDue()
                container.planManager.reconcile()
            } finally {
                pending.finish()
            }
        }
    }
}

/** After a reboot or an app update the alarms are gone: plan again, which sets them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PlanWorker.runSoon(context)
        PlanWorker.schedulePeriodic(context)
    }
}
