package com.smartstudent.planner.ui.progress

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
import com.smartstudent.planner.data.repo.toDoneDates
import com.smartstudent.planner.data.repo.toFixedEntry
import com.smartstudent.planner.data.repo.toInfo
import com.smartstudent.planner.data.repo.toRules
import com.smartstudent.planner.domain.DayBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.LocalTime

data class ProgressState(
    /** Monday..Sunday of the current week. */
    val weekDates: List<LocalDate>,
    /** How many habits were ticked off on each of those days. */
    val habitsDonePerDay: List<Int>,
    val tasksDone: Int,
    val tasksOpen: Int,
    /** The planned work (in minutes) for today and the next six days. */
    val plannedNext7: List<Int>,
    val nextDates: List<LocalDate>,
)

class ProgressViewModel(container: AppContainer) : ViewModel() {

    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
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
        container.settingsRepository.settings,
        container.timetableRepository.entries,
        container.taskRepository.tasks,
        container.habitRepository.habits,
        container.habitRepository.completionsSince(LocalDate.now().minusDays(14)),
    ) { s, e, t, h, c -> Inputs(s, e, t, h, c) }

    val state: StateFlow<ProgressState?> = combine(inputs, ticker) { inp, _ ->
        val today = LocalDate.now()
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val week = (0..6).map { monday.plusDays(it.toLong()) }

        val done = inp.completions.toDoneDates()
        val liveHabitIds = inp.habits.map { it.id }.toSet()
        val perDay = week.map { date ->
            done.count { (habitId, dates) -> habitId in liveHabitIds && date in dates }
        }

        val open = inp.tasks.filter { !it.completed }
        val entriesByWeekday = inp.entries
            .mapNotNull { e -> e.toFixedEntry()?.let { e.dayOfWeek to it } }
            .groupBy({ it.first }, { it.second })
        val planned = DayBuilder.buildRange(
            rules = inp.settings.toRules(),
            entriesByWeekday = entriesByWeekday,
            tasks = open.map { it.toInfo() },
            habits = inp.habits.map { it.toInfo() },
            completions = done,
            startDate = today,
            days = 7,
            nowMin = LocalTime.now().let { it.hour * 60 + it.minute },
        ).map { it.schedule.plannedMinutes }

        ProgressState(
            weekDates = week,
            habitsDonePerDay = perDay,
            tasksDone = inp.tasks.count { it.completed },
            tasksOpen = open.size,
            plannedNext7 = planned,
            nextDates = (0..6).map { today.plusDays(it.toLong()) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProgressViewModel(container) }
        }
    }
}
