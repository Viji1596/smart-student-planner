package com.smartstudent.planner.ui.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.smartstudent.planner.AppContainer
import com.smartstudent.planner.data.local.TimetableEntryEntity
import com.smartstudent.planner.data.repo.TimetableRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class TimetableViewModel(private val repo: TimetableRepository) : ViewModel() {

    private val _selectedDay = MutableStateFlow(LocalDate.now().dayOfWeek.value)
    val selectedDay: StateFlow<Int> = _selectedDay

    /** Entries of the selected day, earliest first. */
    val entries: StateFlow<List<TimetableEntryEntity>> =
        combine(repo.entries, _selectedDay) { all, day -> all.filter { it.dayOfWeek == day } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectDay(day: Int) {
        _selectedDay.value = day
    }

    fun add(days: Set<Int>, title: String, start: String, end: String) {
        viewModelScope.launch { repo.add(days, title, start, end) }
    }

    fun update(entry: TimetableEntryEntity, title: String, start: String, end: String) {
        viewModelScope.launch { repo.update(entry, title, start, end) }
    }

    fun delete(entry: TimetableEntryEntity) {
        viewModelScope.launch { repo.delete(entry) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { TimetableViewModel(container.timetableRepository) }
        }
    }
}
