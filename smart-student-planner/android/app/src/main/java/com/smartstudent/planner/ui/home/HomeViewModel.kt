package com.smartstudent.planner.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.smartstudent.planner.AppContainer
import com.smartstudent.planner.data.local.HabitCompletionEntity
import com.smartstudent.planner.data.local.HabitEntity
import com.smartstudent.planner.data.local.PlannedSessionDao
import com.smartstudent.planner.data.local.PlannedSessionEntity
import com.smartstudent.planner.data.local.SettingsEntity
import com.smartstudent.planner.data.local.TaskEntity
import com.smartstudent.planner.data.local.TimetableEntryEntity
import com.smartstudent.planner.data.repo.HabitRepository
import com.smartstudent.planner.data.repo.SettingsRepository
import com.smartstudent.planner.data.repo.TaskRepository
import com.smartstudent.planner.data.repo.TimetableRepository
import com.smartstudent.planner.data.repo.toDomainOrNull
import com.smartstudent.planner.data.repo.toDoneDates
import com.smartstudent.planner.data.repo.toFixedEntry
import com.smartstudent.planner.data.repo.toInfo
import com.smartstudent.planner.data.repo.toRules
import com.smartstudent.planner.domain.DayBuilder
import com.smartstudent.planner.domain.DayView
import com.smartstudent.planner.domain.HistoryEntry
import com.smartstudent.planner.domain.PlanReconciler
import com.smartstudent.planner.domain.PlannedSession
import com.smartstudent.planner.domain.Session
import com.smartstudent.planner.domain.SessionStatus
import com.smartstudent.planner.domain.WorkKind
import com.smartstudent.planner.domain.buildWithCommitted
import com.smartstudent.planner.reminders.PlanManager
import com.smartstudent.planner.reminders.ReminderStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

data class HomeState(
    /** 0 = today, 1 = tomorrow, ... */
    val offset: Int,
    val date: LocalDate,
    val view: DayView,
    val openTasks: Int,
    val overdueTasks: Int,
    val hasTimetable: Boolean,
    /** Today's finished and missed work, one line per task (a task missed 3 times is one line). */
    val earlier: List<HistoryEntry>,
    /** Sessions whose time is over but that weren't ticked off yet (still within the grace period). */
    val justEnded: List<PlannedSession>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    settings: SettingsRepository,
    timetable: TimetableRepository,
    private val tasks: TaskRepository,
    private val habits: HabitRepository,
    private val planManager: PlanManager,
    sessionDao: PlannedSessionDao,
    store: ReminderStore,
) : ViewModel() {

    private val selectedOffset = MutableStateFlow(0)

    /** Re-checks every 30 seconds so the screen follows the clock. */
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(30_000)
        }
    }

    private class Data(
        val settings: SettingsEntity,
        val entries: List<TimetableEntryEntity>,
        val tasks: List<TaskEntity>,
        val habits: List<HabitEntity>,
        val completions: List<HabitCompletionEntity>,
    )

    private class WithPlan(val data: Data, val committed: List<PlannedSessionEntity>, val planDate: String?)

    private val data = combine(
        settings.settings,
        timetable.entries,
        tasks.tasks,
        habits.habits,
        habits.completionsSince(LocalDate.now().minusDays(14)),
    ) { s, entries, taskList, habitList, completions ->
        Data(s, entries, taskList, habitList, completions)
    }

    /** Today's committed plan (it follows the date, so it also works across midnight). */
    private val committedToday = ticker
        .map { LocalDate.now() }
        .distinctUntilChanged()
        .flatMapLatest { sessionDao.observeForDate(it.toString()) }

    private val withPlan = combine(data, committedToday, store.planDate) { d, committed, planDate ->
        WithPlan(d, committed, planDate)
    }

    val state: StateFlow<HomeState?> = combine(withPlan, selectedOffset, ticker) { inp, offset, _ ->
        val d = inp.data
        val today = LocalDate.now()
        val date = today.plusDays(offset.toLong())
        val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
        val now = System.currentTimeMillis()

        val open = d.tasks.filter { !it.completed }
        val entriesByWeekday = d.entries
            .mapNotNull { e -> e.toFixedEntry()?.let { e.dayOfWeek to it } }
            .groupBy({ it.first }, { it.second })

        val committed = inp.committed.mapNotNull { it.toDomainOrNull() }
        val planReady = inp.planDate == today.toString()
        val doneToday: Map<String, Int> =
            if (planReady) committed.filter { it.status == SessionStatus.DONE && it.kind == WorkKind.TASK }
                .groupBy { it.refId }.mapValues { e -> e.value.sumOf { it.duration } }
            else emptyMap()

        val view = if (offset == 0 && planReady) {
            // Today comes from the committed plan, so sessions stay where they were planned.
            DayBuilder.buildWithCommitted(
                rules = d.settings.toRules(),
                entries = entriesByWeekday[today.dayOfWeek.value] ?: emptyList(),
                tasks = open.map { it.toInfo() },
                habits = d.habits.map { it.toInfo() },
                completions = d.completions.toDoneDates(),
                date = today,
                nowMin = nowMin,
                committed = committed,
            )
        } else {
            // Other days (and today before its plan exists) are planned live, in order.
            DayBuilder.buildRange(
                rules = d.settings.toRules(),
                entriesByWeekday = entriesByWeekday,
                tasks = open.map { it.toInfo() },
                habits = d.habits.map { it.toInfo() },
                completions = d.completions.toDoneDates(),
                startDate = today,
                days = offset + 1,
                nowMin = nowMin,
                doneToday = doneToday,
            ).last()
        }

        HomeState(
            offset = offset,
            date = date,
            view = view,
            openTasks = open.size,
            overdueTasks = open.count { it.dueDate != null && it.dueDate < now },
            hasTimetable = d.entries.isNotEmpty(),
            earlier = if (offset == 0 && planReady) {
                // Work that is already finished has nothing left to "make up".
                val finished = d.tasks.filter { it.completed }.map { it.id }.toSet() +
                    d.completions.toDoneDates().filterValues { today in it }.keys
                PlanReconciler.summarizeHistory(committed, finished)
            } else emptyList(),
            justEnded = if (offset == 0 && planReady) PlanReconciler.justEnded(committed, nowMin) else emptyList(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // While the app is alive, notice a session that ran out of time without waiting for an alarm.
        viewModelScope.launch {
            ticker.collect { planManager.reconcileIfNeeded() }
        }
    }

    /** Called when the app comes back to the foreground. */
    fun onResume() {
        viewModelScope.launch { planManager.reconcileIfNeeded() }
    }

    /** Whether the user has switched reminders on (the Home screen suggests it when they haven't). */
    val remindersOn: StateFlow<Boolean?> =
        store.settings.map { it.enabled }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectOffset(offset: Int) {
        selectedOffset.value = offset
    }

    /** The user finished a planned session (only offered for today). */
    fun completeSession(session: Session) {
        viewModelScope.launch {
            session.plannedId?.let { planManager.markDone(it) }
            recordProgress(session.item.kind, session.item.refId, session.duration)
        }
    }

    /** "Later": skip this session; its work is planned again for later today. */
    fun skipSession(session: Session) {
        val id = session.plannedId ?: return
        viewModelScope.launch { planManager.skip(id) }
    }

    /** "I did it" on a line that says the task was missed (once, however many times it was missed). */
    fun markMissedDone(entry: HistoryEntry) {
        viewModelScope.launch {
            val minutes = planManager.completeMissed(entry.kind, entry.refId)
            if (minutes > 0) recordProgress(entry.kind, entry.refId, minutes)
        }
    }

    fun replan() {
        viewModelScope.launch { planManager.replanNow() }
    }

    fun undoHabit(habitId: String) {
        viewModelScope.launch { habits.setDone(habitId, LocalDate.now(), false) }
    }

    private suspend fun recordProgress(kind: WorkKind, refId: String, minutes: Int) {
        when (kind) {
            WorkKind.TASK -> tasks.logProgress(refId, minutes)
            WorkKind.HABIT -> habits.setDone(refId, LocalDate.now(), true)
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    container.settingsRepository,
                    container.timetableRepository,
                    container.taskRepository,
                    container.habitRepository,
                    container.planManager,
                    container.plannedSessionDao,
                    container.reminderStore,
                )
            }
        }
    }
}
