package com.smartstudent.planner.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.smartstudent.planner.AppContainer
import com.smartstudent.planner.data.local.TaskEntity
import com.smartstudent.planner.data.repo.TaskRepository
import com.smartstudent.planner.domain.Priority
import com.smartstudent.planner.domain.WorkKind
import com.smartstudent.planner.reminders.PlanManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TasksViewModel(private val repo: TaskRepository, private val planManager: PlanManager) : ViewModel() {

    val tasks: StateFlow<List<TaskEntity>> =
        repo.tasks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(title: String, priority: String, minutes: Int, dueDate: Long?) {
        viewModelScope.launch { repo.add(title, priority, minutes, dueDate) }
    }

    fun update(task: TaskEntity, title: String, priority: String, minutes: Int, dueDate: Long?) {
        viewModelScope.launch { repo.update(task, title, priority, minutes, dueDate) }
    }

    fun setCompleted(task: TaskEntity, completed: Boolean) {
        viewModelScope.launch {
            // finishing a task here counts like ticking its session on Home: it shows under "Earlier today"
            if (completed && !task.completed) {
                planManager.recordDone(WorkKind.TASK, task.id, task.title, Priority.parse(task.priority), maxOf(5, task.estimatedMinutes - task.spentMinutes))
            }
            repo.setCompleted(task, completed)
        }
    }

    fun delete(task: TaskEntity) {
        viewModelScope.launch { repo.delete(task) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { TasksViewModel(container.taskRepository, container.planManager) }
        }
    }
}
