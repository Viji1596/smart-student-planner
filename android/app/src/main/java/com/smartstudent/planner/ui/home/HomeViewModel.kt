package com.smartstudent.planner.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.smartstudent.planner.AppContainer
import com.smartstudent.planner.data.local.HabitCompletionEntity
import com.smartstudent.planner.data.local.HabitEntity
import com.smartstudent.planner.data.local.SettingsEntity
import com.smartstudent.planner.data.local.TaskEntity
import com.smartstudent.planner.data.local.TimetableEntryEntity
import com.smartstudent.planner.data.repo.HabitRepository
import com.smartstudent.planner.data.repo.SettingsRepository
import com.smartstudent.planner.data.repo.TaskRepository
import com.smartstudent.planner.data.repo.TimetableRepository
import com.smartstudent.planner.data.repo.toDoneDates
import com.smartstudent.planner.data.repo.toFixedEntry
import com.smartstudent.planner.data.repo.toInfo
import com.smartstudent.planner.data.repo.toRules
import com.smartstudent.planner.domain.DayBuilder
import com.smartstudent.planner.domain.DayView
import com.smartstudent.planner.domain.WorkItem
import com.smartstudent.planner.domain.WorkKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
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
)

class HomeViewModel(
    settings: SettingsRepository,
    timetable: TimetableRepository,
    private val tasks: TaskRepository,
    private val habits: HabitRepository,
) : ViewModel() {

    private val selectedOffset = MutableStateFlow(0)

    /** Re-plans every 30 seconds so "from now" stays right as the day goes on. */
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(30_000)
        }
    }

    private class Inputs(
        val settings: SettingsEntity,
        val entries: List<TimetableEntryEntity>,
        val tasks: List<TaskEntity>,
        val habits: List<HabitEntity>,
        val completions: List<HabitCompletionEntity>,
    )

    private val inputs = combine(
        settings.settings,
        timetable.entries,
        tasks.tasks,
        habits.habits,
        habits.completionsSince(LocalDate.now().minusDays(14)),
    ) { s, entries, taskList, habitList, completions ->
        Inputs(s, entries, taskList, habitList, completions)
    }

    val state: StateFlow<HomeState?> = combine(inputs, selectedOffset, ticker) { inp, offset, _ ->
        val today = LocalDate.now()
        val date = today.plusDays(offset.toLong())
        val now = System.currentTimeMillis()

        val open = inp.tasks.filter { !it.completed }
        val entriesByWeekday = inp.entries
            .mapNotNull { e -> e.toFixedEntry()?.let { e.dayOfWeek to it } }
            .groupBy({ it.first }, { it.second })

        // Days are planned in order (today first) so work is never planned twice.
        val view = DayBuilder.buildRange(
            rules = inp.settings.toRules(),
            entriesByWeekday = entriesByWeekday,
            tasks = open.map { it.toInfo() },
            habits = inp.habits.map { it.toInfo() },
            completions = inp.completions.toDoneDates(),
            startDate = today,
            days = offset + 1,
            nowMin = LocalTime.now().let { it.hour * 60 + it.minute },
        ).last()
        HomeState(
            offset = offset,
            date = date,
            view = view,
            openTasks = open.size,
            overdueTasks = open.count { it.dueDate != null && it.dueDate < now },
            hasTimetable = inp.entries.isNotEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectOffset(offset: Int) {
        selectedOffset.value = offset
    }

    /** The user finished a planned session (only offered for today). */
    fun completeSession(item: WorkItem, minutes: Int) {
        viewModelScope.launch {
            when (item.kind) {
                WorkKind.TASK -> tasks.logProgress(item.refId, minutes)
                WorkKind.HABIT -> habits.setDone(item.refId, LocalDate.now(), true)
            }
        }
    }

    fun undoHabit(habitId: String) {
        viewModelScope.launch { habits.setDone(habitId, LocalDate.now(), false) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    container.settingsRepository,
                    container.timetableRepository,
                    container.taskRepository,
                    container.habitRepository,
                )
            }
        }
    }
}
