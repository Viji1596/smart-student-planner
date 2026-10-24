package com.smartstudent.planner.ui.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.smartstudent.planner.AppContainer
import com.smartstudent.planner.data.report.SummaryService
import com.smartstudent.planner.domain.ReminderSettings
import com.smartstudent.planner.domain.SummarySchedule
import com.smartstudent.planner.reminders.PlanManager
import com.smartstudent.planner.reminders.ReminderDiagnostics
import com.smartstudent.planner.reminders.ReminderScheduler
import com.smartstudent.planner.reminders.ReminderStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Every change is saved at once; the plan manager notices and sets the alarms again. */
class RemindersViewModel(
    private val store: ReminderStore,
    private val planManager: PlanManager,
    private val scheduler: ReminderScheduler,
    private val summaries: SummaryService,
) : ViewModel() {

    val prefs: StateFlow<ReminderSettings> =
        store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReminderSettings())

    val diagnostics: StateFlow<ReminderDiagnostics> =
        store.diagnostics.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReminderDiagnostics())

    /** Re-checks today's plan and sets the alarms again. */
    fun recheck() {
        viewModelScope.launch { planManager.reconcile(userInitiated = true) }
    }

    /** Publishes today's summary now (as a notification, and in Progress), so you can see what the bedtime one looks like. */
    fun showSummaryNow() {
        viewModelScope.launch { summaries.publish(SummarySchedule.Kind.DAY, java.time.LocalDate.now(), notify = true) }
    }

    /** Sets a real alarm one minute from now. Returns whether exact timing is available. */
    fun scheduleTestAlarm(): Boolean = scheduler.scheduleTest()

    private fun update(transform: (ReminderSettings) -> ReminderSettings) {
        viewModelScope.launch { store.update(transform) }
    }

    fun setEnabled(value: Boolean) = update { it.copy(enabled = value) }
    fun setStartReminder(value: Boolean) = update { it.copy(startReminder = value) }
    fun setLead(value: Int) = update { it.copy(leadMin = value.coerceIn(0, 60)) }
    fun setEndingSoon(value: Boolean) = update { it.copy(endingSoon = value) }
    fun setEndLead(value: Int) = update { it.copy(endLeadMin = value.coerceIn(0, 60)) }
    fun setAtEnd(value: Boolean) = update { it.copy(atEnd = value) }
    fun setTravel(value: Boolean) = update { it.copy(travel = value) }
    fun setMealsAndRest(value: Boolean) = update { it.copy(mealsAndRest = value) }
    fun setMorningSummary(value: Boolean) = update { it.copy(morningSummary = value) }
    fun setDaySummary(value: Boolean) = update { it.copy(daySummary = value) }
    fun setWeekSummary(value: Boolean) = update { it.copy(weekSummary = value) }
    fun setMonthSummary(value: Boolean) = update { it.copy(monthSummary = value) }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { RemindersViewModel(container.reminderStore, container.planManager, container.reminderScheduler, container.summaryService) }
        }
    }
}
