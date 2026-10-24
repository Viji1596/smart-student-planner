package com.smartstudent.planner.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.smartstudent.planner.AppContainer
import com.smartstudent.planner.data.local.ReportEntity
import com.smartstudent.planner.domain.PerformanceReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

enum class Period(val label: String, val days: Int) {
    TODAY("Today", 1),
    WEEK("This week", 7),
    MONTH("This month", 30),
}

/** The grade, the finished-against-scheduled bars and the published summaries on the Progress screen. */
class PerformanceViewModel(private val container: AppContainer) : ViewModel() {

    private val _period = MutableStateFlow(Period.WEEK)
    val period: StateFlow<Period> = _period.asStateFlow()

    fun setPeriod(period: Period) {
        _period.value = period
    }

    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(30_000)
        }
    }

    /** Rebuilt whenever the period, the tasks or today's sessions change (and every half minute). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val report: StateFlow<PerformanceReport?> = combine(
        _period,
        container.taskRepository.tasks,
        container.plannedSessionDao.observeForDate(LocalDate.now().toString()),
        ticker,
    ) { period, _, _, _ -> period }
        .mapLatest { container.reportBuilder.build(it.days) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The published daily, weekly and monthly summaries, newest first. */
    val published: StateFlow<List<ReportEntity>> = container.reportDao.observeRecent(12)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { PerformanceViewModel(container) }
        }
    }
}
