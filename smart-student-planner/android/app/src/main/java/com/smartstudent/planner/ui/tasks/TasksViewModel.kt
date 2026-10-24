package com.smartstudent.planner.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.smartstudent.planner.AppContainer
import com.smartstudent.planner.data.local.TaskEntity
import com.smartstudent.planner.data.repo.TaskRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TasksViewModel(private val repo: TaskRepository) : ViewModel() {

    val tasks: StateFlow<List<TaskEntity>> =
        repo.tasks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(title: String, priority: String, minutes: Int, dueDate: Long?) {
        viewModelScope.launch { repo.add(title, priority, minutes, dueDate) }
    }

    fun update(task: TaskEntity, title: String, priority: String, minutes: Int, dueDate: Long?) {
        viewModelScope.launch { repo.update(task, title, priority, minutes, dueDate) }
    }

    fun setCompleted(task: TaskEntity, completed: Boolean) {
        viewModelScope.launch { repo.setCompleted(task, completed) }
    }

    fun delete(task: TaskEntity) {
        viewModelScope.launch { repo.delete(task) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { TasksViewModel(container.taskRepository) }
        }
    }
}
