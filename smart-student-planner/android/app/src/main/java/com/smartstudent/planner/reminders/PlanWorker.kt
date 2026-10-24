package com.smartstudent.planner.reminders

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.smartstudent.planner.PlannerApp
import java.util.concurrent.TimeUnit

/** Re-checks today's plan and sets the alarms. Run by alarms, after a reboot, and hourly as a safety net. */
class PlanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            (applicationContext as PlannerApp).container.planManager.reconcile()
        } catch (e: Exception) {
            Log.w("PlanWorker", "Plan check failed: ${e.message}")
        }
        return Result.success()
    }

    companion object {
        fun runSoon(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "plan-reconcile", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<PlanWorker>().build(),
            )
        }

        /** Some phones stop apps' alarms to save battery. This hourly check catches up if that happens. */
        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "plan-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PlanWorker>(1, TimeUnit.HOURS).build(),
            )
        }
    }
}
