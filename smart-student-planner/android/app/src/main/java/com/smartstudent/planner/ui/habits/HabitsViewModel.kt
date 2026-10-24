package com.smartstudent.planner.ui.habits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.smartstudent.planner.AppContainer
import com.smartstudent.planner.data.local.HabitEntity
import com.smartstudent.planner.data.repo.HabitRepository
import com.smartstudent.planner.data.repo.toDoneDates
import com.smartstudent.planner.data.repo.toInfo
import com.smartstudent.planner.domain.HabitInfo
import com.smartstudent.planner.domain.HabitKind
import com.smartstudent.planner.domain.HabitRules
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class HabitRow(
    val entity: HabitEntity,
    val info: HabitInfo,
    val doneThisWeek: Int,
    val doneToday: Boolean,
)

class HabitsViewModel(private val repo: HabitRepository) : ViewModel() {

    val rows: StateFlow<List<HabitRow>> = combine(
        repo.habits,
        repo.completionsSince(LocalDate.now().minusDays(14)),
    ) { habits, completions ->
        val done = completions.toDoneDates()
        val today = LocalDate.now()
        habits.map { entity ->
            val dates = done[entity.id] ?: emptySet()
            HabitRow(
                entity = entity,
                info = entity.toInfo(),
                doneThisWeek = HabitRules.doneThisWeek(dates, today),
                doneToday = today in dates,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(title: String, minutes: Int, priority: String, kind: HabitKind, days: Set<Int>, perWeek: Int) {
        viewModelScope.launch { repo.add(title, minutes, priority, kind, days, perWeek) }
    }

    fun update(habit: HabitEntity, title: String, minutes: Int, priority: String, kind: HabitKind, days: Set<Int>, perWeek: Int) {
        viewModelScope.launch { repo.update(habit, title, minutes, priority, kind, days, perWeek) }
    }

    fun delete(habit: HabitEntity) {
        viewModelScope.launch { repo.delete(habit) }
    }

    fun setDoneToday(habitId: String, done: Boolean) {
        viewModelScope.launch { repo.setDone(habitId, LocalDate.now(), done) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { HabitsViewModel(container.habitRepository) }
        }
    }
}
