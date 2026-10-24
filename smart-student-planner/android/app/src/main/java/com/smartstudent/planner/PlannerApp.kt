package com.smartstudent.planner

import android.app.Application
import com.smartstudent.planner.reminders.Notifier
import com.smartstudent.planner.reminders.PlanWorker
import com.smartstudent.planner.sync.SyncScheduler

class PlannerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncScheduler.schedulePeriodic(this)

        // Reminders: channels, an hourly safety-net check, and keeping the plan in step with the data.
        Notifier.createChannels(this)
        PlanWorker.schedulePeriodic(this)
        container.planManager.start()
    }
}
