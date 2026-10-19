package com.smartstudent.planner

import android.app.Application
import com.smartstudent.planner.sync.SyncScheduler

class PlannerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncScheduler.schedulePeriodic(this)
    }
}
